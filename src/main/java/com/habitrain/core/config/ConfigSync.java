package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ConfigSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("ConfigManager");
    private final ConfigStore store;

    public ConfigSync(ConfigStore store) {
        this.store = store;
    }

    public void loadFromJsonString(ConfigRepository repo, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            Map<String, TaskConfigEntry> newTasks = new HashMap<>();
            Map<String, GameModeConfigScope> newModes = new HashMap<>();
            Map<String, MinigameConfigEntry> newMinigames = new HashMap<>();
            List<String> newShaderWhitelist = new ArrayList<>();
            float newDlcTarget = 0.5f;
            boolean newShaderEnabled = false;
            int newSheriffDivisor = 6;
            boolean newMgGlobal = true;

            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");
                if (global.has("dlcProbabilityTarget")) {
                    newDlcTarget = (float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble()));
                }
                if (global.has("shaderWhitelistEnabled")) {
                    newShaderEnabled = global.get("shaderWhitelistEnabled").getAsBoolean();
                }
                if (global.has("shaderWhitelist")) {
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) newShaderWhitelist.add(name);
                    }
                }
                if (global.has("sheriffCountDivisor")) {
                    int div = global.get("sheriffCountDivisor").getAsInt();
                    if (div > 0) newSheriffDivisor = div;
                }
            }

            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                for (var entry : tasks.entrySet()) {
                    newTasks.put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("gameModes")) {
                JsonObject modes = root.getAsJsonObject("gameModes");
                for (var entry : modes.entrySet()) {
                    newModes.put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("minigames")) {
                JsonObject mg = root.getAsJsonObject("minigames");
                if (mg.has("globalEnabled")) {
                    newMgGlobal = mg.get("globalEnabled").getAsBoolean();
                }
                if (mg.has("entries")) {
                    JsonObject entries = mg.getAsJsonObject("entries");
                    for (var e : entries.entrySet()) {
                        newMinigames.put(e.getKey(),
                                MinigameConfigEntry.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }

            repo.getMutableTaskConfigs().clear();
            repo.getMutableTaskConfigs().putAll(newTasks);
            repo.getMutableGameModeConfigs().clear();
            repo.getMutableGameModeConfigs().putAll(newModes);
            repo.getMutableMinigameConfigs().clear();
            repo.getMutableMinigameConfigs().putAll(newMinigames);
            repo.setDlcProbabilityTarget(newDlcTarget);
            repo.setShaderWhitelistEnabled(newShaderEnabled);
            repo.getShaderWhitelist().clear();
            repo.getShaderWhitelist().addAll(newShaderWhitelist);
            repo.setSheriffCountDivisor(newSheriffDivisor);
            repo.setMinigameGlobalEnabled(newMgGlobal);
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串加载配置失败，保持原有内存状态不变", e);
        }
    }

    public void applySyncData(ConfigRepository repo, Map<String, TaskConfigEntry> configs, float target) {
        repo.setSuppressCallback(true);
        try {
            repo.getMutableTaskConfigs().clear();
            repo.getMutableTaskConfigs().putAll(configs);
            repo.setDlcProbabilityTarget(target);
        } finally {
            repo.setSuppressCallback(false);
        }
    }

    public void applySyncFromJson(ConfigRepository repo, String json) {
        repo.setSuppressCallback(true);
        try {
            loadFromJsonString(repo, json);
            store.save(repo);
        } finally {
            repo.setSuppressCallback(false);
        }
    }

    public void applyShaderWhitelistSync(ConfigRepository repo, boolean enabled, List<String> list) {
        repo.setShaderWhitelistEnabled(enabled);
        repo.getShaderWhitelist().clear();
        repo.getShaderWhitelist().addAll(list);
    }
}
