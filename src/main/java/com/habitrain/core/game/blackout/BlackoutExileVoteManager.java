package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.network.BlackoutVotePayload;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 放逐投票管理器。
 *
 * 按 dimension 隔离，每次最多一个 active 投票。
 * 候选人 = 当前对局内存活玩家（含发起者）。
 * 每人 1 票，不可改票。
 */
public final class BlackoutExileVoteManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutExileVoteManager");
    private static final int VOTE_DURATION_SECONDS = 15;
    private static final ResourceLocation EXILE_DEATH_REASON =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "exile_vote");

    private static final ConcurrentMap<ResourceKey<Level>, VoteState> STATES = new ConcurrentHashMap<>();

    private BlackoutExileVoteManager() {}

    private static final class VoteState {
        boolean active = false;
        int remainingSeconds = 0;
        final List<UUID> candidateOrder = new ArrayList<>();
        final Map<UUID, UUID> votesByVoter = new HashMap<>(); // voter -> target
        UUID initiatorId = null;
    }

    private static VoteState getOrCreate(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new VoteState());
    }

    /** 对局重置/清理 */
    public static void reset(ServerLevel level) {
        STATES.remove(level.dimension());
    }

    /** 当前是否有 active 的放逐投票 */
    public static boolean isVoteActive(ServerLevel level) {
        VoteState state = STATES.get(level.dimension());
        return state != null && state.active;
    }

    /**
     * 发起放逐投票。
     * 调用前需校验：金钱、对局状态、无 active 投票。
     */
    public static void startVote(ServerLevel level, ServerPlayer initiator) {
        VoteState state = getOrCreate(level);
        state.active = true;
        state.remainingSeconds = VOTE_DURATION_SECONDS;
        state.votesByVoter.clear();
        state.candidateOrder.clear();
        state.initiatorId = initiator.getUUID();

        // 候选人 = 所有存活玩家
        for (UUID id : BlackoutRoleManager.getAllAlive(level)) {
            state.candidateOrder.add(id);
        }

        if (state.candidateOrder.isEmpty()) {
            state.active = false;
            broadcastResult(level, "§e当前没有存活玩家，无法发起放逐投票。");
            return;
        }

        LOGGER.info("[ExileVote] {} started exile vote with {} candidates",
                initiator.getName().getString(), state.candidateOrder.size());

        broadcastState(level);
        broadcastResult(level, "§e放逐投票已开启！按 V 键打开投票页面。");
    }

    /** 每 tick 处理（每秒调用一次） */
    public static void tickSecond(ServerLevel level) {
        VoteState state = STATES.get(level.dimension());
        if (state == null || !state.active) return;

        state.remainingSeconds--;
        if (state.remainingSeconds <= 0) {
            resolve(level, state);
        } else {
            broadcastState(level);
        }
    }

    /** 投票 */
    public static void castVote(ServerLevel level, UUID voterId, UUID targetId) {
        VoteState state = STATES.get(level.dimension());
        if (state == null || !state.active) return;

        // 校验投票者存活且在候选人中
        if (!state.candidateOrder.contains(voterId)) return;
        if (!BlackoutRoleManager.isAlive(level, voterId)) return;

        if (targetId != null && !state.candidateOrder.contains(targetId)) return;

        if (targetId != null) {
            state.votesByVoter.put(voterId, targetId);
        } else {
            state.votesByVoter.remove(voterId);
        }

        broadcastState(level);
    }

    /** 结算 */
    private static void resolve(ServerLevel level, VoteState state) {
        state.active = false;

        // 统计票数
        Map<UUID, Integer> voteCounts = new HashMap<>();
        for (UUID candidateId : state.candidateOrder) {
            voteCounts.put(candidateId, 0);
        }
        for (Map.Entry<UUID, UUID> entry : state.votesByVoter.entrySet()) {
            UUID target = entry.getValue();
            voteCounts.merge(target, 1, Integer::sum);
        }

        int totalVotes = state.votesByVoter.size();

        if (totalVotes == 0) {
            broadcastResult(level, "§e无人投票，本轮无人被放逐");
            return;
        }

        // 找最高票
        int maxVotes = voteCounts.values().stream().max(Integer::compareTo).orElse(0);
        List<UUID> topCandidates = new ArrayList<>();
        for (var entry : voteCounts.entrySet()) {
            if (entry.getValue() == maxVotes) {
                topCandidates.add(entry.getKey());
            }
        }

        UUID exiledId;
        if (topCandidates.size() == 1) {
            exiledId = topCandidates.get(0);
        } else {
            // 平票随机
            Random random = new Random(level.getRandom().nextLong());
            exiledId = topCandidates.get(random.nextInt(topCandidates.size()));
        }

        ServerPlayer exiled = level.getServer().getPlayerList().getPlayer(exiledId);
        String exiledName = exiled != null ? exiled.getName().getString() : exiledId.toString();

        // 执行放逐（玩家可能已离线，killPlayer 需要非空）
        if (exiled != null) {
            GameUtils.killPlayer(exiled, true, null, EXILE_DEATH_REASON);
        }
        BlackoutRoleManager.eliminate(level, exiledId);

        broadcastResult(level, "§e投票结束，§b" + exiledName + " §e被放逐");

        LOGGER.info("[ExileVote] {} exiled ({}/{} votes)", exiledName, maxVotes, totalVotes);

        // 通知胜负检查
        var mode = findBlackoutMode(level);
        if (mode != null) {
            mode.setPendingEndMessage("放逐后胜负条件变化");
        }
    }

    /** 广播投票状态到所有客户端 */
    private static void broadcastState(ServerLevel level) {
        VoteState state = STATES.get(level.dimension());
        if (state == null) return;

        List<BlackoutVotePayload.Entry> entries = buildEntryList(level, state);
        BlackoutVotePayload.broadcastToAll(
                level.getServer(),
                "EXILE",
                state.active,
                state.remainingSeconds,
                VOTE_DURATION_SECONDS,
                1,
                "放逐投票",
                "选择一名玩家放逐",
                entries
        );
    }

    private static List<BlackoutVotePayload.Entry> buildEntryList(ServerLevel level, VoteState state) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (UUID candidateId : state.candidateOrder) {
            if (BlackoutRoleManager.isAlive(level, candidateId)) {
                counts.put(candidateId, 0);
            }
        }
        for (UUID target : state.votesByVoter.values()) {
            counts.merge(target, 1, Integer::sum);
        }

        Map<UUID, String> nameCache = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            nameCache.put(player.getUUID(), player.getName().getString());
        }

        List<BlackoutVotePayload.Entry> entries = new ArrayList<>();
        for (UUID candidateId : state.candidateOrder) {
            if (!counts.containsKey(candidateId)) continue;
            String name = nameCache.getOrDefault(candidateId, candidateId.toString());
            entries.add(new BlackoutVotePayload.Entry(candidateId, name, counts.get(candidateId)));
        }
        return entries;
    }

    /** 全图顶部提示 */
    private static void broadcastResult(ServerLevel level, String message) {
        Component component = Component.literal(message);
        for (ServerPlayer player : level.players()) {
            SubtitleNotifier.sendTop(player, Component.empty(), component, 80);
        }
    }

    /** 从 GameModeRegistry 查找当前 BlackoutMode */
    private static com.habitrain.core.game.blackout.BlackoutMode findBlackoutMode(ServerLevel level) {
        var mode = com.habitrain.core.api.GameModeRegistry.getActiveForLevel(level);
        if (mode.isPresent() && mode.get() instanceof com.habitrain.core.game.blackout.BlackoutMode bm) {
            return bm;
        }
        return null;
    }
}
