package com.habitrain.core.task;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class TaskPoolBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("TaskPoolBuilder");

    private record PoolKey(String modeId, String mapName, String categoryId, List<String> builtinIds) {}

    private static final ConcurrentHashMap<PoolKey, List<TaskDefinition>> CACHE = new ConcurrentHashMap<>();

    public static List<TaskDefinition> getPool(@Nullable GameMode activeMode, String mapName,
                                                @Nullable TaskCategory forcedCategory,
                                                TaskCategory currentCategory,
                                                Player player, Set<String> builtinSreTaskIds) {
        String modeId = activeMode != null ? activeMode.getId() : "null";
        String categoryId = forcedCategory != null ? forcedCategory.getId() :
                (currentCategory != null ? currentCategory.getId() : "null");
        List<String> builtinIds = builtinSreTaskIds == null
                ? List.of() : new ArrayList<>(builtinSreTaskIds);
        Collections.sort(builtinIds);
        PoolKey key = new PoolKey(modeId, mapName, categoryId, List.copyOf(builtinIds));
        List<TaskDefinition> cached = CACHE.computeIfAbsent(key, k -> List.copyOf(getAvailableDlcTasks(
                TaskManager.getInstance(), mapName, currentCategory, activeMode,
                forcedCategory, builtinSreTaskIds, player)));
        return filterForPlayer(cached, activeMode, player);
    }

    public static List<TaskDefinition> getAvailableDlcTasks(TaskManager mgr, String mapName,
                                                             TaskCategory currentCategory,
                                                             @Nullable GameMode activeMode,
                                                             @Nullable TaskCategory forcedCategory,
                                                             Set<String> builtinSreTaskIds,
                                                             Player player) {
        List<TaskDefinition> tasks = selectCandidates(
                TaskRegistry.getAll(), id -> isTaskMapEnabled(id, mapName), currentCategory,
                activeMode, forcedCategory, builtinSreTaskIds, player);
        if (forcedCategory != null) {
            LOGGER.info("[HabiDebug] getAvailableDlcTasks: forced category={}, {} candidates (no fallback)",
                    forcedCategory, tasks.size());
        } else {
            LOGGER.debug("[HabiDebug] getAvailableDlcTasks: {} candidates via exact mode/category policy {}",
                    tasks.size(), currentCategory);
        }
        return tasks;
    }

    /**
     * Selects only definitions that are valid for the current mode/category.
     * An empty result intentionally remains empty: crossing into another mode's category
     * is never a valid fallback.
     */
    static List<TaskDefinition> selectCandidates(Collection<TaskDefinition> definitions,
                                                 Predicate<String> mapEnabled,
                                                 @Nullable TaskCategory currentCategory,
                                                 @Nullable GameMode activeMode,
                                                 @Nullable TaskCategory forcedCategory,
                                                 Set<String> builtinSreTaskIds,
                                                 Player player) {
        if (definitions == null || definitions.isEmpty()) return List.of();
        Set<String> builtinIds = builtinSreTaskIds == null ? Set.of() : builtinSreTaskIds;
        Predicate<TaskDefinition> base = def -> !isBuiltinSreTask(def, builtinIds)
                && mapEnabled.test(def.getFullId())
                && isTaskAllowedForPool(def, currentCategory, activeMode, player);

        return definitions.stream()
                .filter(base)
                .filter(def -> forcedCategory == null || forcedCategory.equals(def.getCategory()))
                .toList();
    }

    public static boolean isBuiltinSreTask(TaskDefinition def, Set<String> builtinSreTaskIds) {
        return builtinSreTaskIds.contains(def.getTaskId());
    }

    public static boolean isTaskMapEnabled(String fullId, String mapName) {
        // 单一真相：委托 ConfigManager（含 enabled + mapFilterMode）
        return ConfigManager.getInstance().isTaskEnabled(fullId, mapName);
    }

    public static boolean isTaskAllowedForPool(TaskDefinition def, TaskCategory currentCategory,
                                                @Nullable GameMode activeMode, Player player) {
        TaskCategory category = def.getCategory();
        if (TaskCategory.ALL.equals(category)
                || TaskCategory.CUSTOM.equals(category)
                || category.equals(currentCategory)) {
            return true;
        }

        return activeMode != null
                && activeMode.getTaskCategories().stream().anyMatch(category::equals);
    }

    private static List<TaskDefinition> filterForPlayer(List<TaskDefinition> tasks,
                                                         @Nullable GameMode activeMode,
                                                         Player player) {
        if (activeMode == null || !(player instanceof ServerPlayer serverPlayer)) {
            return tasks;
        }
        List<TaskDefinition> filtered = activeMode.filterAvailableTasks(tasks, serverPlayer);
        if (filtered == null) {
            LOGGER.warn("GameMode {} returned null from filterAvailableTasks for player {}",
                    activeMode.getId(), serverPlayer.getGameProfile().getName());
            return List.of();
        }
        List<TaskDefinition> sanitized = filtered.stream()
                .filter(tasks::contains)
                .toList();
        if (sanitized.size() != filtered.size()) {
            LOGGER.warn("GameMode {} returned tasks outside the candidate pool for player {}; ignored",
                    activeMode.getId(), serverPlayer.getGameProfile().getName());
        }
        return sanitized;
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    public static void invalidate(String modeId) {
        CACHE.keySet().removeIf(k -> k.modeId().equals(modeId));
    }
}
