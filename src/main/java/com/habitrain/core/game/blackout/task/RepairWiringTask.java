package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 停电模式好人任务：修理线路（右键交互）。
 * <p>派发时给玩家 1 个红石，玩家手持红石右键红石块 → 消耗红石 + 缓慢 III(6秒) + 完成。
 * <p>完成时延迟停电时间 15 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class RepairWiringTask {
    public static void register() {
        TaskRegistry.register("habitrain_core", "repair_wiring", builder -> builder
            .displayName("修理线路")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(-1)
            .instinctColor(255, 215, 0, 200)
            .scanBlocks(Blocks.REDSTONE_BLOCK)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                if (player instanceof ServerPlayer serverPlayer) {
                    boolean added = serverPlayer.getInventory().add(new ItemStack(Items.REDSTONE, 1));
                    if (!added) {
                        serverPlayer.drop(new ItemStack(Items.REDSTONE, 1), false);
                    }
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.repair_wiring"),
                            Component.literal("§6【任务】拿着红石右键红石块修复线路。"),
                            80
                    );
                }
            })
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), 15);
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:repair_wiring");
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.repair_wiring"),
                            Component.literal("§a线路已修复！停电时间延迟 15 秒。"),
                            80
                    );
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