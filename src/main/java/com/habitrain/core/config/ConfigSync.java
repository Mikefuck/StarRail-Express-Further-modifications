package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ConfigSync {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigSync.class.getSimpleName());
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
            int newTempPowerPrice = 100;
            boolean newMgGlobal = true;
            ModeMapVoteSettings newModeMapVote = ModeMapVoteSettings.createDefault();

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
                if (global.has("tempPowerPrice")) {
                    int price = global.get("tempPowerPrice").getAsInt();
                    if (price >= 0) newTempPowerPrice = price;
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

            if (root.has("modeMapVote") && root.get("modeMapVote").isJsonObject()) {
                newModeMapVote = ModeMapVoteSettings.fromJson(root.getAsJsonObject("modeMapVote"));
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
            repo.setTempPowerPrice(newTempPowerPrice);
            repo.setMinigameGlobalEnabled(newMgGlobal);
            repo.setModeMapVote(newModeMapVote);
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串加载配置失败，保持原有内存状态不变", e);
        }
    }

    /**
     * 合并语义：仅用 json 中存在的 tasks/gameModes/minigames 覆盖对应键，
     * 不删除 json 中缺失的键（避免 OP 客户端陈旧视图删除服务端独有条目）。
     * global 字段按 json 覆盖（整体项）。
     * modeMapVote 标量按 json 覆盖；modes/maps 仅 put 存在的键，不删缺失键。
     */
    public void mergeFromJsonString(ConfigRepository repo, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");
                if (global.has("dlcProbabilityTarget")) {
                    repo.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble())));
                }
                if (global.has("shaderWhitelistEnabled")) {
                    repo.setShaderWhitelistEnabled(global.get("shaderWhitelistEnabled").getAsBoolean());
                }
                if (global.has("shaderWhitelist")) {
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    List<String> list = new ArrayList<>();
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) list.add(name);
                    }
                    repo.getShaderWhitelist().clear();
                    repo.getShaderWhitelist().addAll(list);
                }
                if (global.has("sheriffCountDivisor")) {
                    int div = global.get("sheriffCountDivisor").getAsInt();
                    if (div > 0) repo.setSheriffCountDivisor(div);
                }
                if (global.has("tempPowerPrice")) {
                    int price = global.get("tempPowerPrice").getAsInt();
                    if (price >= 0) repo.setTempPowerPrice(price);
                }
            }

            // 合并而非替换：json 中有的键覆盖，缺失的键保留服务端原值
            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                for (var entry : tasks.entrySet()) {
                    repo.getMutableTaskConfigs().put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }
            if (root.has("gameModes")) {
                JsonObject modes = root.getAsJsonObject("gameModes");
                for (var entry : modes.entrySet()) {
                    repo.getMutableGameModeConfigs().put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
            if (root.has("minigames")) {
                JsonObject mg = root.getAsJsonObject("minigames");
                if (mg.has("globalEnabled")) {
                    repo.setMinigameGlobalEnabled(mg.get("globalEnabled").getAsBoolean());
                }
                if (mg.has("entries")) {
                    JsonObject entries = mg.getAsJsonObject("entries");
                    for (var e : entries.entrySet()) {
                        repo.getMutableMinigameConfigs().put(e.getKey(),
                                MinigameConfigEntry.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }

            if (root.has("modeMapVote") && root.get("modeMapVote").isJsonObject()) {
                JsonObject mmv = root.getAsJsonObject("modeMapVote");
                ModeMapVoteSettings s = repo.getModeMapVote();
                if (mmv.has("enabled")) s.enabled = mmv.get("enabled").getAsBoolean();
                if (mmv.has("modeDurationSeconds")) {
                    int v = mmv.get("modeDurationSeconds").getAsInt();
                    s.modeDurationSeconds = Math.max(5, Math.min(120, v));
                }
                if (mmv.has("mapDurationSeconds")) {
                    int v = mmv.get("mapDurationSeconds").getAsInt();
                    s.mapDurationSeconds = Math.max(5, Math.min(120, v));
                }
                if (mmv.has("modes") && mmv.get("modes").isJsonObject()) {
                    JsonObject modesObj = mmv.getAsJsonObject("modes");
                    for (var e : modesObj.entrySet()) {
                        if (e.getValue().isJsonObject()) {
                            s.modes.put(e.getKey(), ModeVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                        }
                    }
                }
                if (mmv.has("maps") && mmv.get("maps").isJsonObject()) {
                    JsonObject mapsObj = mmv.getAsJsonObject("maps");
                    for (var e : mapsObj.entrySet()) {
                        if (e.getValue().isJsonObject()) {
                            s.maps.put(e.getKey(), MapVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串合并配置失败，保持原有内存状态不变", e);
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
            // 客户端收到服务器配置后只更新内存供 GUI 显示，不写本地盘，
            // 避免把服务器配置污染到本地 habitrain_core.json（影响下次单机开服）。
            loadFromJsonString(repo, json);
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
