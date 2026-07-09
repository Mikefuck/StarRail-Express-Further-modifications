package com.habitrain.core.client;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class InstinctColorHelper {
    private static final Map<Integer, Color> overrideColors = new HashMap<>();
    private static volatile boolean overrideColorsDirty = true;
    // 配置颜色版本号：每次 markDirty 自增。供多个独立缓存（如 CustomTaskBlockRendererMixin）
    // 通过对比版本号判断是否需要重建，避免相互清脏导致的竞态。
    private static volatile int colorVersion = 0;

    public static void markDirty() {
        overrideColorsDirty = true;
        colorVersion++;
    }

    public static Map<Integer, Color> getOverrideColors() {
        return Collections.unmodifiableMap(overrideColors);
    }

    public static boolean isDirty() {
        return overrideColorsDirty;
    }

    public static int getColorVersion() {
        return colorVersion;
    }

    public static void rebuildOverrides() {
        overrideColorsDirty = false;
        overrideColors.clear();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            int bt = def.getBlockTypeId();
            if (bt < 1) continue;

            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            if (cfg != null) {
                overrideColors.put(bt, new Color(cfg.getColor(), true));
            }
        }
    }
}
