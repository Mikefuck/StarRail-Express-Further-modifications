package com.habitrain.core.task;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TaskPoolBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("TaskPoolBuilder");

    private record PoolKey(String modeId, String mapName, String categoryId) {}

    private static final ConcurrentHashMap<PoolKey, List<TaskDefinition>> CACHE = new ConcurrentHashMap<>();

    public static List<TaskDefinition> getPool(@Nullable GameMode activeMode, String mapName,
                                                @Nullable TaskCategory forcedCategory,
                                                TaskCategory currentCategory,
                                                Player player, Set<String> builtinSreTaskIds) {
        String modeId = activeMode != null ? activeMode.getId() : "null";
        String categoryId = forcedCategory != null ? forcedCategory.getId() :
                (currentCategory != null ? currentCategory.getId() : "null");
        PoolKey key = new PoolKey(modeId, mapName, categoryId);
        return CACHE.computeIfAbsent(key, k -> getAvailableDlcTasks(
                TaskManager.getInstance(), mapName, currentCategory, activeMode,
                forcedCategory, builtinSreTaskIds, player));
    }

    public static List<TaskDefinition> getAvailableDlcTasks(TaskManager mgr, String mapName,
                                                             TaskCategory currentCategory,
                                                             @Nullable GameMode activeMode,
                                                             @Nullable TaskCategory forcedCategory,
                                                             Set<String> builtinSreTaskIds,
                                                             Player player) {
        if (forcedCategory != null) {
            List<TaskDefinition> tasks = TaskRegistry.getAll().stream()
                    .filter(def -> !isBuiltinSreTask(def, builtinSreTaskIds))
                    .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                    .filter(def -> isTaskAllowedForPool(def, currentCategory, activeMode, player))
                    .filter(def -> forcedCategory.equals(def.getCategory()))
                    .collect(Collectors.toList());
            LOGGER.info("[HabiDebug] getAvailableDlcTasks: blackout faction filter={}, {} candidates (fallback disabled)",
                    forcedCategory, tasks.size());
            return tasks;
        }

        List<TaskDefinition> tasks = TaskRegistry.getAll().stream()
                .filter(def -> !isBuiltinSreTask(def, builtinSreTaskIds))
                .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                .filter(def -> isTaskAllowedForPool(def, currentCategory, activeMode, player))
                .collect(Collectors.toList());
        if (!tasks.isEmpty()) {
            LOGGER.debug("[HabiDebug] getAvailableDlcTasks: {} via category {}", tasks.size(), currentCategory);
            return tasks;
        }

        if (currentCategory != TaskCategory.MURDER) {
            tasks = TaskRegistry.getAll().stream()
                    .filter(def -> !isBuiltinSreTask(def, builtinSreTaskIds))
                    .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                    .filter(def -> isTaskAllowedForPool(def, TaskCategory.MURDER, activeMode, player))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->MURDER, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        if (currentCategory != TaskCategory.ALL) {
            tasks = TaskRegistry.getAll().stream()
                    .filter(def -> !isBuiltinSreTask(def, builtinSreTaskIds))
                    .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                    .filter(def -> isTaskAllowedForPool(def, TaskCategory.ALL, activeMode, player))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->ALL, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ULTIMATE fallback (ignoring category)");
        tasks = TaskRegistry.getAll().stream()
                .filter(def -> !isBuiltinSreTask(def, builtinSreTaskIds))
                .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                .collect(Collectors.toList());
        LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ultimate found {} tasks", tasks.size());
        return tasks;
    }

    public static boolean isBuiltinSreTask(TaskDefinition def, Set<String> builtinSreTaskIds) {
        return builtinSreTaskIds.contains(def.getTaskId());
    }

    public static boolean isTaskMapEnabled(String fullId, String mapName) {
        TaskConfigEntry entry = ConfigManager.getInstance().getTaskConfig(fullId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        if (entry.mapFilterMode == 0) return true;

        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);

        if (entry.mapFilterMode == 1) return listEmpty || contained;
        return listEmpty || !contained;
    }

    public static boolean isTaskAllowedForPool(TaskDefinition def, TaskCategory currentCategory,
                                                @Nullable GameMode activeMode, Player player) {
        if (activeMode != null && player instanceof ServerPlayer sp) {
            if (activeMode.filterAvailableTasks(List.of(def), sp).isEmpty()) {
                return false;
            }
        }

        TaskCategory category = def.getCategory();
        if (TaskCategory.ALL.equals(category)
                || TaskCategory.CUSTOM.equals(category)
                || category.equals(currentCategory)) {
            return true;
        }

        return activeMode != null
                && activeMode.getTaskCategories().stream().anyMatch(category::equals);
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    public static void invalidate(String modeId) {
        CACHE.keySet().removeIf(k -> k.modeId().equals(modeId));
    }
}
