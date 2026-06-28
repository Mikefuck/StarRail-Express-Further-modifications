package com.habitrain.taskapi.client;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端自定义任务缓存 - 存储从同步数据中捕获的自定义任务信息
 * 供 HUD 渲染使用（非 mixin 类，避免 visibility 限制）
 */
public class CustomTaskStore {
    private static final Map<String, String> activeCustomTasks = new HashMap<>();

    public static Map<String, String> getActiveCustomTasks() {
        return activeCustomTasks;
    }

    public static void clear() {
        activeCustomTasks.clear();
    }

    public static void put(String customId, String displayName) {
        activeCustomTasks.put(customId, displayName);
    }
}
