package com.habitrain.core.client.util;

import com.habitrain.core.game.sre.SRETrainTaskWrapper;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Normalizes task labels so the UI can keep real translation keys while
 * stripping stray "task." prefixes from raw names and custom tasks.
 */
public final class TaskTextNormalizer {
    private TaskTextNormalizer() {
    }

    public static Component normalizeTaskTitle(SREPlayerTaskComponent.TrainTask task) {
        if (task == null) {
            return Component.empty();
        }

        // DLC 任务包装器：直接用 displayName 字面量，不依赖 getType()==CUSTOM。
        // 包装器挂在 CUSTOM 槽位，若不在此拦截会走 SRE 原版 task.* 翻译产生错误文本。
        if (task instanceof SRETrainTaskWrapper) {
            String name = task.getName();
            if (name == null || name.isBlank()) {
                return Component.empty();
            }
            return Component.literal(name);
        }

        String name = task.getName();
        if (name == null || name.isBlank()) {
            return Component.empty();
        }

        if (task.getType() == SREPlayerTaskComponent.Task.CUSTOM) {
            return Component.literal(name);
        }

        return normalizeTaskKey(name.startsWith("task.") ? name : "task." + name);
    }

    public static Component normalizeTaskComponent(Component component) {
        if (component == null) {
            return Component.empty();
        }

        if (!(component.getContents() instanceof TranslatableContents translatable)) {
            return component;
        }

        return normalizeTaskKey(translatable.getKey(), translatable.getArgs());
    }

    private static Component normalizeTaskKey(String key) {
        return normalizeTaskKey(key, null);
    }

    private static Component normalizeTaskKey(String key, @org.jetbrains.annotations.Nullable Object[] args) {
        if (key == null || key.isBlank()) {
            return Component.empty();
        }

        String normalized = key;
        if (normalized.startsWith("task.task.")) {
            normalized = normalized.substring("task.".length());
        }

        if (Language.getInstance().has(normalized)) {
            return args == null || args.length == 0
                    ? Component.translatable(normalized)
                    : Component.translatable(normalized, args);
        }

        if (normalized.startsWith("task.")) {
            String suffix = normalized.substring("task.".length());
            if (Language.getInstance().has(suffix)) {
                return args == null || args.length == 0
                        ? Component.translatable(suffix)
                        : Component.translatable(suffix, args);
            }
            return Component.literal(suffix);
        }

        return Component.literal(normalized);
    }
}
