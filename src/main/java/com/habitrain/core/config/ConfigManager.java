package com.habitrain.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.task.TaskBalancer;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理器 — 取代 HabiConfigManager。
 * 管理全局设置、任务配置、GameMode 配置。
 * 已移除自动录制回放相关字段。
 */
public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ConfigManager");
    private static volatile ConfigManager INSTANCE;
    private final File configFile;
    private final Map<String, TaskConfigEntry> taskConfigs = new HashMap<>();
    private final Map<String, GameModeConfigScope> gameModeConfigs = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private float dlcProbabilityTarget = 0.5f;

    // Iris shader whitelist
    private boolean shaderWhitelistEnabled = false;
    private final List<String> shaderWhitelist = new ArrayList<>();

    // Save callback (client-side: auto-sync to server)
    @Nullable
    private static Runnable onSaveCallback = null;
    private boolean suppressCallback = false;

    public static void setOnSaveCallback(@Nullable Runnable callback) {
        onSaveCallback = callback;
    }

    private ConfigManager() {
        this.configFile = new File(
                FabricLoader.getInstance().getConfigDir().toFile(),
                "habitrain_core.json"
        );
    }

    public static ConfigManager getInstance() {
        if (INSTANCE == null) {
            synchronized (ConfigManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ConfigManager();
                }
            }
        }
        return INSTANCE;
    }

    // ========================================================================
    //  IO
    // ========================================================================

    public void load() {
        taskConfigs.clear();
        gameModeConfigs.clear();
        dlcProbabilityTarget = 0.5f;

        if (!configFile.exists()) {
            createDefaultConfig();
            save();
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");

                if (global.has("dlcProbabilityTarget")) {
                    dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble()));
                } else if (global.has("dlcWeightBoost")) {
                    float oldBoost = Math.max(0f, global.get("dlcWeightBoost").getAsFloat());
                    long dlcCount = countDlcTasks();
                    long origCount = countOriginalTasks();
                    if (dlcCount > 0 && origCount > 0) {
                        float total = oldBoost * dlcCount + origCount;
                        dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8, oldBoost * dlcCount / total));
                    }
                }

                if (global.has("shaderWhitelistEnabled")) {
                    shaderWhitelistEnabled = global.get("shaderWhitelistEnabled").getAsBoolean();
                }
                if (global.has("shaderWhitelist")) {
                    shaderWhitelist.clear();
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) shaderWhitelist.add(name);
                    }
                }

                LOGGER.info("全局设置: DLC目标占比={}%, 光影白名单={}, 允许{}个光影",
                        Math.round(dlcProbabilityTarget * 100),
                        shaderWhitelistEnabled ? "启用" : "禁用",
                        shaderWhitelist.size());
            }

            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                for (var entry : tasks.entrySet()) {
                    taskConfigs.put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("gameModes")) {
                JsonObject modes = root.getAsJsonObject("gameModes");
                for (var entry : modes.entrySet()) {
                    gameModeConfigs.put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            LOGGER.info("任务配置已加载: {} 个任务, {} 个GameMode", taskConfigs.size(), gameModeConfigs.size());
        } catch (Exception e) {
            LOGGER.error("加载任务配置失败，使用默认配置", e);
            createDefaultConfig();
        }
    }

    public void save() {
        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

            JsonObject root = new JsonObject();

            JsonObject global = new JsonObject();
            global.addProperty("dlcProbabilityTarget", dlcProbabilityTarget);
            global.addProperty("dlcWeightBoost", calculateCurrentBoost());
            global.addProperty("shaderWhitelistEnabled", shaderWhitelistEnabled);
            var whitelistArray = new com.google.gson.JsonArray();
            for (String name : shaderWhitelist) whitelistArray.add(name);
            global.add("shaderWhitelist", whitelistArray);
            root.add("global", global);

            JsonObject tasks = new JsonObject();
            for (TaskDefinition def : TaskRegistry.getAll()) {
                String fullId = def.getFullId();
                TaskConfigEntry entry = taskConfigs.getOrDefault(fullId, new TaskConfigEntry(true));
                tasks.add(fullId, entry.toJson());
            }
            root.add("tasks", tasks);

            JsonObject gameModes = new JsonObject();
            for (Map.Entry<String, GameModeConfigScope> e : gameModeConfigs.entrySet()) {
                gameModes.add(e.getKey(), e.getValue().toJson());
            }
            root.add("gameModes", gameModes);

            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.error("保存配置失败", e);
        }

        if (!suppressCallback && onSaveCallback != null) {
            try { onSaveCallback.run(); } catch (Exception e) {
                LOGGER.error("保存回调执行失败", e);
            }
        }
    }

    private void createDefaultConfig() {
        taskConfigs.clear();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            taskConfigs.put(def.getFullId(), new TaskConfigEntry(true));
        }
        dlcProbabilityTarget = 0.5f;
    }

    // ========================================================================
    //  Query
    // ========================================================================

    public TaskConfigEntry getTaskConfig(String fullId) {
        return taskConfigs.get(fullId);
    }

    public void setTaskConfig(String fullId, TaskConfigEntry entry) {
        taskConfigs.put(fullId, entry);
        save();
    }

    public Map<String, TaskConfigEntry> getAllConfigs() {
        return taskConfigs;
    }

    public void setAllConfigs(Map<String, TaskConfigEntry> entries) {
        taskConfigs.putAll(entries);
        save();
    }

    // ========================================================================
    //  GameMode config
    // ========================================================================

    public GameModeConfigScope getGameModeConfig(String gameModeId) {
        return gameModeConfigs.computeIfAbsent(gameModeId, GameModeConfigScope::new);
    }

    public Map<String, GameModeConfigScope> getAllGameModeConfigs() {
        return gameModeConfigs;
    }

    // ========================================================================
    //  Statistics
    // ========================================================================

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

    // ========================================================================
    //  Auto-balance
    // ========================================================================

    public float getDlcWeightBoost() {
        long dlcCount = countDlcTasks();
        long origCount = countOriginalTasks();
        float boost = TaskBalancer.calcBoost(dlcProbabilityTarget, dlcCount, origCount);

        if (dlcCount > 0) {
            float dlcTotal = boost * dlcCount;
            float pct = (dlcTotal + origCount) > 0 ? dlcTotal / (dlcTotal + origCount) : 0;
            LOGGER.info("[DLC概率] 目标={}%, {}个DLC vs {}个原版 → autoBoost={} (实际概率≈{}%)",
                    Math.round(dlcProbabilityTarget * 100), dlcCount, origCount,
                    String.format("%.2f", boost), Math.round(pct * 100));
        }
        return boost;
    }

    private float calculateCurrentBoost() {
        return TaskBalancer.calcBoost(dlcProbabilityTarget, countDlcTasks(), countOriginalTasks());
    }

    public float getDlcProbabilityTarget() { return dlcProbabilityTarget; }

    public void setDlcProbabilityTarget(float target) {
        this.dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8, target));
        save();
    }

    // ========================================================================
    //  Shader whitelist
    // ========================================================================

    public boolean isShaderWhitelistEnabled() { return shaderWhitelistEnabled; }

    public void setShaderWhitelistEnabled(boolean enabled) {
        this.shaderWhitelistEnabled = enabled;
        save();
    }

    public List<String> getShaderWhitelist() { return shaderWhitelist; }

    public void setShaderWhitelist(List<String> list) {
        this.shaderWhitelist.clear();
        this.shaderWhitelist.addAll(list);
        save();
    }

    public void setShaderWhitelistConfig(boolean enabled, List<String> list) {
        this.shaderWhitelistEnabled = enabled;
        this.shaderWhitelist.clear();
        this.shaderWhitelist.addAll(list);
        save();
    }

    public void applyShaderWhitelistSync(boolean enabled, List<String> list) {
        this.shaderWhitelistEnabled = enabled;
        this.shaderWhitelist.clear();
        this.shaderWhitelist.addAll(list);
    }

    // ========================================================================
    //  Network sync
    // ========================================================================

    public String toJsonString() {
        JsonObject root = new JsonObject();

        JsonObject global = new JsonObject();
        global.addProperty("dlcProbabilityTarget", dlcProbabilityTarget);
        global.addProperty("shaderWhitelistEnabled", shaderWhitelistEnabled);
        var whitelistArr = new com.google.gson.JsonArray();
        for (String name : shaderWhitelist) whitelistArr.add(name);
        global.add("shaderWhitelist", whitelistArr);
        root.add("global", global);

        JsonObject tasks = new JsonObject();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            String fullId = def.getFullId();
            TaskConfigEntry entry = taskConfigs.getOrDefault(fullId, new TaskConfigEntry(true));
            tasks.add(fullId, entry.toJson());
        }
        root.add("tasks", tasks);

        JsonObject gameModes = new JsonObject();
        for (Map.Entry<String, GameModeConfigScope> e : gameModeConfigs.entrySet()) {
            gameModes.add(e.getKey(), e.getValue().toJson());
        }
        root.add("gameModes", gameModes);

        return gson.toJson(root);
    }

    public void loadFromJsonString(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");
                if (global.has("dlcProbabilityTarget")) {
                    dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble()));
                }
                if (global.has("shaderWhitelistEnabled")) {
                    shaderWhitelistEnabled = global.get("shaderWhitelistEnabled").getAsBoolean();
                }
                if (global.has("shaderWhitelist")) {
                    shaderWhitelist.clear();
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) shaderWhitelist.add(name);
                    }
                }
            }

            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                taskConfigs.clear();
                for (var entry : tasks.entrySet()) {
                    taskConfigs.put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("gameModes")) {
                JsonObject modes = root.getAsJsonObject("gameModes");
                for (var entry : modes.entrySet()) {
                    gameModeConfigs.put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串加载配置失败", e);
        }
    }

    public void applySyncData(Map<String, TaskConfigEntry> configs, float target) {
        suppressCallback = true;
        try {
            this.taskConfigs.clear();
            this.taskConfigs.putAll(configs);
            this.dlcProbabilityTarget = target;
            save();
        } finally {
            suppressCallback = false;
        }
    }

    public void applySyncFromJson(String json) {
        suppressCallback = true;
        try {
            loadFromJsonString(json);
            save();
        } finally {
            suppressCallback = false;
        }
    }

    public File getConfigFile() { return configFile; }
}
