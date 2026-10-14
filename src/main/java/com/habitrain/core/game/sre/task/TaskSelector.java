package com.habitrain.core.game.sre.task;

import com.habitrain.core.api.TaskDefinition;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
     * @param dlcCreator      callback to create and track a DLC task
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
        return selectAccepted(pool, () -> player.getRandom().nextFloat(),
                key -> instantiateTask(key, originalCreator, dlcCreator));
    }

    /** Each rejected candidate is removed once, so an all-rejected pool terminates. */
    static <T> T selectAccepted(List<Map.Entry<Object, Float>> pool,
            java.util.function.DoubleSupplier randomSource, Function<Object, T> creator) {
        List<Map.Entry<Object, Float>> remaining = new ArrayList<>();
        for (var entry : pool) {
            if (entry.getValue() != null && Float.isFinite(entry.getValue()) && entry.getValue() > 0)
                remaining.add(entry);
        }
        while (!remaining.isEmpty()) {
            double weight = remaining.stream().mapToDouble(Map.Entry::getValue).sum();
            double roll = randomSource.getAsDouble() * weight;
            int selected = remaining.size() - 1;
            for (int i = 0; i < remaining.size(); i++) {
                roll -= remaining.get(i).getValue();
                if (roll < 0) { selected = i; break; }
            }
            T task = creator.apply(remaining.remove(selected).getKey());
            if (task != null) return task;
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

}
