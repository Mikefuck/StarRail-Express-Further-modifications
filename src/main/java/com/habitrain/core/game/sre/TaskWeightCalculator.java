package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.task.TaskManager;
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

public final class TaskWeightCalculator {

    private static final Logger LOGGER = LoggerFactory.getLogger("TaskWeightCalculator");

    private TaskWeightCalculator() {}

    /**
     * Builds the pool of original SRE tasks with mood-based weight adjustments.
     *
     * @param entries         mutable list to append weighted entries into
     * @param currentMood     current mood value (0.0 – 1.0 range generally)
     * @param disabledTasks   globally disabled task IDs
     * @param mapName         current map name for config lookups
     * @param mgr             task manager instance
     * @param activeMode      active game mode (nullable)
     * @param player          the player (for TaskWeightCurves)
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

        float total = 0f;
        int added = 0;

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

            float weight = 1f / Math.max(1, timesGotten.getOrDefault(task, 1));

            if (currentMood < GameConstants.MID_MOOD_THRESHOLD) {
                if (task == SREPlayerTaskComponent.Task.MEDITATE
                        || task == SREPlayerTaskComponent.Task.SLEEP
                        || task == SREPlayerTaskComponent.Task.CHAIR) {
                    weight *= 2f;
                }
                if (task == SREPlayerTaskComponent.Task.EXERCISE
                        || task == SREPlayerTaskComponent.Task.OUTSIDE
                        || task == SREPlayerTaskComponent.Task.BREATHE) {
                    weight *= 0.5f;
                }
            } else if (currentMood > GameConstants.ANGRY_MOOD_THRESHOLD) {
                if (task == SREPlayerTaskComponent.Task.EXERCISE
                        || task == SREPlayerTaskComponent.Task.OUTSIDE
                        || task == SREPlayerTaskComponent.Task.NOTE_BLOCK) {
                    weight *= 1.5f;
                }
                if (task == SREPlayerTaskComponent.Task.SLEEP
                        || task == SREPlayerTaskComponent.Task.MEDITATE) {
                    weight *= 0.5f;
                }
            }

            LOGGER.debug("[HabiDebug]   ADD original {}: weight={}", task.name(), weight);
            entries.add(new AbstractMap.SimpleEntry<>(task, weight));
            total += weight;
            added++;
        }

        if (enabledSceneTasks != null && !enabledSceneTasks.isEmpty()) {
            for (SREPlayerTaskComponent.Task task : SREPlayerTaskComponent.Task.getSceneTasksList()) {
                if (task == SREPlayerTaskComponent.Task.PRAY) continue;

                if (existingTasks.containsKey(task)) continue;
                if (!enabledSceneTasks.contains(task.name())) continue;
                if (disabledTasks.contains(task.name())) continue;
                if (mgr.isOriginalTaskDisabled(task.name(), mapName)) continue;

                float weight = 1f / Math.max(1, timesGotten.getOrDefault(task, 1));
                if (currentMood < GameConstants.MID_MOOD_THRESHOLD) {
                    if (task == SREPlayerTaskComponent.Task.LIGHT_STOVE) weight *= 2f;
                } else if (currentMood > GameConstants.ANGRY_MOOD_THRESHOLD) {
                    if (task == SREPlayerTaskComponent.Task.CLEAN_DUST
                            || task == SREPlayerTaskComponent.Task.TRANSPORT
                            || task == SREPlayerTaskComponent.Task.PRUNE_BUSH
                            || task == SREPlayerTaskComponent.Task.HARVEST_CROP) {
                        weight *= 1.5f;
                    }
                }

                LOGGER.debug("[HabiDebug]   ADD scene original {}: weight={}", task.name(), weight);
                entries.add(new AbstractMap.SimpleEntry<>(task, weight));
                total += weight;
                added++;
            }
        }

        LOGGER.debug("[HabiDebug] Original tasks added: {}, weight total={}", added, String.format("%.2f", total));
        return total;
    }
}
