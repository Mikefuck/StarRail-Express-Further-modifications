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
        // 杀手假任务用 Task.PRAY 槽位包装，getType() 返回 PRAY 而非 CUSTOM，
        // 若不在此拦截会命中 SRE 原版 task.pray 翻译（"祷告..."）产生错误文本。
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

        return normalizeTaskKey(translatable.getKey());
    }

    private static Component normalizeTaskKey(String key) {
        if (key == null || key.isBlank()) {
            return Component.empty();
        }

        String normalized = key;
        if (normalized.startsWith("task.task.")) {
            normalized = normalized.substring("task.".length());
        }

        if (Language.getInstance().has(normalized)) {
            return Component.translatable(normalized);
        }

        if (normalized.startsWith("task.")) {
            String suffix = normalized.substring("task.".length());
            if (Language.getInstance().has(suffix)) {
                return Component.translatable(suffix);
            }
            return Component.literal(suffix);
        }

        return Component.literal(normalized);
    }
}
