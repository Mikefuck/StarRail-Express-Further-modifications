package com.habitrain.core.game.sre.task;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskDefinition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Applies a registered mode's filter to the upstream task pool. */
public final class TaskWeightCurves {
    private TaskWeightCurves() {}

    public static boolean shouldIncludeOriginalTasks(@Nullable GameMode activeMode, Player player,
                                                      Set<String> builtinSreTaskIds) {
        Set<String> allowed = resolveAllowedOriginalTaskIds(activeMode, player, builtinSreTaskIds);
        return allowed == null || !allowed.isEmpty();
    }

    /**
     * Returns the lower-case task IDs permitted by {@code activeMode.filterAvailableTasks},
     * or {@code null} when no mode filtering applies.
     */
    @Nullable
    public static Set<String> resolveAllowedOriginalTaskIds(@Nullable GameMode activeMode, Player player,
                                                            Set<String> builtinSreTaskIds) {
        if (activeMode == null || !(player instanceof ServerPlayer sp) || builtinSreTaskIds == null) {
            return null;
        }
        List<TaskDefinition> allTasks = com.habitrain.core.api.TaskRegistry.getAll().stream()
                .filter(t -> builtinSreTaskIds.contains(t.getTaskId()))
                .toList();
        List<TaskDefinition> filtered = activeMode.filterAvailableTasks(allTasks, sp);
        if (filtered == null || filtered.isEmpty()) {
            return Set.of();
        }
        Set<String> allowed = new HashSet<>(filtered.size());
        for (TaskDefinition def : filtered) {
            if (def != null && def.getTaskId() != null) {
                allowed.add(def.getTaskId().toLowerCase(Locale.ROOT));
            }
        }
        return allowed;
    }
}
