package com.habitrain.core.config;

import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;

public class MinigameEnforcement {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinigameEnforcement.class.getSimpleName());
    private final ConfigStore store;

    public MinigameEnforcement(ConfigStore store) {
        this.store = store;
    }

    public void apply(@Nullable MinecraftServer server, ConfigRepository repo) {
        if (server == null) return;
        try {
            for (var level : server.getAllLevels()) {
                var areas = io.wifi.starrailexpress.cca.AreasWorldComponent.KEY.get(level);
                if (areas == null) continue;
                areas.minigameQuestEnabled = repo.isMinigameGlobalEnabled();
                HashSet<String> available = areas.availableMinigameIds;
                available.clear();
                String mapName = areas.mapName != null ? areas.mapName : "";
                for (QuestMinigame mg : store.safeGetAllMinigames()) {
                    MinigameConfigEntry entry = repo.getMinigameConfig(mg.id());
                    if (entry == null || (entry.enabled && entry.isAllowedOnMap(mapName))) {
                        available.add(mg.id());
                    }
                }
                areas.sync();
            }
            LOGGER.info("小游戏配置已强制应用: global={}，可用 {} 个", repo.isMinigameGlobalEnabled(), repo.getAllMinigameConfigs().size());
        } catch (Throwable t) {
            LOGGER.warn("applyMinigameEnforcement 失败，SRE 可能未安装", t);
        }
    }
}
