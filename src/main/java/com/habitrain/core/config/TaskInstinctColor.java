package com.habitrain.core.config;

import com.habitrain.core.api.TaskDefinition;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the ESP / instinct overlay color for a task.
 *
 * <p>Mod Menu may persist a per-task override. Missing overrides and legacy
 * default-gray values must not stomp {@link TaskDefinition#getInstinctColorRGB()}.
 */
public final class TaskInstinctColor {

    private TaskInstinctColor() {}

    public static int resolveArgb(@Nullable TaskConfigEntry cfg, int definitionArgb) {
        if (cfg == null || !cfg.hasInstinctColor) {
            return definitionArgb;
        }
        if (cfg.instinctColorFromLegacyJson
                && cfg.instinctColor == TaskConfigEntry.DEFAULT_INSTINCT_COLOR
                && definitionArgb != TaskConfigEntry.DEFAULT_INSTINCT_COLOR) {
            return definitionArgb;
        }
        return cfg.instinctColor;
    }

    public static int resolveArgb(@Nullable TaskConfigEntry cfg, @Nullable TaskDefinition def) {
        int definition = def != null
                ? def.getInstinctColorRGB()
                : TaskConfigEntry.DEFAULT_INSTINCT_COLOR;
        return resolveArgb(cfg, definition);
    }

    public static TaskConfigEntry seedFromDefinition(int definitionArgb) {
        TaskConfigEntry entry = TaskConfigEntry.createDefault();
        entry.instinctColor = definitionArgb;
        entry.hasInstinctColor = false;
        entry.instinctColorFromLegacyJson = false;
        return entry;
    }

    public static TaskConfigEntry seedFromDefinition(@Nullable TaskDefinition def) {
        return seedFromDefinition(def != null
                ? def.getInstinctColorRGB()
                : TaskConfigEntry.DEFAULT_INSTINCT_COLOR);
    }

    /**
     * After loading a pre-{@code hasInstinctColor} JSON entry, rewrite flags so the
     * editor swatch matches {@link #resolveArgb} and later saves do not persist a
     * phantom gray override.
     */
    public static void normalizeLoadedEntry(@Nullable TaskConfigEntry cfg, int definitionArgb) {
        if (cfg == null || !cfg.instinctColorFromLegacyJson) {
            return;
        }
        int resolved = resolveArgb(cfg, definitionArgb);
        cfg.instinctColor = resolved;
        cfg.hasInstinctColor = resolved != definitionArgb;
        cfg.instinctColorFromLegacyJson = false;
    }

    public static void normalizeLoadedEntry(@Nullable TaskConfigEntry cfg, @Nullable TaskDefinition def) {
        normalizeLoadedEntry(cfg, def != null
                ? def.getInstinctColorRGB()
                : TaskConfigEntry.DEFAULT_INSTINCT_COLOR);
    }
}
