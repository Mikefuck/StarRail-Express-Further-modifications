package com.habitrain.core.vote;

import com.habitrain.core.api.VoteOption;
import com.habitrain.core.api.VoteResult;
import com.habitrain.core.game.sre.RepairModeManager;
import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.network.OptionVotePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 通用选项投票管理器（模式/地图等字符串选项，非玩家 UUID）。
 * 按 dimension 隔离，每次最多一个 active 投票。
 * 每人 1 票，允许改票与弃票（optionId null）。
 */
public final class OptionVoteManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("OptionVoteManager");

    private static final ConcurrentMap<ResourceKey<Level>, State> STATES = new ConcurrentHashMap<>();

    private OptionVoteManager() {}

    private static final class State {
        boolean active;
        String voteId = "";
        String title = "";
        String description = "";
        String resolvedOptionId = "";
        int remainingSeconds;
        int totalSeconds;
        final List<VoteOption> options = new ArrayList<>();
        final Map<UUID, String> votesByVoter = new HashMap<>(); // voter -> optionId
        @Nullable Consumer<VoteResult> onResolved;
        List<MapVoteProfilePayload> profilePayloads = List.of();
        long stateVersion;
        long lastSentVersion = -1L;
    }

    private static State getOrCreate(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new State());
    }

    /**
     * 发起一次选项投票。
     *
     * @return true 成功发起；false 已有 active / 选项空 / duration &lt; 1
     */
    public static boolean start(ServerLevel level, String voteId, String title, String description,
                                List<VoteOption> options, int durationSeconds,
                                Consumer<VoteResult> onResolved) {
        if (level == null || options == null || options.isEmpty() || durationSeconds < 1) {
            return false;
        }
        if (voteId == null || voteId.isBlank()) {
            return false;
        }
        State state = getOrCreate(level);
        if (state.active) {
            LOGGER.warn("[OptionVote] start ignored: vote already active (voteId={})", state.voteId);
            return false;
        }

        state.active = true;
        state.voteId = voteId;
        state.title = title == null ? "" : title;
        state.description = description == null ? "" : description;
        state.resolvedOptionId = "";
        state.remainingSeconds = durationSeconds;
        state.totalSeconds = durationSeconds;
        state.options.clear();
        state.options.addAll(options);
        state.votesByVoter.clear();
        state.onResolved = onResolved;
        state.profilePayloads = List.of();
        markChanged(state);

        LOGGER.info("[OptionVote] started voteId={} options={} duration={}s",
                voteId, state.options.size(), durationSeconds);
        broadcastState(level);
        return true;
    }

    /**
     * 投票或弃票。
     * {@code voteId} 必须与当前 active 投票匹配，否则 no-op。
     * {@code optionId == null} 表示弃票。
     *
     * @return true 表示投票或弃票请求已被当前投票接受
     */
    public static boolean cast(ServerLevel level, UUID voterId, @Nullable String voteId, @Nullable String optionId) {
        if (level == null || voterId == null) return false;
        State state = STATES.get(level.dimension());
        if (state == null || !state.active) return false;
        if (voteId == null || !voteId.equals(state.voteId)) return false;

        // Only online, non-repairer players in this dimension may cast.
        ServerPlayer voter = level.getServer() != null
                ? level.getServer().getPlayerList().getPlayer(voterId) : null;
        if (voter == null || voter.serverLevel() != level) return false;
        if (RepairModeManager.isRepairer(voter)) return false;
        if (voter.isSpectator()) return false;

        if (optionId != null) {
            boolean known = false;
            for (VoteOption opt : state.options) {
                if (opt.id().equals(optionId)) {
                    known = true;
                    break;
                }
            }
            if (!known) return false;
            state.votesByVoter.put(voterId, optionId);
        } else {
            state.votesByVoter.remove(voterId);
        }
        markChanged(state);
        broadcastState(level);
        return true;
    }

    /** 当前 active 投票 id；无 active 时返回 null。 */
    public static @Nullable String currentVoteId(ServerLevel level) {
        if (level == null) return null;
        State state = STATES.get(level.dimension());
        if (state == null || !state.active) return null;
        return state.voteId;
    }

    /** 每秒调用一次：倒计时，到 0 时结算。 */
    public static void tickSecond(ServerLevel level) {
        State state = STATES.get(level.dimension());
        if (state == null || !state.active) return;

        state.remainingSeconds--;
        markChanged(state);
        if (state.remainingSeconds <= 0) {
            resolve(level, state);
        } else {
            broadcastState(level);
        }
    }

    private static void resolve(ServerLevel level, State state) {
        state.active = false;

        Map<String, Integer> tallies = new HashMap<>();
        for (VoteOption opt : state.options) {
            tallies.put(opt.id(), 0);
        }
        for (String optionId : state.votesByVoter.values()) {
            tallies.merge(optionId, 1, Integer::sum);
        }

        int totalVotes = state.votesByVoter.size();
        boolean randomPick = false;
        String winnerId = null;

        if (state.options.isEmpty()) {
            // 不应发生：start 已拒绝空选项
            winnerId = null;
        } else if (totalVotes == 0) {
            // 全员 0 票：在所有选项中随机
            randomPick = true;
            int idx = level.getRandom().nextInt(state.options.size());
            winnerId = state.options.get(idx).id();
        } else {
            int maxVotes = tallies.values().stream().max(Integer::compareTo).orElse(0);
            List<String> top = new ArrayList<>();
            for (var e : tallies.entrySet()) {
                if (e.getValue() == maxVotes) {
                    top.add(e.getKey());
                }
            }
            if (top.size() == 1) {
                winnerId = top.get(0);
            } else {
                randomPick = true;
                winnerId = top.get(level.getRandom().nextInt(top.size()));
            }
        }

        VoteResult result = new VoteResult(state.voteId, winnerId, tallies, randomPick);
        state.resolvedOptionId = winnerId == null ? "" : winnerId;
        Consumer<VoteResult> callback = state.onResolved;
        state.onResolved = null;

        LOGGER.info("[OptionVote] resolved voteId={} winner={} randomPick={} totalVotes={}",
                result.voteId(), winnerId, randomPick, totalVotes);

        broadcastState(level);

        if (callback != null) {
            try {
                callback.accept(result);
            } catch (Exception e) {
                LOGGER.error("[OptionVote] onResolved threw for voteId={}", result.voteId(), e);
            }
        }
    }

    /** 投票者离线/移除：删除其选票并 rebroadcast。 */
    public static void onVoterRemoved(ServerLevel level, UUID voterId) {
        State state = STATES.get(level.dimension());
        if (state == null || voterId == null) return;
        if (state.votesByVoter.remove(voterId) != null && state.active) {
            markChanged(state);
            broadcastState(level);
        }
    }

    /** 取消当前投票：不调用 onResolved，广播 close。 */
    public static void cancel(ServerLevel level) {
        State state = STATES.get(level.dimension());
        if (state == null || !state.active) return;
        state.active = false;
        state.resolvedOptionId = "";
        state.onResolved = null;
        state.votesByVoter.clear();
        markChanged(state);
        broadcastState(level);
        LOGGER.info("[OptionVote] cancelled voteId={}", state.voteId);
    }

    /** 对局/维度清理：移除 state。 */
    public static void reset(ServerLevel level) {
        STATES.remove(level.dimension());
    }

    public static boolean isActive(ServerLevel level) {
        State state = STATES.get(level.dimension());
        return state != null && state.active;
    }

    public static int remainingSeconds(ServerLevel level) {
        if (level == null) return 0;
        State state = STATES.get(level.dimension());
        return state != null && state.active ? Math.max(0, state.remainingSeconds) : 0;
    }

    /**
     * 地图投票开始后推送档案（一次性，不随 1Hz 票数广播重复推）。
     * 仅当前 active 投票为地图阶段时生效。
     */
    public static void pushProfiles(ServerLevel level,
                                    Map<String, MapVoteProfilePayload.MapProfile> profiles) {
        if (level == null || profiles == null) return;
        State state = STATES.get(level.dimension());
        if (state == null || !state.active || !"map".equals(state.voteId)) return;
        List<MapVoteProfilePayload> fragments = new ArrayList<>();
        for (var entry : profiles.entrySet()) {
            fragments.add(new MapVoteProfilePayload(Map.of(entry.getKey(), entry.getValue())));
        }
        state.profilePayloads = List.copyOf(fragments);
        for (ServerPlayer player : level.players()) {
            if (RepairModeManager.isRepairer(player)) continue;
            sendProfileFragments(player, state.profilePayloads);
        }
    }

    private static void sendProfileFragments(ServerPlayer player,
                                             List<MapVoteProfilePayload> fragments) {
        for (MapVoteProfilePayload fragment : fragments) {
            ServerPlayNetworking.send(player, fragment);
        }
    }

    /** 玩家加入时同步当前 active 投票状态。 */
    public static void syncTo(ServerPlayer player) {
        if (player == null) return;
        // 维修人员不进入对局，不收到大厅投票 GUI
        if (RepairModeManager.isRepairer(player)) return;
        ServerLevel level = player.serverLevel();
        State state = STATES.get(level.dimension());
        if (state == null || !state.active) return;
        List<OptionVotePayload.Entry> entries = buildEntries(state);
        OptionVotePayload.sendTo(
                player,
                state.voteId,
                state.active,
                state.remainingSeconds,
                state.totalSeconds,
                1,
                state.title,
                state.description,
                entries
        );
        // 地图阶段补发档案（中途加入的玩家）
        if ("map".equals(state.voteId) && !state.profilePayloads.isEmpty()) {
            sendProfileFragments(player, state.profilePayloads);
        }
    }

    private static void broadcastState(ServerLevel level) {
        State state = STATES.get(level.dimension());
        if (state == null) return;

        if (state.stateVersion == state.lastSentVersion) return;
        state.lastSentVersion = state.stateVersion;

        List<OptionVotePayload.Entry> entries = buildEntries(state);
        for (ServerPlayer player : level.players()) {
            // 维修人员不进入对局，不收到大厅投票 GUI
            if (RepairModeManager.isRepairer(player)) continue;
            OptionVotePayload.sendTo(
                    player,
                    state.voteId,
                    state.active,
                    state.remainingSeconds,
                    state.totalSeconds,
                    1,
                    state.title,
                    state.description,
                    state.resolvedOptionId,
                    entries
            );
        }
    }

    private static void markChanged(State state) {
        state.stateVersion++;
    }

    private static List<OptionVotePayload.Entry> buildEntries(State state) {
        Map<String, Integer> counts = new HashMap<>();
        for (VoteOption opt : state.options) {
            counts.put(opt.id(), 0);
        }
        for (String optionId : state.votesByVoter.values()) {
            counts.merge(optionId, 1, Integer::sum);
        }
        List<OptionVotePayload.Entry> entries = new ArrayList<>(state.options.size());
        for (VoteOption opt : state.options) {
            entries.add(new OptionVotePayload.Entry(
                    opt.id(), opt.displayName(), counts.getOrDefault(opt.id(), 0)));
        }
        return entries;
    }
}
