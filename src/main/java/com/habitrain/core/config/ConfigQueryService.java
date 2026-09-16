package com.habitrain.core.config;


/**
 * Read-only interface for configuration data.
 * GUI/network layers should depend on this interface rather than
 * the concrete ConfigManager singleton.
 */
public interface ConfigQueryService {
    TaskConfigEntry getTaskConfig(String fullId);
    MinigameConfigEntry getMinigameConfig(String minigameId);
    boolean isTaskEnabled(String fullId, String mapName);
    boolean isMapAllowed(String fullId, String mapName);
    float getDlcWeightBoost();
    boolean isMinigameGlobalEnabled();
    boolean isMinigameEnabledForMap(String minigameId, String mapName);
}
