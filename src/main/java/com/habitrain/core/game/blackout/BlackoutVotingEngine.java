package com.habitrain.core.game.blackout;

import com.habitrain.core.network.BlackoutStatusPayload;
import com.habitrain.core.network.BlackoutStatusPayload.StatusType;
import com.habitrain.core.network.BlackoutVotePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 停电模式 — 投票选举警长引擎。
 *
 * 流程:
 * 1. 开局60s后 → openVoting() → 广播 + 设置30s窗口
 * 2. 玩家按P打开VoteScreen → 选人 → BlackoutVotePayload C2S
 * 3. 30s后 → resolveVoting() → 计票 → 公告结果 / 补选
 */
public class BlackoutVotingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutVote");

    private static boolean voteWindowOpen = false;
    private static int windowElapsedTicks = 0;
    private static final int WINDOW_DURATION_TICKS = 600; // 600 tick = 30s（在 onTick 每秒调一次）
    private static boolean votingResolved = false;
    private static final Map<UUID, UUID> VOTES = new HashMap<>(); // voter → target
    private static MinecraftServer server = null;

    public static void init(MinecraftServer srv) {
        server = srv;
        voteWindowOpen = false;
        windowElapsedTicks = 0;
        votingResolved = false;
        VOTES.clear();
    }

    /** 开启投票窗口 (由 BlackoutMode.onTick 在60s时调用) */
    public static void openVoting() {
        voteWindowOpen = true;
        windowElapsedTicks = 0;
        votingResolved = false;
        VOTES.clear();
        broadcast("§e【投票】现在可以投出你觉得的「警长」！按 [P] 键打开投票界面");
        broadcast("§7投票将在 30 秒后截止");
        LOGGER.info("Voting window opened for 30s, server={}", server != null ? "available" : "null");
        if (server != null) {
            BlackoutStatusPayload.broadcast(server, StatusType.VOTE_OPEN, "");
        }
    }

    /** 每秒调用 (由 BlackoutMode.onTick 驱动) */
    public static void tickVoting() {
        if (!voteWindowOpen || votingResolved || server == null) return;

        windowElapsedTicks++;  // +1 tick（caller 保证每秒调一次，但实际累加 tick）
        int remainingTicks = WINDOW_DURATION_TICKS - windowElapsedTicks;

        // 每10秒提醒一次
        if (remainingTicks > 0 && remainingTicks % 200 == 0) {
            broadcast("§7投票剩余: " + (remainingTicks / 20) + " 秒");
        }

        if (windowElapsedTicks >= WINDOW_DURATION_TICKS) {
            resolveVoting();
        }
    }

    /** 处理玩家投票 (由 C2S 接收器调用) */
    public static boolean castVote(UUID voterId, UUID targetId) {
        if (!voteWindowOpen || votingResolved) return false;
        if (!BlackoutRoleManager.isAlive(voterId)) return false;
        if (!BlackoutRoleManager.isAlive(targetId)) return false;
        if (voterId.equals(targetId)) return false;

        VOTES.put(voterId, targetId);
        LOGGER.info("Vote cast: {} → {}", voterId, targetId);
        return true;
    }

    /** 结算投票 */
    private static void resolveVoting() {
        votingResolved = true;
        voteWindowOpen = false;

        Map<UUID, Integer> tally = new HashMap<>();
        for (UUID target : VOTES.values()) {
            tally.merge(target, 1, Integer::sum);
        }

        UUID winner = null;
        int maxVotes = 0;

        for (var entry : tally.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                winner = entry.getKey();
            }
        }

        // 检查平票
        if (winner != null) {
            final UUID finalWinner = winner;
            int finalMax = maxVotes;
            boolean tie = tally.entrySet().stream()
                    .filter(e -> !e.getKey().equals(finalWinner))
                    .anyMatch(e -> e.getValue() == finalMax);
            if (tie) winner = null;
        }

        // 无人当选/平票 → 系统选非杀手
        if (winner == null) {
            List<UUID> eligible = BlackoutRoleManager.getAllAlive().stream()
                    .filter(BlackoutRoleManager::canBeSheriff)
                    .toList();
            if (!eligible.isEmpty()) {
                winner = eligible.get(new Random().nextInt(eligible.size()));
            }
        }

        if (winner != null) {
            String playerName = "";
            if (server != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(winner);
                if (p != null) playerName = p.getName().getString();
            }
            BlackoutRoleManager.setSheriff(winner);
            broadcast("§e【投票】" + playerName + " 当选为警长！");
            LOGGER.info("Sheriff elected: {} (votes: {})", winner, maxVotes);
        } else {
            broadcast("§e【投票】无人可当选警长...警长位置空缺");
        }
    }

    public static boolean isVoteWindowOpen() { return voteWindowOpen; }

    public static void reset() {
        voteWindowOpen = false;
        windowElapsedTicks = 0;
        votingResolved = false;
        VOTES.clear();
        server = null;
    }

    private static void broadcast(String msg) {
        if (server == null) return;
        Component c = Component.literal(msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(c);
        }
    }
}
