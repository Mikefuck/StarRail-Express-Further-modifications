package com.habitrain.core.api;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 任务注册中心 — 取代 HabiTaskRegistry。
 * 新增: 按 GameMode 查询。
 */
public class TaskRegistry {
    private static final Map<String, TaskDefinition> REGISTRY = new LinkedHashMap<>();
    private static boolean frozen = false;

    public static void register(TaskDefinition definition) {
        if (frozen) throw new IllegalStateException("Task registry is frozen!");
        String fullId = definition.getFullId();
        if (REGISTRY.containsKey(fullId)) throw new IllegalArgumentException("Task '" + fullId + "' is already registered!");
        REGISTRY.put(fullId, definition);
    }

    public static TaskDefinition register(String modId, String taskId, Consumer<TaskDefinition.Builder> builder) {
        TaskDefinition.Builder b = new TaskDefinition.Builder(modId, taskId);
        builder.accept(b);
        TaskDefinition def = b.build();
        register(def);
        return def;
    }

    public static Collection<TaskDefinition> getAll() { return Collections.unmodifiableCollection(REGISTRY.values()); }
    public static TaskDefinition get(String fullId) { return REGISTRY.get(fullId); }
    public static Set<String> getAllIds() { return Collections.unmodifiableSet(REGISTRY.keySet()); }
    public static boolean isRegistered(String fullId) { return REGISTRY.containsKey(fullId); }
    public static int size() { return REGISTRY.size(); }

    /** 按 GameMode ID 查询属于某个模式的所有任务 */
    public static List<TaskDefinition> getByGameMode(String gameModeId) {
        return REGISTRY.values().stream()
                .filter(def -> gameModeId.equals(def.getGameModeId()))
                .collect(Collectors.toList());
    }

    /** 按分类查询 */
    public static List<TaskDefinition> getByCategory(TaskCategory category) {
        return REGISTRY.values().stream()
                .filter(def -> def.getCategory() == category
                        || def.getCategory() == TaskCategory.ALL)
                .toList();
    }

    public static void freeze() { frozen = true; }
    public static boolean isFrozen() { return frozen; }
}
