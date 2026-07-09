package com.habitrain.core.config;

import com.habitrain.core.config.MinigameConfigEntry;
import com.habitrain.core.config.TaskConfigEntry;

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
