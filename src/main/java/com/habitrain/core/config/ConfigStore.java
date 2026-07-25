package com.habitrain.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.task.TaskBalancer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigStore.class.getSimpleName());
    private final File configFile;
    private final Gson gson;
    private boolean dirty = false;

    public ConfigStore() {
        this.configFile = new File(
                FabricLoader.getInstance().getConfigDir().toFile(),
                "habitrain_core.json"
        );
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public File getConfigFile() { return configFile; }

    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Save to file only if dirty. Resets the dirty flag on success.
     * @return true if the file was actually written, false if no-op
     */
    public boolean commit(ConfigRepository repo) {
        if (!dirty) return false;
        boolean saved = save(repo);
        if (saved) {
            dirty = false;
            return true;
        }
        // save 失败：保留 dirty，下次 commit 重试，避免改动永久丢失
        return false;
    }

    public void load(ConfigRepository repo) {
        this.dirty = false;
        repo.getMutableTaskConfigs().clear();
        repo.getMutableGameModeConfigs().clear();
        repo.getMutableMinigameConfigs().clear();
        repo.setDlcProbabilityTarget(0.5f);
        repo.setModeMapVote(ModeMapVoteSettings.createDefault());

        if (!configFile.exists()) {
            createDefaultConfig(repo);
            save(repo);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");

                if (global.has("dlcProbabilityTarget")) {
                    repo.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble())));
                } else if (global.has("dlcWeightBoost")) {
                    float oldBoost = Math.max(0f, global.get("dlcWeightBoost").getAsFloat());
                    long dlcCount = countDlcTasks();
                    long origCount = countOriginalTasks();
                    if (dlcCount > 0 && origCount > 0) {
                        float total = oldBoost * dlcCount + origCount;
                        repo.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8, oldBoost * dlcCount / total)));
                    }
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

                LOGGER.info("全局设置: DLC目标占比={}%, 光影白名单={}, 允许{}个光影, 警长除数={}",
                        Math.round(repo.getDlcProbabilityTarget() * 100),
                        repo.isShaderWhitelistEnabled() ? "启用" : "禁用",
                        repo.getShaderWhitelist().size(),
                        repo.getSheriffCountDivisor());
            }

            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                Map<String, TaskConfigEntry> taskMap = repo.getMutableTaskConfigs();
                for (var entry : tasks.entrySet()) {
                    taskMap.put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("gameModes")) {
                JsonObject modes = root.getAsJsonObject("gameModes");
                Map<String, GameModeConfigScope> modeMap = repo.getMutableGameModeConfigs();
                modeMap.clear();
                for (var entry : modes.entrySet()) {
                    modeMap.put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("minigames")) {
                JsonObject mg = root.getAsJsonObject("minigames");
                Map<String, MinigameConfigEntry> mgMap = repo.getMutableMinigameConfigs();
                mgMap.clear();
                if (mg.has("globalEnabled")) {
                    repo.setMinigameGlobalEnabled(mg.get("globalEnabled").getAsBoolean());
                }
                if (mg.has("entries")) {
                    JsonObject entries = mg.getAsJsonObject("entries");
                    for (var e : entries.entrySet()) {
                        mgMap.put(e.getKey(),
                                MinigameConfigEntry.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }

            if (root.has("modeMapVote") && root.get("modeMapVote").isJsonObject()) {
                repo.setModeMapVote(ModeMapVoteSettings.fromJson(root.getAsJsonObject("modeMapVote")));
            } else {
                repo.setModeMapVote(ModeMapVoteSettings.createDefault());
            }

            if (root.has("environment") && root.get("environment").isJsonObject()) {
                repo.setEnvironment(EnvironmentSettings.fromJson(root.getAsJsonObject("environment")));
            } else {
                repo.setEnvironment(EnvironmentSettings.createDefault());
            }

            if (root.has("roleOverrides") && root.get("roleOverrides").isJsonObject()) {
                repo.setRoleOverrides(RoleOverrideConfigSection.fromJson(root.getAsJsonObject("roleOverrides")));
            } else {
                repo.setRoleOverrides(RoleOverrideConfigSection.createDefault());
            }

            LOGGER.info("任务配置已加载: {} 个任务, {} 个GameMode, {} 个小游戏",
                    repo.getMutableTaskConfigs().size(),
                    repo.getMutableGameModeConfigs().size(),
                    repo.getMutableMinigameConfigs().size());
        } catch (Exception e) {
            LOGGER.error("加载任务配置失败，重建默认配置并覆盖损坏的配置文件", e);
            createDefaultConfig(repo);
            repo.getMutableGameModeConfigs().clear();
            repo.setShaderWhitelistEnabled(false);
            repo.getShaderWhitelist().clear();
            repo.getMutableMinigameConfigs().clear();
            repo.setMinigameGlobalEnabled(true);
            repo.setModeMapVote(ModeMapVoteSettings.createDefault());
            repo.setEnvironment(EnvironmentSettings.createDefault());
            save(repo);
        }
    }

    /**
     * @return true 写盘成功；false 失败（含 IO 与 buildJsonRoot 抛出的 RuntimeException），
     *         调用方据此决定是否保留 dirty flag（见 commit）。
     */
    public boolean save(ConfigRepository repo) {
        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

            JsonObject root = buildJsonRoot(repo, true);

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
            return true;
        } catch (Exception e) {
            // 同时捕获 IOException 与 buildJsonRoot 抛出的 RuntimeException，
            // 避免非 IO 异常传出后 commit 已清 dirty 导致改动永久丢失。
            LOGGER.error("保存配置失败", e);
            return false;
        }
    }

    public JsonObject buildJsonRoot(ConfigRepository repo, boolean includeWeightBoost) {
        JsonObject root = new JsonObject();

        JsonObject global = new JsonObject();
        global.addProperty("dlcProbabilityTarget", repo.getDlcProbabilityTarget());
        if (includeWeightBoost) {
            global.addProperty("dlcWeightBoost", getDlcWeightBoost(repo));
        }
        global.addProperty("shaderWhitelistEnabled", repo.isShaderWhitelistEnabled());
        var whitelistArray = new com.google.gson.JsonArray();
        for (String name : repo.getShaderWhitelist()) whitelistArray.add(name);
        global.add("shaderWhitelist", whitelistArray);
        global.addProperty("sheriffCountDivisor", repo.getSheriffCountDivisor());
        global.addProperty("tempPowerPrice", repo.getTempPowerPrice());
        root.add("global", global);

        JsonObject tasks = new JsonObject();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            String fullId = def.getFullId();
            TaskConfigEntry entry = repo.getTaskConfig(fullId);
            if (entry == null) entry = new TaskConfigEntry(true);
            tasks.add(fullId, entry.toJson());
        }
        root.add("tasks", tasks);

        JsonObject gameModes = new JsonObject();
        for (Map.Entry<String, GameModeConfigScope> e : repo.getAllGameModeConfigs().entrySet()) {
            gameModes.add(e.getKey(), e.getValue().toJson());
        }
        root.add("gameModes", gameModes);

        JsonObject minigames = new JsonObject();
        minigames.addProperty("globalEnabled", repo.isMinigameGlobalEnabled());
        JsonObject mgEntries = new JsonObject();
        for (String id : safeGetAllMinigameIds()) {
            MinigameConfigEntry entry = repo.getMinigameConfig(id);
            if (entry == null) entry = MinigameConfigEntry.createDefault();
            mgEntries.add(id, entry.toJson());
        }
        minigames.add("entries", mgEntries);
        root.add("minigames", minigames);

        root.add("modeMapVote", repo.getModeMapVote().toJson());

        root.add("environment", repo.getEnvironment().toJson());

        root.add("roleOverrides", repo.getRoleOverrides().toJson());

        return root;
    }

    public void createDefaultConfig(ConfigRepository repo) {
        Map<String, TaskConfigEntry> taskMap = repo.getMutableTaskConfigs();
        taskMap.clear();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            taskMap.put(def.getFullId(), new TaskConfigEntry(true));
        }
        repo.setDlcProbabilityTarget(0.5f);
        Map<String, MinigameConfigEntry> mgMap = repo.getMutableMinigameConfigs();
        mgMap.clear();
        for (String id : safeGetAllMinigameIds()) {
            mgMap.put(id, MinigameConfigEntry.createDefault());
        }
        repo.setMinigameGlobalEnabled(true);
        repo.setModeMapVote(ModeMapVoteSettings.createDefault());
        repo.setEnvironment(EnvironmentSettings.createDefault());
    }

    public String toJsonString(ConfigRepository repo) {
        return gson.toJson(buildJsonRoot(repo, false));
    }

    public List<String> safeGetAllMinigameIds() {
        return SREIntegration.getAllMinigameIds();
    }

    public long countDlcTasks() {
        return TaskRegistry.getAll().stream()
                .filter(t -> !"habitrain_core".equals(t.getModId()))
                .count();
    }

    public long countOriginalTasks() {
        return TaskRegistry.getAll().stream()
                .filter(t -> "habitrain_core".equals(t.getModId()))
                .count();
    }

    public float getDlcWeightBoost(ConfigRepository repo) {
        long dlcCount = countDlcTasks();
        long origCount = countOriginalTasks();
        float boost = TaskBalancer.calcBoost(repo.getDlcProbabilityTarget(), dlcCount, origCount);

        if (dlcCount > 0) {
            float dlcTotal = boost * dlcCount;
            float pct = (dlcTotal + origCount) > 0 ? dlcTotal / (dlcTotal + origCount) : 0;
            LOGGER.info("[DLC概率] 目标={}%, {}个DLC vs {}个原版 → autoBoost={} (实际概率≈{}%)",
                    Math.round(repo.getDlcProbabilityTarget() * 100), dlcCount, origCount,
                    String.format("%.2f", boost), Math.round(pct * 100));
        }
        return boost;
    }

}
