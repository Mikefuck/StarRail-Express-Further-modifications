package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskDefinition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Set;

/** Applies a registered mode's filter to the upstream task pool. */
public final class TaskWeightCurves {
    private TaskWeightCurves() {}
    public static boolean shouldIncludeOriginalTasks(@Nullable GameMode activeMode, Player player,
                                                      Set<String> builtinSreTaskIds) {
        if (activeMode == null) {
            return true;
        }
        if (!(player instanceof ServerPlayer sp)) {
            return true;
        }

        // Examine only the builtinSreTaskIds against the mode's filter, avoiding
        // constructing a full Set from the entire task registry every call.
        List<TaskDefinition> allTasks = com.habitrain.core.api.TaskRegistry.getAll().stream()
                .filter(t -> builtinSreTaskIds.contains(t.getTaskId()))
                .toList();
        List<TaskDefinition> filtered = activeMode.filterAvailableTasks(allTasks, sp);
        return !filtered.isEmpty();
    }
}
