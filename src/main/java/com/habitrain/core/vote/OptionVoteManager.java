package com.habitrain.core.vote;

import com.habitrain.core.api.VoteOption;
import com.habitrain.core.api.VoteResult;
import com.habitrain.core.game.sre.RepairModeManager;
import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.network.OptionVotePayload;
import io.wifi.starrailexpress.cca.ParticipationComponent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * 通用选项投票管理器（模式/地图等字符串选项，非玩家 UUID）。
 * 按 dimension 隔离，每次最多一个 active 投票。
 * 每人 1 票，允许改票与弃票（optionId null）。
 */
public final class OptionVoteManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("OptionVoteManager");

    private static final ConcurrentMap<ResourceKey<Level>, State> STATES = new ConcurrentHashMap<>();

    static {
        com.habitrain.core.task.ClearableHandlerRegistry.register(OptionVoteManager::resetAll);
    }

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

        // Only online, participating, non-repairer players in this dimension may cast.
        ServerPlayer voter = level.getServer() != null
                ? level.getServer().getPlayerList().getPlayer(voterId) : null;
        if (voter == null || voter.serverLevel() != level) return false;
        if (RepairModeManager.isRepairer(voter)) return false;
        if (voter.isSpectator()) return false;
        if (!isParticipatingVoter(level, voterId)) return false;

        if (optionId != null) {
            boolean known = false;
            for (VoteOption opt : state.options) {
                if (opt.id().equals(optionId)) {
                    known = true;
                    break;
                }
            }
            if (!known) return false;
            // 地图阶段：维修锁在 beginMapVote 快照之后仍可能加上，开票后拒投被锁图。
            if (isMapVoteId(state.voteId) && RepairModeManager.isMapLocked(optionId)) {
                return false;
            }
            // 重复投同一选项：no-op，不 bump 版本、不广播（review M18——
            // 恶意刷包时每包不应产生全维度全量广播）。
            if (optionId.equals(state.votesByVoter.get(voterId))) {
                return true;
            }
            state.votesByVoter.put(voterId, optionId);
        } else {
            if (!state.votesByVoter.containsKey(voterId)) {
                return true;
            }
            state.votesByVoter.remove(voterId);
        }
        markChanged(state);
        // 不在 cast 内立即广播：tickSecond 的 1Hz 全量重播兜底（最多 1 秒
        // 延迟），避免每票 × 全维度玩家的出站放大（review M18）。
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
        Predicate<String> blocked = isMapVoteId(state.voteId)
                ? RepairModeManager::isMapLocked
                : id -> false;
        WinnerPick pick = pickWinner(
                state.options,
                state.votesByVoter,
                blocked,
                bound -> level.getRandom().nextInt(bound));
        boolean randomPick = pick.randomPick();
        String winnerId = pick.winnerId();

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

    /** Lobby map-phase vote id used by {@link ModeMapVoteOrchestrator}. */
    static boolean isMapVoteId(@Nullable String voteId) {
        return "map".equals(voteId);
    }

    /**
     * Pick a winner among non-blocked options. Votes for blocked/unknown ids are ignored.
     * All blocked (or empty) → {@code winnerId == null} rather than starting a locked map.
     */
    static WinnerPick pickWinner(List<VoteOption> options,
                                 Map<UUID, String> votesByVoter,
                                 @Nullable Predicate<String> optionBlocked,
                                 @Nullable IntUnaryOperator randomIndex) {
        List<VoteOption> eligible = new ArrayList<>();
        if (options != null) {
            for (VoteOption opt : options) {
                if (opt == null || opt.id() == null || opt.id().isBlank()) {
                    continue;
                }
                if (optionBlocked != null && optionBlocked.test(opt.id())) {
                    continue;
                }
                eligible.add(opt);
            }
        }
        if (eligible.isEmpty()) {
            return new WinnerPick(null, false);
        }

        Map<String, Integer> tallies = new HashMap<>();
        for (VoteOption opt : eligible) {
            tallies.put(opt.id(), 0);
        }
        int countedVotes = 0;
        if (votesByVoter != null) {
            for (String optionId : votesByVoter.values()) {
                if (optionId != null && tallies.containsKey(optionId)) {
                    tallies.merge(optionId, 1, Integer::sum);
                    countedVotes++;
                }
            }
        }

        if (countedVotes == 0) {
            return new WinnerPick(eligible.get(indexInRange(randomIndex, eligible.size())).id(), true);
        }

        int maxVotes = 0;
        for (int count : tallies.values()) {
            if (count > maxVotes) {
                maxVotes = count;
            }
        }
        List<String> top = new ArrayList<>();
        for (var e : tallies.entrySet()) {
            if (e.getValue() == maxVotes) {
                top.add(e.getKey());
            }
        }
        if (top.size() == 1) {
            return new WinnerPick(top.get(0), false);
        }
        return new WinnerPick(top.get(indexInRange(randomIndex, top.size())), true);
    }

    private static int indexInRange(@Nullable IntUnaryOperator randomIndex, int size) {
        if (size <= 0) {
            return 0;
        }
        int idx = randomIndex == null ? 0 : randomIndex.applyAsInt(size);
        if (idx < 0 || idx >= size) {
            return 0;
        }
        return idx;
    }

    record WinnerPick(@Nullable String winnerId, boolean randomPick) {}

    private static boolean isParticipatingVoter(ServerLevel level, UUID voterId) {
        try {
            ParticipationComponent participation = ParticipationComponent.KEY.get(level);
            return participation != null && participation.isParticipating(voterId);
        } catch (Throwable t) {
            // SRE 组件不可用时不额外拦票（默认参与）。
            return true;
        }
    }

    /**
     * Disconnect keeps the ballot while a vote is active so reconnect restores
     * this round's vote. Explicit leave-participation still uses
     * {@link #onVoterRemoved}. Rebroadcasts remaining players' UI.
     */
    public static void onVoterDisconnected(ServerLevel level, UUID voterId) {
        applyVoterExit(level, voterId, false);
    }

    /** 明确退出投票（维修模式等）：删除其选票并 rebroadcast。 */
    public static void onVoterRemoved(ServerLevel level, UUID voterId) {
        applyVoterExit(level, voterId, true);
    }

    /**
     * Current level if it has an active vote; otherwise the first other loaded
     * level with an active vote (rest/reconnect may not be in the vote world).
     */
    public static @Nullable ServerLevel resolveActiveVoteLevel(@Nullable ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return resolveActiveVoteLevel(player.getServer(), player.serverLevel());
    }

    static @Nullable ServerLevel resolveActiveVoteLevel(
            @Nullable MinecraftServer server, @Nullable ServerLevel preferred) {
        if (preferred != null && isActive(preferred)) {
            return preferred;
        }
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level != preferred && isActive(level)) {
                return level;
            }
        }
        return null;
    }

    private static void applyVoterExit(ServerLevel level, UUID voterId, boolean explicitLeave) {
        if (level == null || voterId == null) {
            return;
        }
        State state = STATES.get(level.dimension());
        if (state == null) {
            return;
        }
        if (!dropBallotOnVoterExit(state.active, explicitLeave)) {
            if (state.active && state.votesByVoter.containsKey(voterId)) {
                markChanged(state);
                broadcastState(level);
            }
            return;
        }
        if (state.votesByVoter.remove(voterId) != null && state.active) {
            markChanged(state);
            broadcastState(level);
        }
    }

    /** Disconnect keeps an active ballot; explicit leave always drops it. */
    static boolean dropBallotOnVoterExit(boolean voteActive, boolean explicitLeaveParticipation) {
        return explicitLeaveParticipation || !voteActive;
    }

    /**
     * 地图投票进行中时从候选列表拿掉一张已锁地图，并删除投给它的票。
     * 若删空则立即结算（winner null），让编排器取消开局而不是卡在 MAP_VOTING。
     */
    public static void invalidateMapOption(ServerLevel level, String optionId) {
        if (level == null || optionId == null || optionId.isBlank()) return;
        State state = STATES.get(level.dimension());
        if (state == null || !state.active || !isMapVoteId(state.voteId)) return;
        boolean removed = state.options.removeIf(opt -> optionId.equals(opt.id()));
        if (!removed) return;
        state.votesByVoter.entrySet().removeIf(e -> optionId.equals(e.getValue()));
        markChanged(state);
        if (state.options.isEmpty()) {
            resolve(level, state);
            return;
        }
        broadcastState(level);
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
        if (level == null) return;
        STATES.remove(level.dimension());
    }

    /** 全局/服务器关闭/对局结束清理：移除所有维度的投票 state。 */
    public static void resetAll() {
        STATES.clear();
    }

    /** 全局取消所有维度的活动投票。 */
    public static void cancelAll() {
        for (State state : STATES.values()) {
            state.active = false;
            state.resolvedOptionId = "";
            state.onResolved = null;
            state.votesByVoter.clear();
        }
        STATES.clear();
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
        ServerLevel level = resolveActiveVoteLevel(player);
        if (level == null) return;
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
