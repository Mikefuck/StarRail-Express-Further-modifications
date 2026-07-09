package com.habitrain.core.client.render;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.client.InstinctColorHelper;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * 构建 blockTypeId → Color 映射，供旁观/创造模式全量渲染使用。
 *
 * 缓存策略：通过 {@link InstinctColorHelper#getColorVersion()} 检测配置变更，
 * 仅在颜色配置确实变化时重建映射，避免每帧分配 HashMap 并全表扫描
 * {@link TaskRegistry#getAll()}。
 */
public final class TypeColorMapper {

    private static Map<Integer, Color> cachedTypeColorMap = null;
    private static int cachedColorVersion = -1;

    private TypeColorMapper() {}

    /**
     * 构建 blockTypeId → Color 的映射。
     *
     * <p>优先使用 ModMenu 配置颜色，其次使用任务定义默认颜色。
     *
     * @return 类型 ID 到颜色的不可变语义映射（调用方不应修改）
     */
    public static Map<Integer, Color> buildTypeColorMap() {
        if (cachedTypeColorMap != null && cachedColorVersion == InstinctColorHelper.getColorVersion()) {
            return cachedTypeColorMap;
        }
        Map<Integer, Color> map = new HashMap<>();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            int bt = def.getBlockTypeId();
            if (bt < 12) continue;

            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            if (cfg != null) {
                map.put(bt, new Color(cfg.getColor(), true));
            } else {
                map.put(bt, new Color(def.getInstinctColorRGB(), true));
            }
        }
        cachedTypeColorMap = map;
        cachedColorVersion = InstinctColorHelper.getColorVersion();
        return map;
    }
}
