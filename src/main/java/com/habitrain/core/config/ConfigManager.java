package com.habitrain.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.task.TaskBalancer;
import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import io.wifi.starrailexpress.content.minigame.QuestMinigames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

    private int sheriffCountDivisor = 6;

    // 小游戏配置（叠加在 SRE QuestMinigames 之上）
    private final Map<String, MinigameConfigEntry> minigameConfigs = new HashMap<>();
    private boolean minigameGlobalEnabled = true;

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
        minigameConfigs.clear();
        dlcProbabilityTarget = 0.5f;

        if (!configFile.exists()) {
            createDefaultConfig();
            save();
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
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

                if (global.has("sheriffCountDivisor")) {
                    int div = global.get("sheriffCountDivisor").getAsInt();
                    if (div > 0) sheriffCountDivisor = div;
                }

                LOGGER.info("全局设置: DLC目标占比={}%, 光影白名单={}, 允许{}个光影, 警长除数={}",
                        Math.round(dlcProbabilityTarget * 100),
                        shaderWhitelistEnabled ? "启用" : "禁用",
                        shaderWhitelist.size(),
                        sheriffCountDivisor);
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
                gameModeConfigs.clear();
                for (var entry : modes.entrySet()) {
                    gameModeConfigs.put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("minigames")) {
                JsonObject mg = root.getAsJsonObject("minigames");
                minigameConfigs.clear();
                if (mg.has("globalEnabled")) {
                    minigameGlobalEnabled = mg.get("globalEnabled").getAsBoolean();
                }
                if (mg.has("entries")) {
                    JsonObject entries = mg.getAsJsonObject("entries");
                    for (var e : entries.entrySet()) {
                        minigameConfigs.put(e.getKey(),
                                MinigameConfigEntry.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }

            LOGGER.info("任务配置已加载: {} 个任务, {} 个GameMode, {} 个小游戏", taskConfigs.size(), gameModeConfigs.size());
        } catch (Exception e) {
            LOGGER.error("加载任务配置失败，重建默认配置并覆盖损坏的配置文件", e);
            createDefaultConfig();
            // createDefaultConfig 只重建 taskConfigs，需补齐其余字段，否则内存残留旧 gameModeConfigs/shader 数据
            gameModeConfigs.clear();
            shaderWhitelistEnabled = false;
            shaderWhitelist.clear();
            minigameConfigs.clear();
            minigameGlobalEnabled = true;
            save();
        }
    }

    public void save() {
        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

            JsonObject root = buildJsonRoot(true);

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
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

    private JsonObject buildJsonRoot(boolean includeWeightBoost) {
        JsonObject root = new JsonObject();

        JsonObject global = new JsonObject();
        global.addProperty("dlcProbabilityTarget", dlcProbabilityTarget);
        if (includeWeightBoost) {
            global.addProperty("dlcWeightBoost", calculateCurrentBoost());
        }
        global.addProperty("shaderWhitelistEnabled", shaderWhitelistEnabled);
        var whitelistArray = new com.google.gson.JsonArray();
        for (String name : shaderWhitelist) whitelistArray.add(name);
        global.add("shaderWhitelist", whitelistArray);
        global.addProperty("sheriffCountDivisor", sheriffCountDivisor);
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

        JsonObject minigames = new JsonObject();
        minigames.addProperty("globalEnabled", minigameGlobalEnabled);
        JsonObject mgEntries = new JsonObject();
        for (QuestMinigame mg : safeGetAllMinigames()) {
            String id = mg.id();
            MinigameConfigEntry entry = minigameConfigs.getOrDefault(id, MinigameConfigEntry.createDefault());
            mgEntries.add(id, entry.toJson());
        }
        minigames.add("entries", mgEntries);
        root.add("minigames", minigames);

        return root;
    }

    private void createDefaultConfig() {
        taskConfigs.clear();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            taskConfigs.put(def.getFullId(), new TaskConfigEntry(true));
        }
        dlcProbabilityTarget = 0.5f;
        minigameConfigs.clear();
        for (QuestMinigame mg : safeGetAllMinigames()) {
            minigameConfigs.put(mg.id(), MinigameConfigEntry.createDefault());
        }
        minigameGlobalEnabled = true;
    }

    /** 安全获取 SRE 小游戏列表，SRE 未安装时返回空列表。 */
    private List<QuestMinigame> safeGetAllMinigames() {
        try {
            return QuestMinigames.getAll();
        } catch (Throwable t) {
            LOGGER.warn("无法读取 QuestMinigames.getAll()，SRE 可能未安装", t);
            return List.of();
        }
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

    /**
     * 仅写入内存配置表，不触发 save() 与同步回调。
     * 供 GUI 进行 draft 编辑（如颜色/描边逐次点击）时使用，避免每次点击都发包；
     * 最终统一由显式 {@link #save()} 持久化。
     */
    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        taskConfigs.put(fullId, entry);
    }

    public Map<String, TaskConfigEntry> getAllConfigs() {
        // 返回不可变视图，强制调用方走 setTaskConfig/putTaskConfig，
        // 避免绕过 save() 直接改内存导致不同步/不落盘。
        return Collections.unmodifiableMap(taskConfigs);
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
        return gson.toJson(buildJsonRoot(false));
    }

    public void loadFromJsonString(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // 先解析到临时变量，全部成功后再原子提交到成员字段。
            // 避免解析中途失败导致内存处于"部分字段已清空+部分已加载"的不一致状态，
            // 进而防止调用方紧接着 save() 把损坏状态写盘造成配置永久丢失。
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

            // 原子提交
            taskConfigs.clear();
            taskConfigs.putAll(newTasks);
            gameModeConfigs.clear();
            gameModeConfigs.putAll(newModes);
            minigameConfigs.clear();
            minigameConfigs.putAll(newMinigames);
            dlcProbabilityTarget = newDlcTarget;
            shaderWhitelistEnabled = newShaderEnabled;
            shaderWhitelist.clear();
            shaderWhitelist.addAll(newShaderWhitelist);
            sheriffCountDivisor = newSheriffDivisor;
            minigameGlobalEnabled = newMgGlobal;
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串加载配置失败，保持原有内存状态不变", e);
        }
    }

    public void applySyncData(Map<String, TaskConfigEntry> configs, float target) {
        suppressCallback = true;
        try {
            this.taskConfigs.clear();
            this.taskConfigs.putAll(configs);
            this.dlcProbabilityTarget = target;
            // 不调用 save()：applySyncData 仅同步任务配置与目标占比，
            // 客户端的 shader/minigame/gameMode 等其它配置由各自 payload 单独同步，
            // 此处落盘会用残缺内存覆盖客户端本地完整配置。
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

    public int getSheriffCountDivisor() { return sheriffCountDivisor; }

    public void setSheriffCountDivisor(int divisor) {
        this.sheriffCountDivisor = Math.max(1, divisor);
        save();
    }

    // ========================================================================
    //  Minigame config
    // ========================================================================

    public MinigameConfigEntry getMinigameConfig(String minigameId) {
        return minigameConfigs.get(minigameId);
    }

    public MinigameConfigEntry getOrCreateMinigameConfig(String minigameId) {
        return minigameConfigs.computeIfAbsent(minigameId, k -> MinigameConfigEntry.createDefault());
    }

    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        minigameConfigs.put(minigameId, entry);
        save();
    }

    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        minigameConfigs.put(minigameId, entry);
    }

    public Map<String, MinigameConfigEntry> getAllMinigameConfigs() {
        return minigameConfigs;
    }

    public boolean isMinigameGlobalEnabled() { return minigameGlobalEnabled; }

    public void setMinigameGlobalEnabled(boolean enabled) {
        this.minigameGlobalEnabled = enabled;
        save();
    }

    /** 小游戏在指定地图是否启用（总开关 + 单小游戏 enabled + 地图过滤）。 */
    public boolean isMinigameEnabledForMap(String minigameId, String mapName) {
        if (!minigameGlobalEnabled) return false;
        MinigameConfigEntry entry = minigameConfigs.get(minigameId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        return entry.isAllowedOnMap(mapName);
    }

    /**
     * 将小游戏配置强制写入 SRE 的 {@code AreasWorldComponent}（可用集合 + 总开关）。
     * 在服务端启动 / 玩家加入 / OP 保存配置后调用。无 SRE 时静默跳过。
     */
    public void applyMinigameEnforcement(@Nullable MinecraftServer server) {
        if (server == null) return;
        try {
            for (var level : server.getAllLevels()) {
                var areas = io.wifi.starrailexpress.cca.AreasWorldComponent.KEY.get(level);
                if (areas == null) continue;
                areas.minigameQuestEnabled = minigameGlobalEnabled;
                HashSet<String> available = areas.availableMinigameIds;
                available.clear();
                String mapName = areas.mapName != null ? areas.mapName : "";
                for (QuestMinigame mg : safeGetAllMinigames()) {
                    MinigameConfigEntry entry = minigameConfigs.get(mg.id());
                    if (entry == null || (entry.enabled && entry.isAllowedOnMap(mapName))) {
                        available.add(mg.id());
                    }
                }
                areas.sync();
            }
            LOGGER.info("小游戏配置已强制应用: global={}，可用 {} 个", minigameGlobalEnabled, minigameConfigs.size());
        } catch (Throwable t) {
            LOGGER.warn("applyMinigameEnforcement 失败，SRE 可能未安装", t);
        }
    }
}
