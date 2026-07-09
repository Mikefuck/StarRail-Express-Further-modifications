package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskDefinition;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class TaskSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger("TaskSelector");

    private TaskSelector() {}

    /**
     * Performs weighted random selection from the combined pool.
     *
     * @param pool            the weighted entries (task/DLC -> weight)
     * @param total           total accumulated weight
     * @param player          the player (for RNG)
     * @param originalCreator callback to instantiate an original task (wraps the @Shadow createTaskInstance)
     * @param dlcCreator      callback to create and track a DLC task (captures currentIsFakeTask in closure)
     * @return selected TrainTask, or null if none could be selected
     */
    @Nullable
    public static SREPlayerTaskComponent.TrainTask weightedSelect(
            List<Map.Entry<Object, Float>> pool,
            float total,
            Player player,
            Function<SREPlayerTaskComponent.Task, SREPlayerTaskComponent.TrainTask> originalCreator,
            Function<TaskDefinition, SREPlayerTaskComponent.TrainTask> dlcCreator
    ) {
        if (pool.isEmpty() || total <= 0) {
            LOGGER.debug("[HabiDebug] weightedSelect: pool empty or total<=0, returning null");
            return null;
        }

        float random = player.getRandom().nextFloat() * total;
        Collections.shuffle(pool);

        LOGGER.debug("[HabiDebug] weightedSelect: random={}/{}",
                String.format("%.4f", random), String.format("%.4f", total));

        for (Map.Entry<Object, Float> entry : pool) {
            random -= entry.getValue();
            LOGGER.debug("[HabiDebug]   roll: key={}, value={}, after={}",
                    formatKey(entry.getKey()),
                    String.format("%.4f", entry.getValue()),
                    String.format("%.4f", random));
            if (random < 0) {
                return instantiateTask(entry.getKey(), originalCreator, dlcCreator);
            }
        }

        // Fallback: select from remaining entries
        LOGGER.debug("[HabiDebug] Fallback (float): selecting from remaining entries");
        for (Map.Entry<Object, Float> entry : pool) {
            Object key = entry.getKey();
            if (key instanceof TaskDefinition defKey) {
                LOGGER.debug("[HabiDebug] Fallback selected DLC: {}", defKey.getFullId());
                return dlcCreator.apply(defKey);
            }
        }
        Object firstKey = pool.get(0).getKey();
        if (firstKey instanceof SREPlayerTaskComponent.Task taskKey) {
            LOGGER.debug("[HabiDebug] Fallback selected original: {}", taskKey.name());
            return originalCreator.apply(taskKey);
        }
        return null;
    }

    @Nullable
    private static SREPlayerTaskComponent.TrainTask instantiateTask(
            Object key,
            Function<SREPlayerTaskComponent.Task, SREPlayerTaskComponent.TrainTask> originalCreator,
            Function<TaskDefinition, SREPlayerTaskComponent.TrainTask> dlcCreator
    ) {
        if (key instanceof SREPlayerTaskComponent.Task taskKey) {
            LOGGER.debug("[HabiDebug] SELECTED original task: {}", taskKey.name());
            return originalCreator.apply(taskKey);
        } else if (key instanceof TaskDefinition defKey) {
            LOGGER.debug("[HabiDebug] SELECTED DLC task: {}", defKey.getFullId());
            return dlcCreator.apply(defKey);
        }
        LOGGER.warn("[HabiDebug] Unknown key type: {}", key.getClass().getName());
        return null;
    }

    private static String formatKey(Object key) {
        if (key instanceof SREPlayerTaskComponent.Task t) return t.name();
        if (key instanceof TaskDefinition d) return d.getFullId();
        return key.toString();
    }
}
