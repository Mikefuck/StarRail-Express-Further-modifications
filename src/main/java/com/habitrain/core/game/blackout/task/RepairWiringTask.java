package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 停电模式好人任务：修理线路（右键交互）。
 * <p>派发时给玩家 1 个红石，玩家手持红石右键红石块 → 消耗红石 + 缓慢 III(3秒) + 完成。
 * <p>完成时延迟停电时间 40 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class RepairWiringTask {
    public static void register() {
        TaskRegistry.register("habitrain_core", "repair_wiring", builder -> builder
            .displayName("修理线路")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(21)
            .instinctColor(255, 215, 0, 200)
            .scanBlocks(Blocks.REDSTONE_BLOCK)
            // 声明时间影响：完成后延迟停电倒计时 40 秒。
            // 自适应刷新概率从 delta=40 派生阈值（low=30s, high=120s）。
            .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 40)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                if (player instanceof ServerPlayer serverPlayer) {
                    boolean added = serverPlayer.getInventory().add(new ItemStack(Items.REDSTONE, 1));
                    if (!added) {
                        serverPlayer.drop(new ItemStack(Items.REDSTONE, 1), false);
                    }
                }
            })
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    // 通过 applyTimeImpact 统一调用（替代硬编码 delayMaintenanceOrCountdown(level, 40)）
                    BlackoutTaskHelper.applyTimeImpact(serverPlayer.serverLevel(), "habitrain_core:repair_wiring");
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:repair_wiring");
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.repair_wiring"),
                            Component.literal("§a线路已修复！停电时间延迟 40 秒。"),
                            80
                    );
                    // 同步完成其它正在做 repair_wiring 的 GOOD 玩家
                    SupplyTaskSyncHelper.syncCompletion(
                            serverPlayer.serverLevel(), serverPlayer.getUUID(), "habitrain_core:repair_wiring");
                }
            })
            .onRemove((player, task) -> cleanup(player))
        );
    }

    private static void cleanup(Player player) {
        if (player == null) return;
        RepairWiringHandler.clearState(player.getUUID());
    }
}