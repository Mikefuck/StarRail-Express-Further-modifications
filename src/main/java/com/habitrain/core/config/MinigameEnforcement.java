package com.habitrain.core.config;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinigameEnforcement {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinigameEnforcement.class.getSimpleName());
    private final ConfigStore store;

    public MinigameEnforcement(ConfigStore store) {
        this.store = store;
    }

    public void apply(@Nullable MinecraftServer server, ConfigRepository repo) {
        if (server == null) return;
        SREIntegration.applyMinigameSettings(
                server,
                repo.isMinigameGlobalEnabled(),
                store.safeGetAllMinigameIds(),
                repo
        );
        LOGGER.info("小游戏配置已强制应用: global={}，可用 {} 个", repo.isMinigameGlobalEnabled(), repo.getAllMinigameConfigs().size());
    }
}
