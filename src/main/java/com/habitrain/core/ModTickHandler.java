package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelTickEngine;
import com.habitrain.core.game.sre.EnvironmentController;
import com.habitrain.core.game.sre.GameEndTransitionCoordinator;
import com.habitrain.core.game.sre.SREGameModeBase;
import com.habitrain.core.game.sre.SREWeatherController;
import com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager;
import com.habitrain.core.task.GameLifecycleHandler;
import com.habitrain.core.vote.MapPoolRotationService;
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

            // 1Hz option-vote tick (mode/map lobby vote countdown) + map pool calendar
            voteTickCounter++;
            if (voteTickCounter % 20 == 0) {
                for (ServerLevel level : server.getAllLevels()) {
                    OptionVoteManager.tickSecond(level);
                }
                try {
                    MapPoolRotationService.onCalendarTick(server);
                } catch (Throwable t) {
                    // never break server tick loop
                    HabiTrainCore.LOGGER.warn("[ModTick] MapPoolRotationService.onCalendarTick failed", t);
                }
            }

            // Greed anonymous trade session timeouts
            try {
                GreedTradeManager.tick(server);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[ModTick] GreedTradeManager.tick failed", t);
            }

            // 对局结束结算画面：环境就绪后二次广播
            try {
                GameEndTransitionCoordinator.tick(server);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[ModTick] GameEndTransitionCoordinator.tick failed", t);
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
            BetelTickEngine.tickPlayer(player);
            ExtraSlotComponent.KEY.get(player).serverTick();
        }
    }
}
