package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.game.sre.SREGameModeBase;
import com.habitrain.core.task.GameLifecycleHandler;
import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class ModTickHandler {
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SREGameModeBase.processPendingVoiceJoins(server);
            SREGameModeBase.processGameEndGroupJoin(server);
            GameModeRegistry.tickAll(server);
            tickMoreMods(server);
        });
    }

    public static void tickMoreMods(MinecraftServer server) {
        boolean anyGameActive = false;
        boolean hasActiveGame = false;
        for (ServerLevel world : server.getAllLevels()) {
            BetelLeafHandler.tickHarvests(world);
            if (BetelQuestState.isGameActive(world)) {
                anyGameActive = true;
                hasActiveGame = true;
            }
        }
        GameLifecycleHandler.tickGameEndCheck(anyGameActive, server);

        if (!hasActiveGame) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BetelQuestState.tickPlayer(player);
            ExtraSlotComponent.KEY.get(player).serverTick();
        }
    }
}
