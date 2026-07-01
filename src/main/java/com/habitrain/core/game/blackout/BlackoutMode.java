package com.habitrain.core.game.blackout;

import com.habitrain.core.api.*;
import com.habitrain.core.network.BlackoutTimerPayload;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.habitrain.core.HabiTrainCore;
import java.util.List;

/**
 * 停电模式 GameMode 实现 — habitrains:blackout
 *
 * 基于 SRE 原版游戏运行 (地图重置、房间传送、角色分配由 SRE 处理)，
 * 叠加停电模式的计时器、投票、任务系统。
 */
public class BlackoutMode implements GameMode {

    public static final String MODE_ID = "habitrains:blackout";
    public static final String MODE_DISPLAY = "停电模式";

    public static final TaskCategory BLACKOUT_GOOD =
            new TaskCategory("habitrain:blackout_good", "好人任务", MODE_ID);
    public static final TaskCategory BLACKOUT_BAD =
            new TaskCategory("habitrain:blackout_bad", "坏人任务", MODE_ID);

    private ServerLevel currentLevel;
    private int tickAccumulator = 0;
    private boolean gameEnded = false;
    /** SRE 游戏是否已实际开始运行 (异步加载地图完成后) */
    private boolean sreGameRunning = false;

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return MODE_DISPLAY; }

    @Override
    public List<TaskCategory> getTaskCategories() {
        return List.of(BLACKOUT_GOOD, BLACKOUT_BAD);
    }

    @Override
    public boolean isActive(ServerLevel level) {
        return currentLevel != null && currentLevel.dimension().equals(level.dimension());
    }

    // ====== 生命周期 ======

    @Override
    public void onPreStart(ServerLevel level) {
        this.currentLevel = level;
        this.tickAccumulator = 0;
        this.gameEnded = false;
        this.sreGameRunning = false;

        BlackoutRoleManager.clear();
        BlackoutTimerSystem.init(level,
                this::triggerSREPermanentBlackout,
                this::endSREBlackout,
                this::sendTimeWarning
        );
        TACZWeaponBridge.resetPurchases();
    }

    @Override
    public void onStart(ServerLevel level) {
        TACZWeaponBridge.register();

        // 查找并启动 SRE 游戏 (异步加载地图、分配角色)
        ResourceLocation blackoutModeId = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
        var sreMode = SREGameModes.GAME_MODES.get(blackoutModeId);
        if (sreMode == null) {
            HabiTrainCore.LOGGER.error("SREBlackoutGameMode not found!");
            return;
        }
        var sreGame = SREGameWorldComponent.KEY.get(level);
        if (sreGame != null && !sreGame.isRunning()) {
            GameUtils.startGame(level, sreMode,
                    GameConstants.getInTicks(
                        ((io.wifi.starrailexpress.api.GameMode)sreMode).defaultStartTime, 0));
        }
    }

    @Override
    public void onTick(ServerLevel level) {
        if (level != currentLevel || gameEnded) return;

        var sreGame = SREGameWorldComponent.KEY.get(level);
        boolean sreActive = sreGame != null && sreGame.isRunning();

        // 状态转换: SRE 游戏开始
        if (sreActive && !sreGameRunning) {
            sreGameRunning = true;
        }

        // 状态转换: SRE 游戏结束 (如 tmm stop) → 结束 BlackoutMode
        if (!sreActive && sreGameRunning) {
            sreGameRunning = false;
            endGame("§6对局结束");
            return;
        }

        // 等待 SRE 启动完成
        if (!sreActive) return;

        // Timer only runs while SRE is active
        tickAccumulator++;
        if (tickAccumulator % 20 == 0) {
            BlackoutTimerSystem.tickSecond();
            checkVictory();

            // Broadcast time sync
            int totalTime = BlackoutTimerSystem.getTotalTimeRemaining();
            boolean permDark = BlackoutTimerSystem.isPermanentBlackoutActive();
            int maintTime = BlackoutTimerSystem.getMaintenanceTime();
            int cd = BlackoutTimerSystem.getBlackoutCountdown();
            BlackoutTimerPayload.broadcastToAll(level.getServer(),
                    totalTime,
                    permDark ? 0 : (maintTime > 0 ? maintTime : cd),
                    permDark || BlackoutTimerSystem.isTransientBlackoutActive(),
                    BlackoutTimerSystem.getPhase().ordinal());

            // Reapply permanent blackout
            if (tickAccumulator % 40 == 0 && BlackoutTimerSystem.isPermanentBlackoutActive()) {
                reapplyPermanentBlackout();
            }
        }
    }

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {
        checkVictory();
    }

    @Override
    public void onPlayerJoin(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(
            "§e当前游戏: 停电模式  剩余: §l" + formatTime(BlackoutTimerSystem.getTotalTimeRemaining()) + "§r"));
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        BlackoutRoleManager.eliminate(player.getUUID());
        checkVictory();
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        broadcast("§6对局结束！");
        // HUD 隐藏由客户端 BlackoutTimerPayload 接收器处理:
        // 当 totalTimeRemaining <= 0 时自动隐藏，无论广播是否触发。
        currentLevel = null;
    }

    @Override
    public void onCleanup(ServerLevel level) {
        BlackoutRoleManager.clear();
        BlackoutTimerSystem.reset();
        currentLevel = null;
        gameEnded = false;
        sreGameRunning = false;
    }

    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        // 只返回停电模式专属任务
        return tasks.stream()
                .filter(t -> {
                    TaskCategory cat = t.getCategory();
                    return BLACKOUT_GOOD.equals(cat) || BLACKOUT_BAD.equals(cat);
                })
                .toList();
    }

    // ====== 内部方法 ======

    private void triggerSREPermanentBlackout() {
        if (currentLevel == null) return;
        var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
        if (blackout != null) {
            blackout.triggerBlackout(true, 60000);
        }
    }

    private void endSREBlackout() {
        if (currentLevel == null) return;
        var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
        if (blackout != null) {
            blackout.reset();
        }
        broadcast("§a供电已恢复");
    }

    private void reapplyPermanentBlackout() {
        if (currentLevel == null) return;
        try {
            var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
            if (blackout != null) {
                // 简单的周期性重新触发（SRE API 允许重复触发）
                blackout.triggerBlackout(false, 60000);
                HabiTrainCore.LOGGER.debug("Re-applied permanent blackout via API (periodic push)");
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to reapply blackout", e);
        }
    }

    private void sendTimeWarning() {
        broadcast("§e仅剩 1 分钟！");
    }

    private void checkVictory() {
        if (BlackoutRoleManager.getRemainingGood() <= 0 && BlackoutRoleManager.getRemainingBad() <= 0) return;

        int goodRemaining = BlackoutRoleManager.getRemainingGood();
        int badRemaining = BlackoutRoleManager.getRemainingBad();

        // 好人胜: 时间归零
        if (BlackoutTimerSystem.isTimeUp()) {
            endGame(WinResult.noWinner("时间归零"), "§a好人阵营获胜！时间归零，好人成功存活！");
            return;
        }

        // 好人胜: 杀手全灭
        if (badRemaining <= 0 && goodRemaining > 0) {
            endGame(WinResult.noWinner("杀手全灭"), "§a好人阵营获胜！所有杀手已被消灭");
            return;
        }

        // 杀手胜: 好人全灭
        if (goodRemaining <= 0 && badRemaining > 0) {
            endGame(WinResult.noWinner("好人全灭"), "§c杀手阵营获胜！所有好人都被淘汰了");
            return;
        }
    }

    /**
     * 以指定 WinResult 结束游戏（含 clearRoleMap 和完整日志）。
     * 由 checkVictory() 调用，使用自然的胜利原因。
     */
    private void endGame(WinResult result, String message) {
        if (gameEnded) return;
        gameEnded = true;
        broadcast(message);
        if (currentLevel != null) {
            try {
                var sreGame = SREGameWorldComponent.KEY.get(currentLevel);
                if (sreGame != null) {
                    sreGame.setGameStatus(io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STOPPING);
                    sreGame.clearRoleMap();
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("endGame: failed to stop SRE game", e);
            }
            GameModeRegistry.stop(currentLevel, result);
        }
    }

    /**
     * 以默认 WinResult.forceEnd 结束游戏。
     * 仅供内部意外终止路径使用（例如 SRE 游戏提前结束）。
     */
    private void endGame(String message) {
        endGame(WinResult.forceEnd("游戏结束"), message);
    }

    private void broadcast(String message) {
        if (currentLevel == null) return;
        Component component = Component.literal(message);
        for (ServerPlayer player : currentLevel.players()) {
            player.sendSystemMessage(component);
        }
    }

    public static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
