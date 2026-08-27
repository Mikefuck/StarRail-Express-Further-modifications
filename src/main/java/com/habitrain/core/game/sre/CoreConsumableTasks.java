package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.task.BlackoutTaskHelper;
import net.minecraft.server.level.ServerPlayer;

/** Canonical Core-owned eat and drink tasks used by every SRE game mode. */
public final class CoreConsumableTasks {

    public static final int EAT_BLOCK_TYPE_ID = 39;
    public static final int DRINK_BLOCK_TYPE_ID = 40;

    private CoreConsumableTasks() {
    }

    public static void register() {
        TaskRegistry.register(createEatDefinition());
        TaskRegistry.register(createDrinkDefinition());
    }

    static TaskDefinition createEatDefinition() {
        return new TaskDefinition.Builder(HabiTrainCore.MOD_ID, "eat")
                .displayName("进食")
                .category(TaskCategory.ALL)
                .weight(3.0f)
                .blockTypeId(EAT_BLOCK_TYPE_ID)
                .instinctColor(0, 255, 0, 200)
                .scanBlockIds("trainmurdermystery:food_platter")
                .canRepeat(true)
                .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 10)
                .onAssign((player, task) -> task.setMaxProgress(1))
                .onTick((player, task) -> resetInvalidProgress(player, task))
                .completionChecker((player, task) ->
                        CustomTaskTickGate.allow(player) && task.getProgress() >= task.getMaxProgress())
                .onComplete((player, task) -> complete(player, HabiTrainCore.TASK_EAT))
                .build();
    }

    static TaskDefinition createDrinkDefinition() {
        return new TaskDefinition.Builder(HabiTrainCore.MOD_ID, "drink")
                .displayName("喝水")
                .category(TaskCategory.ALL)
                .weight(3.0f)
                .blockTypeId(DRINK_BLOCK_TYPE_ID)
                .instinctColor(234, 88, 88, 200)
                .scanBlockIds("trainmurdermystery:drink_tray")
                .canRepeat(true)
                .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 10)
                .onAssign((player, task) -> task.setMaxProgress(1))
                .onTick((player, task) -> resetInvalidProgress(player, task))
                .completionChecker((player, task) ->
                        CustomTaskTickGate.allow(player) && task.getProgress() >= task.getMaxProgress())
                .onComplete((player, task) -> complete(player, HabiTrainCore.TASK_DRINK))
                .build();
    }

    private static void resetInvalidProgress(net.minecraft.world.entity.player.Player player,
                                             com.habitrain.core.api.TaskInstance task) {
        if (!CustomTaskTickGate.allow(player) && task.getProgress() != 0) {
            task.setProgress(0);
        }
    }

    private static void complete(net.minecraft.world.entity.player.Player player, String taskFullId) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        boolean blackoutActive = GameModeRegistry.getActiveForLevel(serverPlayer.serverLevel())
                .filter(mode -> mode instanceof BlackoutMode)
                .isPresent();
        if (blackoutActive) {
            BlackoutTaskHelper.applyTimeImpact(serverPlayer.serverLevel(), taskFullId);
        }
    }
}
