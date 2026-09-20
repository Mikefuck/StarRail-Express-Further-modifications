package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import io.wifi.starrailexpress.game.GameConstants;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「上游原版 SRE 任务」整理成加权抽取项，供 {@code GenerateTaskMixin} 与 DLC 任务
 * 合并进同一次加权抽取。
 *
 * <p><b>为什么不直接调用上游 {@code generateTaskInternal()}：</b>上游该方法自己完成
 * 建池 + 抽取并直接返回一个任务，无法把 DLC 任务塞进同一次抽取；要让 DLC 与原版任务
 * 共享一个权重池，只能在上游建池的位置重放一遍它的规则。
 *
 * <p><b>因此本类只允许「照抄上游」，不允许自创规则</b>。2.0.10 之前这里是一份
 * 带缺口的重写：心情曲线按任务 ID 硬编码（与上游按 {@code Task.category} 的口径不同）、
 * 漏掉上游的 {@code SRERole#canRefreshTask} 职业刷新限制、并额外吞掉了上游的
 * EAT / DRINK、把 PRAY 排除在场景任务之外。现已逐条对齐上游
 * {@code SREPlayerTaskComponent#generateTaskInternal}，只保留两项<b>新增</b>能力：
 * <ol>
 *   <li>{@link TaskManager#isOriginalTaskDisabled} —— 按 {@code habitrain_core:<id>}
 *       配置逐任务禁用（ModMenu 任务配置页）；</li>
 *   <li>{@link TaskWeightCurves#shouldIncludeOriginalTasks} —— 活跃 GameMode 可整体收窄原版池。</li>
 * </ol>
 */
public final class TaskWeightCalculator {

    private static final Logger LOGGER = LoggerFactory.getLogger("TaskWeightCalculator");

    private TaskWeightCalculator() {}

    /**
     * Builds the pool of original SRE tasks with mood-based weight adjustments.
     *
     * @param entries         mutable list to append weighted entries into
     * @param currentMood     current mood value (0.0 – 1.0 range generally)
     * @param disabledTasks   map-disabled task IDs (上游 AreasWorldComponent.getDisabledTasks)
     * @param mapName         current map name for config lookups
     * @param mgr             task manager instance (配置查询)
     * @param activeMode      active game mode (nullable)
     * @param player          the player (职业刷新限制 / RNG 之外的只读用途)
     * @param existingTasks   the mixin's "tasks" map (shadow field) for duplicate check
     * @param timesGotten     the mixin's "timesGotten" map (shadow field) for anti-repeat
     * @param builtinSreTaskIds set of built-in SRE task IDs for mode filtering
     * @param enabledSceneTasks enabled scene task IDs from the mixin's shadow method
     * @return total accumulated weight
     */
    public static float addOriginalTasks(
            List<Map.Entry<Object, Float>> entries,
            float currentMood,
            Set<String> disabledTasks,
            String mapName,
            TaskManager mgr,
            @Nullable GameMode activeMode,
            Player player,
            Map<SREPlayerTaskComponent.Task, ?> existingTasks,
            Map<SREPlayerTaskComponent.Task, Integer> timesGotten,
            Set<String> builtinSreTaskIds,
            @Nullable Set<String> enabledSceneTasks
    ) {
        if (!TaskWeightCurves.shouldIncludeOriginalTasks(activeMode, player, builtinSreTaskIds)) {
            LOGGER.debug("[HabiDebug] Original SRE tasks filtered out by active GameMode");
            return 0f;
        }

        SRERole role = resolveRole(player);
        float total = 0f;
        int added = 0;

        // ── 非场景任务：与上游 getAvailableTasksList() 同一循环、同一顺序 ──
        for (SREPlayerTaskComponent.Task task : SREPlayerTaskComponent.Task.getAvailableTasksList()) {
            if (existingTasks.containsKey(task)) {
                LOGGER.debug("[HabiDebug]   skip original {}: already in tasks", task.name());
                continue;
            }
            if (disabledTasks.contains(task.name())) {
                LOGGER.debug("[HabiDebug]   skip original {}: disabled by map", task.name());
                continue;
            }
            if (mgr.isOriginalTaskDisabled(task.name(), mapName)) {
                LOGGER.debug("[HabiDebug]   skip original {}: disabled in config", task.name());
                continue;
            }
            if (role != null && !role.canRefreshTask(player, task)) {
                LOGGER.debug("[HabiDebug]   skip original {}: forbidden by role {}", task.name(), role.identifier());
                continue;
            }

            float weight = originalWeight(task, currentMood, timesGotten);
            LOGGER.debug("[HabiDebug]   ADD original {}: weight={}", task.name(), weight);
            entries.add(new AbstractMap.SimpleEntry<>(task, weight));
            total += weight;
            added++;
        }

        // ── 场景任务：与上游同一门控（地图 enabledSceneTasks + 职业刷新限制）──
        if (enabledSceneTasks != null && !enabledSceneTasks.isEmpty()) {
            for (SREPlayerTaskComponent.Task task : SREPlayerTaskComponent.Task.getSceneTasksList()) {
                if (existingTasks.containsKey(task)) continue;
                if (!enabledSceneTasks.contains(task.name())) continue;
                if (disabledTasks.contains(task.name())) continue;
                if (mgr.isOriginalTaskDisabled(task.name(), mapName)) continue;
                if (role != null && !role.canRefreshTask(player, task)) continue;

                float weight = originalWeight(task, currentMood, timesGotten);
                LOGGER.debug("[HabiDebug]   ADD scene original {}: weight={}", task.name(), weight);
                entries.add(new AbstractMap.SimpleEntry<>(task, weight));
                total += weight;
                added++;
            }
        }

        LOGGER.debug("[HabiDebug] Original tasks added: {}, weight total={}", added, String.format("%.2f", total));
        return total;
    }

    /** 反重复权重 × 心情曲线（与上游逐字同式）。 */
    private static float originalWeight(SREPlayerTaskComponent.Task task, float currentMood,
                                        Map<SREPlayerTaskComponent.Task, Integer> timesGotten) {
        int times = timesGotten.getOrDefault(task, 1);
        float weight = 1f / Math.max(1, times);
        return applyMoodWeight(weight, task.category, currentMood);
    }

    /**
     * 与上游 {@code SREPlayerTaskComponent#applyMoodWeight} 完全同式：
     * 按 {@link SREPlayerTaskComponent.Task.TaskCategory} 分类调整，
     * <b>不</b>按任务 ID 硬编码，避免新增/改名任务时口径漂移。
     */
    private static float applyMoodWeight(float weight, SREPlayerTaskComponent.Task.TaskCategory category,
                                         float currentMood) {
        if (category == null) {
            return weight;
        }
        if (currentMood < GameConstants.MID_MOOD_THRESHOLD) {
            // 情绪低落时：安抚性任务权重翻倍，活跃性任务权重降低
            if (category == SREPlayerTaskComponent.Task.TaskCategory.SOOTHING) {
                weight *= 2f;
            }
            if (category == SREPlayerTaskComponent.Task.TaskCategory.ACTIVE) {
                weight *= 0.5f;
            }
        } else if (currentMood > GameConstants.ANGRY_MOOD_THRESHOLD) {
            // 情绪亢奋时：活跃性任务权重提升，静态任务权重降低
            if (category == SREPlayerTaskComponent.Task.TaskCategory.ACTIVE) {
                weight *= 1.5f;
            }
            if (category == SREPlayerTaskComponent.Task.TaskCategory.STATIC
                    || category == SREPlayerTaskComponent.Task.TaskCategory.SOOTHING) {
                weight *= 0.5f;
            }
        }
        return weight;
    }

    /** 上游用职业的 {@code canRefreshTask} 过滤随机池；读不到职业时视为无限制。 */
    @Nullable
    private static SRERole resolveRole(Player player) {
        if (player == null || player.level() == null) {
            return null;
        }
        try {
            SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(player.level());
            return gameWorld != null ? gameWorld.getRole(player) : null;
        } catch (Throwable t) {
            LOGGER.debug("resolveRole failed; treating role refresh limits as unrestricted", t);
            return null;
        }
    }
}
