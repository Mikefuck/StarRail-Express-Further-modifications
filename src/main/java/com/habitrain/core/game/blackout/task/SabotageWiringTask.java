package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * 停电模式坏人任务：破坏线路（右键交互）。
 * <p>玩家右键红石块 → 给缓慢III(6秒) + 推进任务完成。
 * <p>完成时缩短供电时间 25 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_BAD} 池。
 */
public class SabotageWiringTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "sabotage_wiring", builder -> builder
            .displayName("破坏线路")
            .category(BlackoutMode.BLACKOUT_BAD)
            .weight(3.0f)
            .blockTypeId(22)
            .instinctColor(255, 0, 0, 200)
            .scanBlocks(Blocks.REDSTONE_BLOCK)
            // 声明时间影响：缩短供电倒计时/维护时间 25 秒（负值=减少）。
            .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, -25)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
            })
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTaskHelper.applyTimeImpact(serverPlayer.serverLevel(), "habitrain_core:sabotage_wiring");
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:sabotage_wiring");
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.sabotage_wiring"),
                            Component.literal("§a线路已破坏！供电时间缩短 25 秒。"),
                            80
                    );
                }
            })
            .onRemove((player, task) -> cleanup(player))
        );
    }

    private static void cleanup(Player player) {
        if (player == null) return;
        SabotageWiringHandler.clearState(player.getUUID());
    }
}