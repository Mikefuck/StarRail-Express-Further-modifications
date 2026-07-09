package com.habitrain.core.config;

import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import io.wifi.starrailexpress.content.minigame.QuestMinigames;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Adapter isolating SRE DLC dependency behind try/catch wrappers.
 * This is the only class in the config package that directly references SRE types.
 */
public final class SREIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("SREIntegration");

    private SREIntegration() {}

    /**
     * @return all registered QuestMinigame IDs, or empty list if SRE is not installed
     */
    public static List<String> getAllMinigameIds() {
        try {
            return QuestMinigames.getAll().stream()
                    .map(QuestMinigame::id)
                    .toList();
        } catch (Throwable t) {
            LOGGER.warn("SRE not available, returning empty minigame list", t);
            return List.of();
        }
    }

    /**
     * Apply minigame enforcement to all world levels via AreasWorldComponent.
     * Filters minigames per-level based on each level's map name.
     */
    public static void applyMinigameSettings(MinecraftServer server,
                                              boolean globalEnabled,
                                              List<String> allMinigameIds,
                                              ConfigRepository repo) {
        if (server == null) return;
        try {
            for (var level : server.getAllLevels()) {
                var areas = AreasWorldComponent.KEY.get(level);
                if (areas == null) continue;

                areas.minigameQuestEnabled = globalEnabled;
                String mapName = areas.mapName != null ? areas.mapName : "";
                areas.availableMinigameIds.clear();
                for (String mgId : allMinigameIds) {
                    MinigameConfigEntry entry = repo.getMinigameConfig(mgId);
                    if (entry == null || (entry.enabled && entry.isAllowedOnMap(mapName))) {
                        areas.availableMinigameIds.add(mgId);
                    }
                }
                areas.sync();
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply minigame settings, SRE may not be installed", t);
        }
    }
}
