package com.habitrain.core.client.cache;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;

/**
 * 客户端活跃自定义任务缓存
 *
 * 从服务端同步的活跃自定义任务信息存储在此。
 * 在多人模式下，服务端通过 {@link com.habitrain.core.network.ActiveTaskPayload}
 * 将当前玩家的活跃 DLC 任务发送到客户端。
 *
 * 此缓存替代了 HabiTaskManager.getActiveCustomTask() 在多人模式下的缺失，
 * 因为 HabiTaskManager 是服务端单例，客户端没有该数据。
 *
 * 支持的查询：
 * - 活跃任务 ID
 * - 对应方块类型 ID (blockTypeId)
 * - 配置颜色
 * - 描边粗细
 */
public class ActiveTaskCache {

    /** 当前活跃的自定义任务完整 ID，null = 无活跃任务 */
    @Nullable
    private static volatile String activeTaskFullId = null;

    /**
     * 当前活跃的杀手假任务完整 ID，null = 无假任务。
     * 杀手双任务机制：杀手的"假任务"来自好人任务池，单独追踪。
     */
    @Nullable
    private static volatile String fakeTaskFullId = null;

    private ActiveTaskCache() {}

    // ========================================================================
    //  真实任务缓存
    // ========================================================================

    /**
     * 设置活跃自定义任务 ID
     * @param taskFullId 任务完整 ID，null 或空字符串表示清空
     */
    public static void setActiveTask(@Nullable String taskFullId) {
        activeTaskFullId = (taskFullId != null && !taskFullId.isEmpty()) ? taskFullId : null;
        HabiTrainCore.LOGGER.info("[ActiveTaskCache] 设置活跃任务: {}",
                activeTaskFullId != null ? activeTaskFullId : "(无)");
    }

    /**
     * 获取活跃自定义任务 ID
     * @return 任务完整 ID，null 表示无活跃任务
     */
    @Nullable
    public static String getActiveTaskFullId() {
        return activeTaskFullId;
    }

    /**
     * 是否有活跃的自定义任务
     */
    public static boolean hasActiveTask() {
        return activeTaskFullId != null;
    }

    // ========================================================================
    //  假任务缓存（杀手双任务机制）
    // ========================================================================

    /**
     * 设置杀手假任务 ID
     * @param taskFullId 假任务完整 ID，null 或空字符串表示清空
     */
    public static void setFakeTask(@Nullable String taskFullId) {
        fakeTaskFullId = (taskFullId != null && !taskFullId.isEmpty()) ? taskFullId : null;
        HabiTrainCore.LOGGER.info("[ActiveTaskCache] 设置假任务: {}",
                fakeTaskFullId != null ? fakeTaskFullId : "(无)");
    }

    /**
     * 获取杀手假任务 ID
     * @return 假任务完整 ID，null 表示无假任务
     */
    @Nullable
    public static String getFakeTaskFullId() {
        return fakeTaskFullId;
    }

    /**
     * 判断指定任务完整 ID 是否为当前假任务
     * @param taskFullId 任务完整 ID
     * @return true 如果是假任务
     */
    public static boolean isFakeTask(@Nullable String taskFullId) {
        return taskFullId != null && taskFullId.equals(fakeTaskFullId);
    }

    /**
     * 清空所有缓存（真实任务 + 假任务）
     */
    public static void clear() {
        activeTaskFullId = null;
        fakeTaskFullId = null;
    }

    /**
     * 清空假任务缓存
     */
    public static void clearFakeTask() {
        fakeTaskFullId = null;
    }

    // ========================================================================
    //  便捷查询 — 从缓存的任务 ID 推导所有渲染需要的信息
    // ========================================================================

    /**
     * 获取活跃任务的方块类型 ID
     * @return blockTypeId，如果无活跃任务或任务未注册则返回 -1
     */
    public static int getBlockTypeId() {
        if (activeTaskFullId == null) return -1;
        TaskDefinition def = TaskRegistry.get(activeTaskFullId);
        return def != null ? def.getBlockTypeId() : -1;
    }

    /**
     * 获取活跃任务的配置（颜色、描边粗细等）
     * @return 配置项，如果无活跃任务则返回 null
     */
    @Nullable
    public static TaskConfigEntry getConfig() {
        if (activeTaskFullId == null) return null;
        return ConfigManager.getInstance().getTaskConfig(activeTaskFullId);
    }

    /**
     * 获取活跃任务的透视颜色
     * 优先使用 ModMenu 配置颜色，其次使用任务定义默认颜色
     */
    public static Color getColor() {
        if (activeTaskFullId == null) return new Color(200, 200, 200, 180);

        TaskConfigEntry cfg = getConfig();
        if (cfg != null) {
            return new Color(cfg.getColor(), true);
        }

        TaskDefinition def = TaskRegistry.get(activeTaskFullId);
        if (def != null) {
            return new Color(def.getInstinctColorRGB(), true);
        }

        return new Color(200, 200, 200, 180);
    }

    /**
     * 获取活跃任务的描边粗细
     * @return 描边粗细值，默认 4.0
     */
    public static float getOutlineWidth() {
        if (activeTaskFullId == null) return 4.0f;

        TaskConfigEntry cfg = getConfig();
        if (cfg != null) {
            return cfg.outlineWidth;
        }

        return 4.0f;
    }
}
