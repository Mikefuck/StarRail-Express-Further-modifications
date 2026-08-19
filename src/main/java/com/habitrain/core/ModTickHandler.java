package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelTickEngine;
import com.habitrain.core.game.sre.EnvironmentController;
import com.habitrain.core.game.sre.SREGameModeBase;
import com.habitrain.core.game.sre.SREWeatherController;
import com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager;
import com.habitrain.core.task.GameLifecycleHandler;
import com.habitrain.core.vote.OptionVoteManager;
import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class ModTickHandler {
    private static int voteTickCounter = 0;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 大厅阶段周期性补拉未进 LobbyChat 的玩家（必须在 processPending 前，以便本 tick 即可尝试）
            SREGameModeBase.reconcileLobbyGroupMembership(server);
            SREGameModeBase.processPendingVoiceJoins(server);
            SREGameModeBase.processGameEndGroupJoin(server);
            GameModeRegistry.tickAll(server);
            // 天气状态需先经过世界 tick 同步到客户端，再发送开局/结算动画的环境就绪阶段。
            com.habitrain.core.game.sre.MapVoteLoadCoordinator.tick(server);
            com.habitrain.core.game.sre.GameEndTransitionCoordinator.tick(server);

            // 1Hz option-vote tick (mode/map lobby vote countdown)
            voteTickCounter++;
            if (voteTickCounter % 20 == 0) {
                for (ServerLevel level : server.getAllLevels()) {
                    OptionVoteManager.tickSecond(level);
                }
                // 1Hz 开局加载进度广播（投票→地图重置→真正开局窗口内）
                com.habitrain.core.game.sre.MapVoteLoadCoordinator.tickSecond(server);
                // 维修人员模式：异常兜底，释放「离线但仍持锁」的记录（覆盖崩溃/异常断线）
                com.habitrain.core.game.sre.RepairModeManager.checkAbnormal(server);
            }

            // 5s (100 ticks): 自动检测服务端地图定义与档案文件修改，若修改则自动重载并全量同步给客户端
            if (voteTickCounter % 100 == 0) {
                com.habitrain.core.vote.MapFileMonitor.checkAndSync(server);
            }

            // Greed anonymous trade session timeouts
            try {
                GreedTradeManager.tick(server);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[ModTick] GreedTradeManager.tick failed", t);
            }

            tickMoreMods(server);
        });
    }

    public static void tickMoreMods(MinecraftServer server) {
        // Apply MODIFY flags/spawnInfo patches on each tick
        try {
            com.habitrain.core.role.override.RoleOverrideTickApplier.tick(server);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[ModTick] RoleOverrideTickApplier.tick failed", t);
        }

        boolean isGameActive = false;
        for (ServerLevel world : server.getAllLevels()) {
            BetelLeafHandler.tickHarvests(world);
            if (BetelTickEngine.isGameActive(world)) {
                isGameActive = true;
            }
        }
        GameLifecycleHandler.tickGameEndCheck(isGameActive, server);

        // 人数不足阈值下雨 + 环境 profile 维持（主世界）
        for (ServerLevel world : server.getAllLevels()) {
            if (world.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                SREWeatherController.tick(world);
                EnvironmentController.tick(world);
            }
        }

        if (!isGameActive) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                BetelTickEngine.tickPlayer(player);
                ExtraSlotComponent.KEY.get(player).serverTick();
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[ModTick] per-player tick failed for {}", player.getName().getString(), t);
            }
        }
    }
}
