package com.habitrain.core.betel;

import betel.nut.component.BetelNutEntityComponents;
import com.habitrain.core.HabiTrainCore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import java.util.*;

public class BetelQuestState {
    private static BetelQuestState instance;

    private boolean revealUsedThisRound = false;

    private final Map<UUID, PlayerBetelData> playerData = new HashMap<>();

    private BetelQuestState() {}

    public static void init() {
        instance = new BetelQuestState();
    }

    public static BetelQuestState getInstance() {
        if (instance == null) {
            instance = new BetelQuestState();
        }
        return instance;
    }

    private PlayerBetelData computePlayerData(UUID uuid) {
        return playerData.computeIfAbsent(uuid, k -> new PlayerBetelData());
    }

    public static PlayerBetelData getPlayerData(UUID uuid) {
        return getInstance().computePlayerData(uuid);
    }

    public static void markQuestAssigned(UUID uuid) {
        getPlayerData(uuid).hasBetelQuestBeenAssigned = true;
        HabiTrainCore.LOGGER.debug("玩家 {} 的槟榔任务已标记为本局已刷新", getPlayerName(uuid));
    }

    public static boolean hasQuestBeenAssigned(UUID uuid) {
        return getPlayerData(uuid).hasBetelQuestBeenAssigned;
    }

    public static boolean hasFoodRestriction(UUID uuid) {
        return getPlayerData(uuid).hasFoodRestriction;
    }

    public static void setFoodRestriction(UUID uuid, boolean restricted) {
        getPlayerData(uuid).hasFoodRestriction = restricted;
    }

    public static void resetEatenStatus(Player player) {
        if (player == null) return;
        PlayerBetelData data = getPlayerData(player.getUUID());
        data.hasEatenBetelNut = false;

        try {
            var addiction = BetelNutEntityComponents.ADDICTION.get(player);
            long currentEatTime = addiction.getLastEatTime();
            data.lastDetectedEatTime = currentEatTime > 0 ? currentEatTime : 0;
        } catch (Exception e) {
            data.lastDetectedEatTime = 0;
        }

        HabiTrainCore.LOGGER.debug("玩家 {} 的吃槟榔状态已重置 (lastDetectedEatTime={})",
                player.getName().getString(), data.lastDetectedEatTime);
    }

    public static boolean hasPlayerEatenBetelNut(UUID uuid) {
        return getPlayerData(uuid).hasEatenBetelNut;
    }

    private static MinecraftServer getCurrentServer() {
        try {
            var instance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
            if (instance instanceof MinecraftServer server) {
                return server;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String getPlayerName(UUID uuid) {
        var server = getCurrentServer();
        if (server != null) {
            var player = server.getPlayerList().getPlayer(uuid);
            if (player != null) return player.getName().getString();
        }
        return uuid.toString();
    }

    public void resetAll() {
        revealUsedThisRound = false;
        playerData.clear();
    }

    public boolean isRevealUsed() {
        return revealUsedThisRound;
    }

    public void setRevealUsed(boolean used) {
        this.revealUsedThisRound = used;
    }

    public static class PlayerBetelData {
        boolean hasBetelQuestBeenAssigned = false;
        int lastDiagnosticStage = 0;
        boolean hasBeenProcessed = false;
        boolean wasGameNotRunning = false;
        boolean wasSpectating = false;
        long lastKnownLastEatTime = 0;
        long lastDetectedEatTime = 0;
        boolean hasEatenBetelNut = false;
        int betelNutsEatenThisGame = 0;
        long ownLastEatGameTime = 0;
        boolean darknessAppliedThisTrigger = false;
        boolean hasHeavyAddiction = false;
        boolean ateBetelNutToRelieve = false;
        boolean hasFoodRestriction = false;
    }
}
