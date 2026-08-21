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
            boolean newKnifeDurabilityEnabled = false;
            boolean newLobbyVoiceGroupEnabled = true;
            boolean newBlackoutEffectEnhancementEnabled = false;
            boolean newMgGlobal = true;
            ModeMapVoteSettings newModeMapVote = ModeMapVoteSettings.createDefault();
            EnvironmentSettings newEnv = EnvironmentSettings.createDefault();

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
                if (global.has("knifeDurabilityEnabled")) {
                    newKnifeDurabilityEnabled = global.get("knifeDurabilityEnabled").getAsBoolean();
                }
                if (global.has("lobbyVoiceGroupEnabled")) {
                    newLobbyVoiceGroupEnabled = global.get("lobbyVoiceGroupEnabled").getAsBoolean();
                }
                if (global.has("blackoutEffectEnhancementEnabled")) {
                    newBlackoutEffectEnhancementEnabled =
                            global.get("blackoutEffectEnhancementEnabled").getAsBoolean();
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

            if (root.has("environment") && root.get("environment").isJsonObject()) {
                newEnv = EnvironmentSettings.fromJson(root.getAsJsonObject("environment"));
            }

            RoleOverrideConfigSection newRoleOverrides = null;
            if (root.has("roleOverrides") && root.get("roleOverrides").isJsonObject()) {
                newRoleOverrides = RoleOverrideConfigSection.fromJson(root.getAsJsonObject("roleOverrides"));
            }

            MvpAnimationSettings newMvpAnimations = MvpAnimationSettings.createDefault();
            if (root.has("mvpAnimations") && root.get("mvpAnimations").isJsonObject()) {
                newMvpAnimations = MvpAnimationSettings.fromJson(root.getAsJsonObject("mvpAnimations"));
            }

            repo.getMutableTaskConfigs().clear();
            repo.getMutableTaskConfigs().putAll(newTasks);
            repo.getMutableGameModeConfigs().clear();
            repo.getMutableGameModeConfigs().putAll(newModes);
            repo.getMutableMinigameConfigs().clear();
            repo.getMutableMinigameConfigs().putAll(newMinigames);
            repo.setDlcProbabilityTarget(newDlcTarget);
            repo.setShaderWhitelistEnabled(newShaderEnabled);
            repo.setShaderWhitelist(newShaderWhitelist);
            repo.setSheriffCountDivisor(newSheriffDivisor);
            repo.setTempPowerPrice(newTempPowerPrice);
            repo.setKnifeDurabilityEnabled(newKnifeDurabilityEnabled);
            repo.setLobbyVoiceGroupEnabled(newLobbyVoiceGroupEnabled);
            repo.setBlackoutEffectEnhancementEnabled(newBlackoutEffectEnhancementEnabled);
            repo.setMinigameGlobalEnabled(newMgGlobal);
            repo.setModeMapVote(newModeMapVote);
            repo.setEnvironment(newEnv);
            if (newRoleOverrides != null) {
                repo.setRoleOverrides(newRoleOverrides);
            }
            repo.setMvpAnimations(newMvpAnimations);
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串加载配置失败，保持原有内存状态不变", e);
        }
    }

    /**
     * 合并语义：仅用 json 中存在的 tasks/gameModes/minigames 覆盖对应键，
     * 不删除 json 中缺失的键（避免 OP 客户端陈旧视图删除服务端独有条目）。
     * global 字段按 json 覆盖（整体项）。
     * modeMapVote 标量按 json 覆盖；modes/maps 按 JSON key 顺序整表重建并
     * 追加服务端独有键（不删缺失键，同时保留投票列表顺序）。
     * <p>
     * 先完整解析到临时结构，成功后再一次性写入 repo，避免半更新。
     *
     * @return true 合并成功；false 解析/校验失败（repo 保持不变）
     */
    public boolean mergeFromJsonString(ConfigRepository repo, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // ---- stage 1: parse into temps (no repo writes) ----
            Float newDlcTarget = null;
            Boolean newShaderEnabled = null;
            List<String> newShaderWhitelist = null;
            Integer newSheriffDivisor = null;
            Integer newTempPowerPrice = null;
            Boolean newKnifeDurability = null;
            Boolean newLobbyVoice = null;
            Boolean newBlackoutFx = null;

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
                    List<String> list = new ArrayList<>();
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) list.add(name);
                    }
                    newShaderWhitelist = list;
                }
                if (global.has("sheriffCountDivisor")) {
                    int div = global.get("sheriffCountDivisor").getAsInt();
                    if (div > 0) newSheriffDivisor = div;
                }
                if (global.has("tempPowerPrice")) {
                    int price = global.get("tempPowerPrice").getAsInt();
                    if (price >= 0) newTempPowerPrice = price;
                }
                if (global.has("knifeDurabilityEnabled")) {
                    newKnifeDurability = global.get("knifeDurabilityEnabled").getAsBoolean();
                }
                if (global.has("lobbyVoiceGroupEnabled")) {
                    newLobbyVoice = global.get("lobbyVoiceGroupEnabled").getAsBoolean();
                }
                if (global.has("blackoutEffectEnhancementEnabled")) {
                    newBlackoutFx = global.get("blackoutEffectEnhancementEnabled").getAsBoolean();
                }
            }

            Map<String, TaskConfigEntry> taskPatches = null;
            if (root.has("tasks")) {
                taskPatches = new HashMap<>();
                JsonObject tasks = root.getAsJsonObject("tasks");
                for (var entry : tasks.entrySet()) {
                    taskPatches.put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }

            Map<String, GameModeConfigScope> modePatches = null;
            if (root.has("gameModes")) {
                modePatches = new HashMap<>();
                JsonObject modes = root.getAsJsonObject("gameModes");
                for (var entry : modes.entrySet()) {
                    modePatches.put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            Boolean newMgGlobal = null;
            Map<String, MinigameConfigEntry> mgPatches = null;
            if (root.has("minigames")) {
                JsonObject mg = root.getAsJsonObject("minigames");
                if (mg.has("globalEnabled")) {
                    newMgGlobal = mg.get("globalEnabled").getAsBoolean();
                }
                if (mg.has("entries")) {
                    mgPatches = new HashMap<>();
                    JsonObject entries = mg.getAsJsonObject("entries");
                    for (var e : entries.entrySet()) {
                        mgPatches.put(e.getKey(),
                                MinigameConfigEntry.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }

            // modeMapVote: compute next ModeMapVoteSettings copy-on-write from current
            ModeMapVoteSettings nextModeMapVote = null;
            if (root.has("modeMapVote") && root.get("modeMapVote").isJsonObject()) {
                JsonObject mmv = root.getAsJsonObject("modeMapVote");
                ModeMapVoteSettings s = repo.getModeMapVote();
                // clone current into a working copy via toJson/fromJson for isolation
                ModeMapVoteSettings work = ModeMapVoteSettings.fromJson(s.toJson());
                if (mmv.has("enabled")) work.enabled = mmv.get("enabled").getAsBoolean();
                if (mmv.has("modeDurationSeconds")) {
                    int v = mmv.get("modeDurationSeconds").getAsInt();
                    work.modeDurationSeconds = Math.max(5, Math.min(120, v));
                }
                if (mmv.has("mapDurationSeconds")) {
                    int v = mmv.get("mapDurationSeconds").getAsInt();
                    work.mapDurationSeconds = Math.max(5, Math.min(120, v));
                }
                if (mmv.has("modes") && mmv.get("modes").isJsonObject()) {
                    JsonObject modesObj = mmv.getAsJsonObject("modes");
                    LinkedHashMap<String, ModeVoteEntry> rebuilt = new LinkedHashMap<>();
                    for (var e : modesObj.entrySet()) {
                        if (e.getValue().isJsonObject()) {
                            rebuilt.put(e.getKey(), ModeVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                        }
                    }
                    for (var existing : work.modes.entrySet()) {
                        rebuilt.putIfAbsent(existing.getKey(), existing.getValue());
                    }
                    work.modes.clear();
                    work.modes.putAll(rebuilt);
                }
                if (mmv.has("maps") && mmv.get("maps").isJsonObject()) {
                    JsonObject mapsObj = mmv.getAsJsonObject("maps");
                    LinkedHashMap<String, MapVoteEntry> rebuilt = new LinkedHashMap<>();
                    for (var e : mapsObj.entrySet()) {
                        if (e.getValue().isJsonObject()) {
                            rebuilt.put(e.getKey(), MapVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                        }
                    }
                    for (var existing : work.maps.entrySet()) {
                        rebuilt.putIfAbsent(existing.getKey(), existing.getValue());
                    }
                    work.maps.clear();
                    work.maps.putAll(rebuilt);
                }
                if (mmv.has("mapPlayerCountDraw") && mmv.get("mapPlayerCountDraw").isJsonObject()) {
                    work.mapPlayerCountDraw = MapPlayerCountSettings.fromJson(
                            mmv.getAsJsonObject("mapPlayerCountDraw"));
                }
                nextModeMapVote = work;
            }

            EnvironmentSettings nextEnv = null;
            if (root.has("environment") && root.get("environment").isJsonObject()) {
                nextEnv = EnvironmentSettings.fromJson(root.getAsJsonObject("environment"));
            }

            RoleOverrideConfigSection incomingRoleOverrides = null;
            if (root.has("roleOverrides") && root.get("roleOverrides").isJsonObject()) {
                incomingRoleOverrides = RoleOverrideConfigSection.fromJson(root.getAsJsonObject("roleOverrides"));
            }

            MvpAnimationSettings incomingMvpAnimations = null;
            if (root.has("mvpAnimations") && root.get("mvpAnimations").isJsonObject()) {
                incomingMvpAnimations = MvpAnimationSettings.fromJson(root.getAsJsonObject("mvpAnimations"));
            }

            // ---- stage 2: commit all temps to repo ----
            if (newDlcTarget != null) repo.setDlcProbabilityTarget(newDlcTarget);
            if (newShaderEnabled != null) repo.setShaderWhitelistEnabled(newShaderEnabled);
            if (newShaderWhitelist != null) repo.setShaderWhitelist(newShaderWhitelist);
            if (newSheriffDivisor != null) repo.setSheriffCountDivisor(newSheriffDivisor);
            if (newTempPowerPrice != null) repo.setTempPowerPrice(newTempPowerPrice);
            if (newKnifeDurability != null) repo.setKnifeDurabilityEnabled(newKnifeDurability);
            if (newLobbyVoice != null) repo.setLobbyVoiceGroupEnabled(newLobbyVoice);
            if (newBlackoutFx != null) repo.setBlackoutEffectEnhancementEnabled(newBlackoutFx);

            if (taskPatches != null) {
                repo.getMutableTaskConfigs().putAll(taskPatches);
            }
            if (modePatches != null) {
                repo.getMutableGameModeConfigs().putAll(modePatches);
            }
            if (newMgGlobal != null) {
                repo.setMinigameGlobalEnabled(newMgGlobal);
            }
            if (mgPatches != null) {
                repo.getMutableMinigameConfigs().putAll(mgPatches);
            }
            if (nextModeMapVote != null) {
                repo.setModeMapVote(nextModeMapVote);
            }
            if (nextEnv != null) {
                repo.setEnvironment(nextEnv);
            }
            if (incomingRoleOverrides != null) {
                RoleOverrideConfigSection existing = repo.getRoleOverrides();
                existing.setGlobalEnabled(incomingRoleOverrides.isGlobalEnabled());
                existing.getEntries().putAll(incomingRoleOverrides.getEntries());
                existing.getConflictResolution().putAll(incomingRoleOverrides.getConflictResolution());
            }
            if (incomingMvpAnimations != null) {
                MvpAnimationSettings existing = repo.getMvpAnimations();
                existing.enabled = incomingMvpAnimations.enabled;
                existing.randomSelection = incomingMvpAnimations.randomSelection;
                existing.avoidDuplicates = incomingMvpAnimations.avoidDuplicates;
                existing.showRoleItems = incomingMvpAnimations.showRoleItems;
                existing.speed = incomingMvpAnimations.speed;
                existing.animations.putAll(incomingMvpAnimations.animations);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串合并配置失败，保持原有内存状态不变", e);
            return false;
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
        repo.setShaderWhitelist(list != null ? list : List.of());
    }
}
