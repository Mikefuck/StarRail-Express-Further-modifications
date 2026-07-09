package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public class TaskWeightCurves {
    public static final float DYNAMIC_WEIGHT_CAP = 4.0f;
    public static final float DYNAMIC_WEIGHT_FLOOR = 0.05f;
    public static final String ID_MAINTAIN_POWER = "habitrain_core:maintain_power";
    public static final String ID_REPAIR_WIRING = "habitrain_core:repair_wiring";
    public static final String ID_ADD_COAL = "habitrain_core:add_coal";

    public static float computeBlackoutDynamicMultiplier(TaskDefinition def, Player player) {
        if (!(player instanceof ServerPlayer sp)) return 1.0f;
        if (!(sp.level() instanceof ServerLevel level)) return 1.0f;

        String fullId = def.getFullId();
        float multiplier = 1.0f;

        if (isSupplyTaskWithPositiveTimeImpact(def)) {
            multiplier = computeUrgencyMultiplier(def, level);
        } else if (ID_ADD_COAL.equals(fullId)) {
            multiplier = computeSurvivalMultiplier(level);
        }

        return Math.min(DYNAMIC_WEIGHT_CAP, Math.max(DYNAMIC_WEIGHT_FLOOR, multiplier));
    }

    public static boolean isSupplyTaskWithPositiveTimeImpact(TaskDefinition def) {
        if (def.getTimeImpact() == null) return false;
        if (def.getTimeImpact().axis() != TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN) return false;
        return def.getTimeImpact().deltaSeconds() > 0;
    }

    public static float computeUrgencyMultiplier(TaskDefinition def, ServerLevel level) {
        BlackoutTimerSystem.Phase phase = BlackoutTimerSystem.getPhase(level);
        int remaining;
        if (phase == BlackoutTimerSystem.Phase.NORMAL) {
            remaining = BlackoutTimerSystem.getBlackoutCountdown(level);
        } else if (phase == BlackoutTimerSystem.Phase.MAINTENANCE) {
            remaining = BlackoutTimerSystem.getMaintenanceTime(level);
        } else {
            return 1.0f;
        }

        int delta = def.getTimeImpact().deltaSeconds();
        int lowThreshold = Math.max(30, (int)(delta * 0.75));
        int highThreshold = Math.max(180, delta * 3);

        if (remaining <= lowThreshold) return DYNAMIC_WEIGHT_CAP;
        if (remaining >= highThreshold) return DYNAMIC_WEIGHT_FLOOR;

        float t = (float)(remaining - lowThreshold) / (highThreshold - lowThreshold);
        t = t * t * (3 - 2 * t);
        return DYNAMIC_WEIGHT_CAP * (1 - t) + DYNAMIC_WEIGHT_FLOOR * t;
    }

    public static float computeSurvivalMultiplier(ServerLevel level) {
        int remaining = BlackoutRoleManager.getRemainingGood(level);
        int initial = BlackoutRoleManager.getInitialGoodCount(level);
        if (initial <= 0) return 1.0f;
        float ratio = Math.max(0f, (float) remaining / (float) initial);
        return 1.0f + (1.0f - ratio) * (DYNAMIC_WEIGHT_CAP - 1.0f);
    }

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
