package com.habitrain.taskapi.client;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;

/**
 * 客户端活跃自定义任务缓存
 *
 * 从服务端同步的活跃自定义任务信息存储在此。
 * 在多人模式下，服务端通过 {@link com.habitrain.taskapi.impl.network.ActiveCustomTaskPayload}
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
public class ActiveCustomTaskCache {

    /** 当前活跃的自定义任务完整 ID，null = 无活跃任务 */
    @Nullable
    private static String activeTaskFullId = null;

    private ActiveCustomTaskCache() {}

    /**
     * 设置活跃自定义任务 ID
     * @param taskFullId 任务完整 ID，null 或空字符串表示清空
     */
    public static void setActiveTask(@Nullable String taskFullId) {
        activeTaskFullId = (taskFullId != null && !taskFullId.isEmpty()) ? taskFullId : null;
        HabiTrainTaskAPI.LOGGER.info("[ActiveCustomTaskCache] 设置活跃任务: {}",
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

    /**
     * 清空活跃任务
     */
    public static void clear() {
        activeTaskFullId = null;
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
        HabiTaskDefinition def = HabiTaskRegistry.get(activeTaskFullId);
        return def != null ? def.getBlockTypeId() : -1;
    }

    /**
     * 获取活跃任务的配置（颜色、描边粗细等）
     * @return 配置项，如果无活跃任务则返回 null
     */
    @Nullable
    public static HabiTaskConfigEntry getConfig() {
        if (activeTaskFullId == null) return null;
        return HabiConfigManager.getInstance().getTaskConfig(activeTaskFullId);
    }

    /**
     * 获取活跃任务的透视颜色
     * 优先使用 ModMenu 配置颜色，其次使用任务定义默认颜色
     */
    public static Color getColor() {
        if (activeTaskFullId == null) return new Color(200, 200, 200, 180);

        HabiTaskConfigEntry cfg = getConfig();
        if (cfg != null) {
            return cfg.getColor();
        }

        HabiTaskDefinition def = HabiTaskRegistry.get(activeTaskFullId);
        if (def != null && def.getInstinctColor() != null) {
            return def.getInstinctColor();
        }

        return new Color(200, 200, 200, 180);
    }

    /**
     * 获取活跃任务的描边粗细
     * @return 描边粗细值，默认 4.0
     */
    public static float getOutlineWidth() {
        if (activeTaskFullId == null) return 4.0f;

        HabiTaskConfigEntry cfg = getConfig();
        if (cfg != null) {
            return cfg.outlineWidth;
        }

        return 4.0f;
    }
}
