package com.habitrain.core.client;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.config.TaskInstinctColor;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class InstinctColorHelper {
    private static volatile Map<Integer, Color> overrideColors = Map.of();
    private static volatile Map<Integer, Float> overrideOutlineWidths = Map.of();
    private static volatile boolean overrideColorsDirty = true;
    // 配置颜色版本号：每次 markDirty 自增。供多个独立缓存（如 CustomTaskBlockRendererMixin）
    // 通过对比版本号判断是否需要重建，避免相互清脏导致的竞态。
    private static final AtomicInteger COLOR_VERSION = new AtomicInteger(0);

    public static synchronized void markDirty() {
        overrideColorsDirty = true;
        COLOR_VERSION.incrementAndGet();
    }

    public static Map<Integer, Color> getOverrideColors() {
        if (overrideColorsDirty) {
            rebuildOverrides();
        }
        return overrideColors;
    }

    public static float getOverrideOutlineWidth(int blockTypeId, float defaultWidth) {
        if (overrideColorsDirty) {
            rebuildOverrides();
        }
        Float width = overrideOutlineWidths.get(blockTypeId);
        return width != null && width > 0.0f ? width : defaultWidth;
    }

    public static boolean isDirty() {
        return overrideColorsDirty;
    }

    public static int getColorVersion() {
        return COLOR_VERSION.get();
    }

    public static synchronized void rebuildOverrides() {
        Map<Integer, Color> nextColors = new HashMap<>();
        Map<Integer, Float> nextWidths = new HashMap<>();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            int bt = def.getBlockTypeId();
            if (bt < 1) continue;

            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            nextColors.put(bt, new Color(TaskInstinctColor.resolveArgb(cfg, def), true));
            if (cfg != null && cfg.outlineWidth > 0.0f) {
                nextWidths.put(bt, cfg.outlineWidth);
            }
        }
        overrideColors = Collections.unmodifiableMap(nextColors);
        overrideOutlineWidths = Collections.unmodifiableMap(nextWidths);
        overrideColorsDirty = false;
    }
}
