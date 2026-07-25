package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin adapter around SRE MapManager / GameUtils / game-mode constants.
 * All SRE reflection-style calls are try/caught so missing SRE does not crash the API.
 */
public final class SREModeStartAdapter {
    private SREModeStartAdapter() {}

    public static boolean isSreGameBlocking(ServerLevel level) {
        try {
            if (io.wifi.starrailexpress.game.GameUtils.isStartingGame) return true;
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
            return gw != null && gw.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> getAvailableMaps(ServerLevel level) {
        try {
            return new ArrayList<>(io.wifi.starrailexpress.game.MapManager.getAvailableMaps(level));
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static boolean loadMap(ServerLevel level, String mapId) {
        try {
            return io.wifi.starrailexpress.game.MapManager.loadMap(level, mapId);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("loadMap failed: {}", mapId, t);
            return false;
        }
    }

    /**
     * @param registryFullId e.g. habitrain_core:habitrain:blackout
     */
    public static boolean startMode(ServerLevel level, String registryFullId) {
        if (level == null || registryFullId == null || registryFullId.isBlank()) {
            return false;
        }
        try {
            // blackout path
            if (registryFullId.endsWith(":habitrain:blackout")
                    || "habitrain_core:habitrain:blackout".equals(registryFullId)) {
                GameModeRegistry.start("habitrain_core:habitrain:blackout", level);
                return GameModeRegistry.isActiveInLevel(level);
            }
            // murder
            if (registryFullId.contains("sre:murder")) {
                var mode = io.wifi.starrailexpress.api.SREGameModes.MURDER;
                int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(mode.defaultStartTime, 0);
                io.wifi.starrailexpress.game.GameUtils.startGame(level, mode, ticks);
                // GameUtils.startGame is void; best-effort success signal is SRE running/starting.
                return isSreGameBlocking(level);
            }
            // repair
            if (registryFullId.contains("sre:repair")) {
                var mode = io.wifi.starrailexpress.api.SREGameModes.REPAIR_ESCAPE_MODE;
                int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(mode.defaultStartTime, 0);
                io.wifi.starrailexpress.game.GameUtils.startGame(level, mode, ticks);
                return isSreGameBlocking(level);
            }
            // Original SRE modes bridged as thin proxies (wifi:tnt_tag, wifi:lover, …)
            GameMode registered = GameModeRegistry.get(registryFullId);
            if (registered instanceof SreOriginalModeProxy proxy) {
                return startSreModeById(level, proxy.getSreId());
            }
            // generic explicit registry start
            if (GameModeRegistry.isRegistered(registryFullId)) {
                GameModeRegistry.start(registryFullId, level);
                return GameModeRegistry.isActiveInLevel(level);
            }
            return false;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("startMode failed: {}", registryFullId, t);
            return false;
        }
    }

    private static boolean startSreModeById(ServerLevel level, ResourceLocation sreId) {
        if (sreId == null) {
            return false;
        }
        var sreMode = io.wifi.starrailexpress.api.SREGameModes.GAME_MODES.get(sreId);
        if (sreMode == null) {
            HabiTrainCore.LOGGER.warn("SRE mode not found in GAME_MODES: {}", sreId);
            return false;
        }
        int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(sreMode.defaultStartTime, 0);
        io.wifi.starrailexpress.game.GameUtils.startGame(level, sreMode, ticks);
        return isSreGameBlocking(level);
    }
}
