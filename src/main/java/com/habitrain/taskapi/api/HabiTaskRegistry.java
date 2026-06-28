package com.habitrain.taskapi.api;

import java.util.*;

/**
 * 任务注册中心 - DLC模组通过此API注册自定义任务
 * 所有注册的任务会自动出现在ModMenu配置界面中
 */
public class HabiTaskRegistry {
    private static final Map<String, HabiTaskDefinition> REGISTRY = new LinkedHashMap<>();
    private static boolean frozen = false;

    /**
     * 注册一个任务定义
     * @param definition 任务定义
     * @throws IllegalStateException 如果注册表已冻结 (mod初始化完成后)
     */
    public static void register(HabiTaskDefinition definition) {
        if (frozen) {
            throw new IllegalStateException("Task registry is frozen! Register tasks during mod initialization only.");
        }
        String fullId = definition.getFullId();
        if (REGISTRY.containsKey(fullId)) {
            throw new IllegalArgumentException("Task '" + fullId + "' is already registered!");
        }
        REGISTRY.put(fullId, definition);
    }

    /**
     * 快捷注册方法
     * @param modId  模组ID
     * @param taskId 任务ID
     * @param builder 构建器配置
     * @return 注册的HabiTaskDefinition
     */
    public static HabiTaskDefinition register(String modId, String taskId,
                                               java.util.function.Consumer<HabiTaskDefinition.Builder> builder) {
        HabiTaskDefinition.Builder b = new HabiTaskDefinition.Builder(modId, taskId);
        builder.accept(b);
        HabiTaskDefinition def = b.build();
        register(def);
        return def;
    }

    /**
     * 获取所有注册的任务定义
     */
    public static Collection<HabiTaskDefinition> getAll() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    /**
     * 根据完整ID获取任务定义
     */
    public static HabiTaskDefinition get(String fullId) {
        return REGISTRY.get(fullId);
    }

    /**
     * 获取所有注册的任务ID
     */
    public static Set<String> getAllIds() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    /**
     * 按分类获取任务
     */
    public static List<HabiTaskDefinition> getByCategory(HabiTaskCategory category) {
        return REGISTRY.values().stream()
                .filter(def -> def.getCategory() == category || def.getCategory() == HabiTaskCategory.ALL)
                .toList();
    }

    /**
     * 检查某个完整ID是否已注册
     */
    public static boolean isRegistered(String fullId) {
        return REGISTRY.containsKey(fullId);
    }

    /**
     * 获取注册数量
     */
    public static int size() {
        return REGISTRY.size();
    }

    /**
     * 冻结注册表 (mod初始化完成后调用，禁止再注册)
     */
    public static void freeze() {
        frozen = true;
    }

    /**
     * 是否为冻结状态
     */
    public static boolean isFrozen() {
        return frozen;
    }
}
