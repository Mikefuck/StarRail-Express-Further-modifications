package com.habitrain.core.game.blackout;

import com.habitrain.core.api.*;
import com.habitrain.core.client.gui.BlackoutHudOverlay;
import com.habitrain.core.network.BlackoutTimerPayload;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.api.SREGameModes;
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
    private boolean votingPhasePassed = false;
    private boolean gameEnded = false;
    /** SRE 游戏已实际开始 (角色已分配、地图已加载) */
    private boolean sreGameRunning = false;
    /** 已尝试启动 SRE 游戏 */
    private boolean sreStartAttempted = false;
    /** 已强制激活 SRE 游戏 (单人/少人绕过 minPlayerCount) */
    private boolean sreForceActivated = false;
    /** 等待 SRE 游戏启动的 tick 数 */
    private int sreStartWaitTicks = 0;

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
        this.votingPhasePassed = false;
        this.gameEnded = false;
        this.sreGameRunning = false;
        this.sreStartAttempted = false;
        this.sreForceActivated = false;
        this.sreStartWaitTicks = 0;

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
        // 注册 TACZ 子弹监听
        TACZWeaponBridge.register();

        // 查找 companion mod 注册的 SREBlackoutGameMode
        ResourceLocation blackoutModeId = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
        var sreMode = SREGameModes.GAME_MODES.get(blackoutModeId);
        if (sreMode == null) {
            HabiTrainCore.LOGGER.error("SREBlackoutGameMode not found! Did core mod registration fail?");
            return;
        }

        // 启动 SRE 原版游戏 (地图重置、房间传送等)
        var sreGame = SREGameWorldComponent.KEY.get(level);
        if (sreGame != null && !sreGame.isRunning()) {
            sreStartAttempted = true;
            sreStartWaitTicks = 0;
            GameUtils.startGame(level, sreMode,
                    GameConstants.getInTicks(
                        ((io.wifi.starrailexpress.api.GameMode)sreMode).defaultStartTime, 0));
        }
    }

    @Override
    public void onTick(ServerLevel level) {
        if (level != currentLevel || gameEnded) return;

        // 检测 SRE 游戏状态变化
        var sreGame = SREGameWorldComponent.KEY.get(level);
        boolean sreActive = sreGame != null && sreGame.isRunning();

        // 等待 SRE 游戏启动: startGame 异步加载地图, 完成后设置 gameStatus
        // 但单人/少人时 minPlayerCount 不满足, 需要强制激活
        if (!sreActive && sreStartAttempted && !sreGameRunning) {
            sreStartWaitTicks++;
            if (sreGame != null && sreGame.getGameStatus() == io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.INACTIVE
                    && sreStartWaitTicks > 40) { // 等待 ~2 秒让地图加载
                if (!sreForceActivated) {
                    // 强制激活 SRE 游戏 (绕过 minPlayerCount 检查)
                    sreGame.setGameStatus(io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.ACTIVE);
                    sreForceActivated = true;
                    HabiTrainCore.LOGGER.info("BlackoutMode: Forced SRE game to ACTIVE");
                }
            }
            if (sreGame != null && sreGame.isRunning()) {
                sreActive = true;
            }
        }

        if (sreActive && !sreGameRunning) {
            sreGameRunning = true;
            sreStartAttempted = false;

            // 通知 HUD 显示
            try {
                var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
                cls.getMethod("setVisible", boolean.class).invoke(null, true);
            } catch (Exception ignored) {}
        }

        if (!sreActive && sreGameRunning) {
            sreGameRunning = false;
            endGame("§6对局结束");
            return;
        }

        // 仅当 SRE 游戏运行中才执行停电模式的计时
        if (!sreActive) return;

        tickAccumulator++;

        // 每 20 tick (~1秒) 更新
        if (tickAccumulator % 20 == 0) {
            BlackoutTimerSystem.tickSecond();

            // 投票阶段检查 (60s后)
            int totalRemaining = BlackoutTimerSystem.getTotalTimeRemaining();
            int elapsed = 300 - totalRemaining;
            if (!votingPhasePassed && elapsed >= 60) {
                votingPhasePassed = true;
                BlackoutVotingEngine.init(level.getServer());
                BlackoutVotingEngine.openVoting();
            }

            // tick voting engine
            BlackoutVotingEngine.tickVoting();

            // 检查胜利条件
            checkVictory();

            // 广播时间同步
            int totalTime = BlackoutTimerSystem.getTotalTimeRemaining();
            boolean permDark = BlackoutTimerSystem.isPermanentBlackoutActive();
            int maintTime = BlackoutTimerSystem.getMaintenanceTime();
            int cd = BlackoutTimerSystem.getBlackoutCountdown();
            BlackoutTimerPayload.broadcastToAll(level.getServer(),
                    totalTime,
                    permDark ? 0 : (maintTime > 0 ? maintTime : cd),
                    permDark || BlackoutTimerSystem.isTransientBlackoutActive(),
                    BlackoutTimerSystem.getPhase().ordinal());

            // 每 40 tick (2秒) 保持永久停电状态
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
        try {
            var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
            cls.getMethod("setVisible", boolean.class).invoke(null, false);
        } catch (Exception ignored) {}
        currentLevel = null;
    }

    @Override
    public void onCleanup(ServerLevel level) {
        BlackoutRoleManager.clear();
        BlackoutTimerSystem.reset();
        BlackoutVotingEngine.reset();
        currentLevel = null;
        gameEnded = false;
        sreGameRunning = false;
        try {
            var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
            cls.getMethod("setVisible", boolean.class).invoke(null, false);
        } catch (Exception ignored) {}
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
        var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
        if (blackout != null) {
            try {
                var field = blackout.getClass().getDeclaredField("blackouts");
                field.setAccessible(true);
                java.util.List<?> list = (java.util.List<?>) field.get(blackout);
                if (list.isEmpty()) {
                    blackout.triggerBlackout(false, 60000);
                    HabiTrainCore.LOGGER.debug("Re-applied permanent blackout");
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("Failed to reapply blackout", e);
            }
        }
    }

    private void sendTimeWarning() {
        broadcast("§e仅剩 1 分钟！");
    }

    private void checkVictory() {
        if (!sreGameRunning) return;
        if (BlackoutRoleManager.getRemainingGood() <= 0 && BlackoutRoleManager.getRemainingBad() <= 0) return;

        int goodRemaining = BlackoutRoleManager.getRemainingGood();
        int badRemaining = BlackoutRoleManager.getRemainingBad();

        // 好人胜: 时间归零
        if (BlackoutTimerSystem.isTimeUp()) {
            endGame("§a好人阵营获胜！时间归零，好人成功存活！");
            return;
        }

        // 好人胜: 杀手全灭
        if (badRemaining <= 0 && goodRemaining > 0) {
            endGame("§a好人阵营获胜！所有杀手已被消灭");
            return;
        }

        // 杀手胜: 好人全灭
        if (goodRemaining <= 0 && badRemaining > 0) {
            endGame("§c杀手阵营获胜！所有好人都被淘汰了");
            return;
        }
    }

    private void endGame(String message) {
        if (gameEnded) return;
        gameEnded = true;
        sreGameRunning = false;
        broadcast(message);
        if (currentLevel != null) {
            // 也停止 SRE 游戏
            try {
                var sreGame = SREGameWorldComponent.KEY.get(currentLevel);
                if (sreGame != null) {
                    sreGame.setGameStatus(io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STOPPING);
                }
            } catch (Exception ignored) {}
            GameModeRegistry.stop(currentLevel);
        }
    }

    /**
     * 强制终止当前游戏（由 /habi_api stop 或管理员命令触发）。
     * 完整走一遍 SRE 停服 + GameModeRegistry.stop 流程。
     */
    public void forceEndGame(WinResult result, String message) {
        if (gameEnded) return;
        gameEnded = true;
        sreGameRunning = false;

        if (currentLevel != null) {
            broadcast(message);

            try {
                var sreGame = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(currentLevel);
                if (sreGame != null) {
                    sreGame.setGameStatus(
                        io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STOPPING);
                    sreGame.clearRoleMap();
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("forceEndGame: failed to stop SRE game", e);
            }

            GameModeRegistry.stop(currentLevel, result);
        }
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
