package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelTickEngine;
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

            // 1Hz option-vote tick (mode/map lobby vote countdown)
            voteTickCounter++;
            if (voteTickCounter % 20 == 0) {
                for (ServerLevel level : server.getAllLevels()) {
                    OptionVoteManager.tickSecond(level);
                }
            }

            // Greed anonymous trade session timeouts
            try {
                GreedTradeManager.tick(server);
            } catch (Throwable ignored) {}

            tickMoreMods(server);
        });
    }

    public static void tickMoreMods(MinecraftServer server) {
        boolean isGameActive = false;
        for (ServerLevel world : server.getAllLevels()) {
            BetelLeafHandler.tickHarvests(world);
            if (BetelTickEngine.isGameActive(world)) {
                isGameActive = true;
            }
        }
        GameLifecycleHandler.tickGameEndCheck(isGameActive, server);

        // 人数不足 8 人下雨（包含大厅→对局→对局结束全覆盖）
        for (ServerLevel world : server.getAllLevels()) {
            if (world.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                SREWeatherController.tick(world);
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
