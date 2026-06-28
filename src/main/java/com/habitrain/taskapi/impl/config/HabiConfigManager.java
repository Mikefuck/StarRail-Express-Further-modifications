package com.habitrain.taskapi.impl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理器 - 管理任务配置的加载和保存
 *
 * 配置文件结构:
 * {
 *   "global": {
 *     "dlcProbabilityTarget": 0.5    ← 唯一控制参数：DLC目标占比
 *     "dlcWeightBoost": 3.0           ← 自动计算出的权重乘数（仅参考）
 *   },
 *   "tasks": {
 *     "habitrain_taskapi:sleep": { ... },
 *     "test_more_tasks:pet_cat": { ... }
 *   }
 * }
 *
 * ★ 核心设计：
 *   你只需要设一个值 —— DLC任务的出现概率目标（0.1~0.8，默认0.5）
 *   系统自动根据当前注册的 DLC 任务数量计算需要的权重乘数，
 *   无论以后加了多少 DLC 任务，实际概率始终稳定在你设定的目标附近。
 *
 *   计算公式: boost = (target / (1-target)) × (originalCount / dlcCount)
 *   例  target=0.5, orig=11, dlc=4  → 2.75
 *   例  target=0.5, orig=11, dlc=22 → 0.50
 *   例  target=0.3, orig=11, dlc=4  → 1.18
 */
public class HabiConfigManager {
    private static HabiConfigManager INSTANCE;
    private final File configFile;
    private final Map<String, HabiTaskConfigEntry> taskConfigs = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ====== 唯一的用户控制参数 ======
    /** ★ DLC目标占比 (0.1~0.8，默认0.5=50%)
     *  唯一需要用户调节的值，控制DLC任务在全部任务中的出现频率。
     *  系统自动根据任务数量计算权重乘数，新增DLC任务后无需重新调整。 */
    private float dlcProbabilityTarget = 0.5f;

    // ====== 自动录制回放设置 ======
    /** ★ 是否在游戏开始/结束时自动录制回放（需要 ServerReplay 模组） */
    private boolean autoReplayRecording = true;

    public boolean isAutoReplayRecording() {
        return autoReplayRecording;
    }

    public void setAutoReplayRecording(boolean enabled) {
        this.autoReplayRecording = enabled;
        save();
    }

    // ====== Iris 光影白名单设置 ======
    /** 是否启用光影白名单 */
    private boolean shaderWhitelistEnabled = false;
    /** 允许的光影包名称列表（文件夹名或zip文件名） */
    private final List<String> shaderWhitelist = new ArrayList<>();

    public boolean isShaderWhitelistEnabled() {
        return shaderWhitelistEnabled;
    }

    public void setShaderWhitelistEnabled(boolean enabled) {
        this.shaderWhitelistEnabled = enabled;
        save();
    }

    public List<String> getShaderWhitelist() {
        return shaderWhitelist;
    }

    public void setShaderWhitelist(List<String> list) {
        this.shaderWhitelist.clear();
        this.shaderWhitelist.addAll(list);
        save();
    }

    /**
     * 批量设置白名单配置（调用一次 save）
     * 用于 ShaderWhitelistScreen 中的批量更新
     */
    public void setShaderWhitelistConfig(boolean enabled, List<String> list) {
        this.shaderWhitelistEnabled = enabled;
        this.shaderWhitelist.clear();
        this.shaderWhitelist.addAll(list);
        save();
    }

    /**
     * 从服务端同步白名单配置（不触发保存回调，防止 C2S 回环）
     * 用于 S2C 数据包接收
     */
    public void applyShaderWhitelistSync(boolean enabled, List<String> list) {
        this.shaderWhitelistEnabled = enabled;
        this.shaderWhitelist.clear();
        this.shaderWhitelist.addAll(list);
    }

    // ====== 保存回调（客户端使用：保存后自动同步到服务端） ======
    @Nullable
    private static Runnable onSaveCallback = null;

    /** 是否抑制回调（用于从服务端同步时避免回环） */
    private boolean suppressCallback = false;

    /**
     * 设置保存回调（客户端专用：每次保存配置后自动发送到服务端）
     * @param callback 回调，null=清除
     */
    public static void setOnSaveCallback(@Nullable Runnable callback) {
        onSaveCallback = callback;
    }

    private HabiConfigManager() {
        this.configFile = new File(
                FabricLoader.getInstance().getConfigDir().toFile(),
                "habitrain_taskapi.json"
        );
    }

    public static HabiConfigManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new HabiConfigManager();
        }
        return INSTANCE;
    }

    // ========================================================================
    //  配置文件 IO
    // ========================================================================

    /**
     * 加载配置
     */
    public void load() {
        taskConfigs.clear();
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

                // ===== 读取 dlcProbabilityTarget（唯一用户参数） =====
                if (global.has("dlcProbabilityTarget")) {
                    dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble()));
                } else if (global.has("dlcWeightBoost")) {
                    // ★ 向后兼容：从旧的 dlcWeightBoost 反推目标占比
                    float oldBoost = Math.max(0f, global.get("dlcWeightBoost").getAsFloat());
                    long dlcCount = countDlcTasks();
                    long origCount = countOriginalTasks();
                    if (dlcCount > 0 && origCount > 0) {
                        // target = boost × dlcCount / (boost × dlcCount + origCount)
                        float total = oldBoost * dlcCount + origCount;
                        dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8,
                                oldBoost * dlcCount / total));
                    }
                    HabiTrainTaskAPI.LOGGER.info("从旧配置 dlcWeightBoost={} 反推目标占比={}%",
                            oldBoost, Math.round(dlcProbabilityTarget * 100));
                }

                // ===== 读取 Iris 光影白名单 =====
                if (global.has("shaderWhitelistEnabled")) {
                    shaderWhitelistEnabled = global.get("shaderWhitelistEnabled").getAsBoolean();
                }
                if (global.has("shaderWhitelist")) {
                    shaderWhitelist.clear();
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) {
                            shaderWhitelist.add(name);
                        }
                    }
                }

                // ===== 读取自动录制回放设置 =====
                if (global.has("autoReplayRecording")) {
                    autoReplayRecording = global.get("autoReplayRecording").getAsBoolean();
                }

                HabiTrainTaskAPI.LOGGER.info("全局设置: DLC目标占比={}%, 光影白名单={}, 允许{}个光影, 自动录制={}",
                        Math.round(dlcProbabilityTarget * 100),
                        shaderWhitelistEnabled ? "启用" : "禁用",
                        shaderWhitelist.size(),
                        autoReplayRecording ? "启用" : "禁用");
            }

            // ===== 加载每个任务的配置 =====
            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                for (var entry : tasks.entrySet()) {
                    String taskId = entry.getKey();
                    JsonObject taskConfig = entry.getValue().getAsJsonObject();
                    taskConfigs.put(taskId, HabiTaskConfigEntry.fromJson(taskConfig));
                }
            }
            HabiTrainTaskAPI.LOGGER.info("任务配置已加载: {} 个任务配置", taskConfigs.size());
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("加载任务配置失败，使用默认配置", e);
            createDefaultConfig();
        }
    }

    /**
     * 保存配置
     */
    public void save() {
        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

            JsonObject root = new JsonObject();

            // ===== 保存全局设置 =====
            JsonObject global = new JsonObject();
            global.addProperty("dlcProbabilityTarget", dlcProbabilityTarget);
            // 同时保存计算出的 boost 值，方便用户查看和调试
            global.addProperty("dlcWeightBoost", calculateCurrentBoost());

            // 自动录制回放
            global.addProperty("autoReplayRecording", autoReplayRecording);

            // Iris光影白名单
            global.addProperty("shaderWhitelistEnabled", shaderWhitelistEnabled);
            var whitelistArray = new com.google.gson.JsonArray();
            for (String name : shaderWhitelist) {
                whitelistArray.add(name);
            }
            global.add("shaderWhitelist", whitelistArray);

            root.add("global", global);

            // ===== 保存每个任务的配置 =====
            JsonObject tasks = new JsonObject();
            for (HabiTaskDefinition def : HabiTaskRegistry.getAll()) {
                String fullId = def.getFullId();
                HabiTaskConfigEntry entry = taskConfigs.getOrDefault(fullId, new HabiTaskConfigEntry(true));
                tasks.add(fullId, entry.toJson());
            }
            root.add("tasks", tasks);

            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(root, writer);
            }
        } catch (IOException e) {
            HabiTrainTaskAPI.LOGGER.error("保存任务配置失败", e);
        }

        // ★ 触发保存回调（客户端用——自动同步到服务端）
        if (!suppressCallback && onSaveCallback != null) {
            try {
                onSaveCallback.run();
            } catch (Exception e) {
                HabiTrainTaskAPI.LOGGER.error("保存回调执行失败", e);
            }
        }
    }

    /**
     * 创建默认配置（所有任务启用）
     */
    private void createDefaultConfig() {
        taskConfigs.clear();
        for (HabiTaskDefinition def : HabiTaskRegistry.getAll()) {
            taskConfigs.put(def.getFullId(), new HabiTaskConfigEntry(true));
        }
        dlcProbabilityTarget = 0.5f;
    }

    // ========================================================================
    //  任务配置查询
    // ========================================================================

    public HabiTaskConfigEntry getTaskConfig(String fullId) {
        return taskConfigs.get(fullId);
    }

    public void setTaskConfig(String fullId, HabiTaskConfigEntry entry) {
        taskConfigs.put(fullId, entry);
        save();
    }

    public Map<String, HabiTaskConfigEntry> getAllConfigs() {
        return taskConfigs;
    }

    public void setAllConfigs(Map<String, HabiTaskConfigEntry> entries) {
        taskConfigs.putAll(entries);
        save();
    }

    // ========================================================================
    //  统计方法
    // ========================================================================

    /** 统计注册的 DLC 任务数量（排除 habitrain_taskapi 内置任务） */
    public long countDlcTasks() {
        return HabiTaskRegistry.getAll().stream()
                .filter(t -> !"habitrain_taskapi".equals(t.getModId()))
                .count();
    }

    /** 统计注册的原版任务数量（仅 habitrain_taskapi 内置任务） */
    public long countOriginalTasks() {
        return HabiTaskRegistry.getAll().stream()
                .filter(t -> "habitrain_taskapi".equals(t.getModId()))
                .count();
    }

    // ========================================================================
    //  ★ 核心：自动权重计算
    // ========================================================================

    /**
     * ★ 根据当前 DLC 目标占比和注册的任务数量，自动计算所需的权重乘数
     *
     * 公式: boost = target / (1-target) × originalCount / dlcCount
     * 推导: 保证 DLC集体权重 / (DLC集体权重 + 原版集体权重) = target
     *
     * @param target 目标占比 (0.1~0.8)
     * @param dlcCount DLC任务数
     * @param origCount 原版任务数
     * @return 权重乘数 (钳制在 0~10)
     */
    public static float calcBoost(float target, long dlcCount, long origCount) {
        if (dlcCount <= 0 || origCount <= 0) return 1.0f;
        if (target <= 0f) return 0f;
        if (target >= 0.85f) return 10f; // 上限保护
        float boost = (target / (1f - target)) * ((float) origCount / (float) dlcCount);
        return Math.max(0.0f, Math.min(10.0f, boost));
    }

    /**
     * ★ 获取当前生效的权重乘数（每次调用根据最新注册数量动态计算）
     * 这是任务选择引擎实际使用的值。
     */
    public float getDlcWeightBoost() {
        long dlcCount = countDlcTasks();
        long origCount = countOriginalTasks();
        float boost = calcBoost(dlcProbabilityTarget, dlcCount, origCount);

        if (dlcCount > 0) {
            float dlcPct = calcDlcPercent(boost, dlcCount, origCount);
            HabiTrainTaskAPI.LOGGER.info(
                "[DLC概率] 目标={}%, {}个DLC vs {}个原版 → autoBoost={} (实际概率≈{}%)",
                Math.round(dlcProbabilityTarget * 100), dlcCount, origCount,
                String.format("%.2f", boost), Math.round(dlcPct * 100));
        }
        return boost;
    }

    /**
     * 计算当前已生效的 boost 值（不触发日志，供 save 使用）
     */
    private float calculateCurrentBoost() {
        return calcBoost(dlcProbabilityTarget, countDlcTasks(), countOriginalTasks());
    }

    /**
     * 计算 DLC 任务在扁平池中的集体占比
     */
    public static float calcDlcPercent(float boost, long dlcCount, long origCount) {
        float dlcTotal = boost * dlcCount;
        float origTotal = 1.0f * origCount;
        float grand = dlcTotal + origTotal;
        return grand > 0 ? dlcTotal / grand : 0;
    }

    // ========================================================================
    //  用户控制接口（唯二）
    // ========================================================================

    /** ★ 获取 DLC 目标占比（唯一用户参数） */
    public float getDlcProbabilityTarget() {
        return dlcProbabilityTarget;
    }

    /** ★ 设置 DLC 目标占比（唯一用户参数）
     *  @param target 0.1 ~ 0.8，表示DLC任务占全部任务的百分比 */
    public void setDlcProbabilityTarget(float target) {
        this.dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8, target));
        save();
    }

    // ========================================================================
    //  网络同步相关方法（C2S 配置同步）
    // ========================================================================

    /**
     * 将当前配置导出为 JSON 字符串
     * 用于 C2S 网络包传输给服务端
     */
    public String toJsonString() {
        JsonObject root = new JsonObject();

        // 全局设置
        JsonObject global = new JsonObject();
        global.addProperty("dlcProbabilityTarget", dlcProbabilityTarget);

        // 自动录制回放
        global.addProperty("autoReplayRecording", autoReplayRecording);

        // Iris 光影白名单
        global.addProperty("shaderWhitelistEnabled", shaderWhitelistEnabled);
        var whitelistArr = new com.google.gson.JsonArray();
        for (String name : shaderWhitelist) {
            whitelistArr.add(name);
        }
        global.add("shaderWhitelist", whitelistArr);

        root.add("global", global);

        // 任务配置
        JsonObject tasks = new JsonObject();
        for (HabiTaskDefinition def : HabiTaskRegistry.getAll()) {
            String fullId = def.getFullId();
            HabiTaskConfigEntry entry = taskConfigs.getOrDefault(fullId, new HabiTaskConfigEntry(true));
            tasks.add(fullId, entry.toJson());
        }
        root.add("tasks", tasks);

        return gson.toJson(root);
    }

    /**
     * 从 JSON 字符串加载配置（从 C2S 网络包接收后调用）
     * @param json toJsonString() 输出的 JSON 字符串
     */
    public void loadFromJsonString(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // 解析全局设置
            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");
                if (global.has("dlcProbabilityTarget")) {
                    dlcProbabilityTarget = (float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble()));
                }
                // 自动录制回放
                if (global.has("autoReplayRecording")) {
                    autoReplayRecording = global.get("autoReplayRecording").getAsBoolean();
                }

                // Iris 光影白名单
                if (global.has("shaderWhitelistEnabled")) {
                    shaderWhitelistEnabled = global.get("shaderWhitelistEnabled").getAsBoolean();
                }
                if (global.has("shaderWhitelist")) {
                    shaderWhitelist.clear();
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) {
                            shaderWhitelist.add(name);
                        }
                    }
                }
            }

            // 解析任务配置
            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                taskConfigs.clear();
                for (var entry : tasks.entrySet()) {
                    String taskId = entry.getKey();
                    JsonObject taskConfig = entry.getValue().getAsJsonObject();
                    taskConfigs.put(taskId, HabiTaskConfigEntry.fromJson(taskConfig));
                }
            }
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("从 JSON 字符串加载配置失败", e);
        }
    }

    /**
     * 从服务端同步的数据更新配置（应用广播时调用）
     * 与 loadFromJsonString 不同之处: 不触发 onSaveCallback，防止 C2S 回环
     */
    public void applySyncData(Map<String, HabiTaskConfigEntry> configs, float target) {
        suppressCallback = true;
        this.taskConfigs.clear();
        this.taskConfigs.putAll(configs);
        this.dlcProbabilityTarget = target;
        save(); // 保存到文件，但回调被抑制
        suppressCallback = false;
    }

    /**
     * 应用服务端广播的完整配置（从 JSON 字符串，不触发回调）
     */
    public void applySyncFromJson(String json) {
        suppressCallback = true;
        loadFromJsonString(json);
        save(); // 保存到文件，但回调被抑制
        suppressCallback = false;
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    public File getConfigFile() {
        return configFile;
    }
}
