package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 停电模式好人任务：进食（吃任意食物完成）。
 * <p>玩家吃任意可食用物品（有 FOOD 组件的物品）即完成任务。
 * <p>完成时增加供电时间 10 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutEatTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_eat", builder -> builder
            .displayName("进食")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(39)
            .instinctColor(0, 255, 0, 200)
            .scanBlockIds("trainmurdermystery:food_platter")
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
            })
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:blackout_eat");
                }
            })
        );
    }
}