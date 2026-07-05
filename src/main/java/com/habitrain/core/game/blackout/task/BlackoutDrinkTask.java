package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 停电模式好人任务：喝水（喝任意瓶子/流体容器完成）。
 * <p>玩家饮用任意瓶子类物品（药水/水/牛奶/蜂蜜瓶等）即完成任务。
 * <p>完成时增加供电时间 10 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutDrinkTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_drink", builder -> builder
            .displayName("喝水")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(-1)
            .instinctColor(80, 170, 255, 200)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                if (player instanceof ServerPlayer serverPlayer) {
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.blackout_drink"),
                            Component.literal("§6【任务】喝点什么解解渴！"),
                            80
                    );
                }
            })
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:blackout_drink");
                }
            })
            .onRemove((player, task) -> BlackoutDrinkHandler.clearState(player.getUUID()))
        );
    }
}