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
                                                TaskCategory currentCategory,
                                                Player player, Set<String> builtinSreTaskIds) {
        String modeId = activeMode != null ? activeMode.getId() : "null";
        String categoryId = currentCategory != null ? currentCategory.getId() : "null";
        List<String> builtinIds = builtinSreTaskIds == null
                ? List.of() : new ArrayList<>(builtinSreTaskIds);
        Collections.sort(builtinIds);
        PoolKey key = new PoolKey(modeId, mapName != null ? mapName : "", categoryId, List.copyOf(builtinIds));
        List<TaskDefinition> cached = CACHE.computeIfAbsent(key, k -> List.copyOf(getAvailableDlcTasks(
                mapName, currentCategory, activeMode, builtinSreTaskIds)));
        return filterForPlayer(cached, activeMode, player);
    }

    /**
     * 纯函数式的候选构建：只依赖「模式 / 地图 / 分类 / 内置 ID 清单」，
     * <b>不依赖具体玩家</b>——玩家维度的过滤由 {@link GameMode#filterAvailableTasks} 在
     * {@link #filterForPlayer} 中完成，因此本结果可安全缓存（见 {@link #CACHE}）。
     */
    public static List<TaskDefinition> getAvailableDlcTasks(String mapName,
                                                             TaskCategory currentCategory,
                                                             @Nullable GameMode activeMode,
                                                             Set<String> builtinSreTaskIds) {
        List<TaskDefinition> tasks = selectCandidates(
                TaskRegistry.getAll(), id -> isTaskMapEnabled(id, mapName), currentCategory,
                activeMode, builtinSreTaskIds);
        LOGGER.debug("[HabiDebug] getAvailableDlcTasks: {} candidates via exact mode/category policy {}",
                tasks.size(), currentCategory);
        return tasks;
    }

    /**
     * Selects only definitions that are valid for the current mode/category.
     * An empty result intentionally remains empty: crossing into another mode's category
     * is never a valid fallback.
     * <p>池成员资格 = {@link TaskDefinition#isPoolEligible()}（显式声明，主判据）
     * + 非内置 SRE 任务 ID（历史 ID 清单，兜底判据）+ 地图启用 + 模式/分类许可。
     */
    static List<TaskDefinition> selectCandidates(Collection<TaskDefinition> definitions,
                                                 Predicate<String> mapEnabled,
                                                 @Nullable TaskCategory currentCategory,
                                                 @Nullable GameMode activeMode,
                                                 Set<String> builtinSreTaskIds) {
        if (definitions == null || definitions.isEmpty()) return List.of();
        Set<String> builtinIds = builtinSreTaskIds == null ? Set.of() : builtinSreTaskIds;

        return definitions.stream()
                .filter(def -> def != null
                        && def.isPoolEligible()
                        && !isBuiltinSreTask(def, builtinIds)
                        && mapEnabled.test(def.getFullId())
                        && isTaskAllowedForPool(def, currentCategory, activeMode))
                .toList();
    }

    /** 历史兜底：按内置 SRE 任务 ID 清单排除空壳镜像定义（主判据是 {@link TaskDefinition#isPoolEligible()}）。 */
    public static boolean isBuiltinSreTask(TaskDefinition def, Set<String> builtinSreTaskIds) {
        return def != null && builtinSreTaskIds != null && builtinSreTaskIds.contains(def.getTaskId());
    }

    public static boolean isTaskMapEnabled(String fullId, String mapName) {
        // 单一真相：委托 ConfigManager（含 enabled + mapFilterMode）
        return ConfigManager.getInstance().isTaskEnabled(fullId, mapName);
    }

    /**
     * 模式/分类维度的池许可。<b>与玩家无关</b>：玩家维度的过滤只在
     * {@link #filterForPlayer} 里通过 {@link GameMode#filterAvailableTasks} 施加一次。
     * <p>与 {@link TaskRegistry#getByCategory} 的区别：后者是「注册表查询」，
     * 只看分类不看游戏模式；本方法是「派发池判据」，还要考虑活动模式声明的分类。
     * <p>API-TV-17：{@link TaskCategory#CUSTOM} 任务必须按其 {@code customCategory}、当前分类或所属
     * {@code gameModeId} 匹配，不再无条件绕过模式的任务类别白名单；并对 {@code getTaskCategories()} 做空安全保护。
     */
    public static boolean isTaskAllowedForPool(TaskDefinition def, TaskCategory currentCategory,
                                                @Nullable GameMode activeMode) {
        if (def == null) {
            return false;
        }
        TaskCategory category = def.getCategory();
        if (TaskCategory.ALL.equals(category)) {
            return true;
        }
        TaskCategory effectiveCategory = TaskCategory.CUSTOM.equals(category) && def.getCustomCategory() != null
                ? def.getCustomCategory()
                : category;
        if (effectiveCategory != null && effectiveCategory.equals(currentCategory)) {
            return true;
        }
        if (activeMode != null) {
            java.util.Collection<TaskCategory> modeCategories = activeMode.getTaskCategories();
            if (modeCategories != null && effectiveCategory != null
                    && modeCategories.stream().anyMatch(effectiveCategory::equals)) {
                return true;
            }
            String taskModeId = def.getGameModeId();
            if (taskModeId != null && !"sre:base".equals(taskModeId) && taskModeId.equals(activeMode.getId())) {
                return true;
            }
        }
        return false;
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
        if (modeId == null) return;
        CACHE.keySet().removeIf(k -> k.modeId().equals(modeId));
    }
}
