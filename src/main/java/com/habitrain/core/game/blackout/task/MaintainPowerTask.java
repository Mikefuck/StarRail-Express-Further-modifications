package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 停电模式好人任务：维持供电（持续右键发电机）。
 * <p>玩家每 5 秒右键发电机一次，累计 15 秒后完成。漏右键则进度重置。
 * <p>完成时增加供电时间 25 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class MaintainPowerTask {
    public static void register() {
        TaskRegistry.register("habitrain_core", "maintain_power", builder -> builder
            .displayName("维持供电")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(-1)
            .instinctColor(0, 200, 255, 200)
            .scanBlockIds("yuushya:generator")
            .onAssign((player, task) -> {
                task.setMaxProgress(15);
                if (player instanceof ServerPlayer serverPlayer) {
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.maintain_power"),
                            Component.literal("§6【任务】每 5 秒右键发电机一次，持续 15 秒完成。"),
                            80
                    );
                }
            })
            .onTick((player, task) -> MaintainPowerHandler.tickCheck(player, task))
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), 25);
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:maintain_power");
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.maintain_power"),
                            Component.literal("§a供电已维持！供电时间增加 25 秒。"),
                            80
                    );
                }
            })
            .onRemove((player, task) -> cleanup(player))
        );
    }

    private static void cleanup(Player player) {
        if (player == null) return;
        MaintainPowerHandler.clearState(player.getUUID());
    }
}