package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * 停电模式坏人任务：炸毁熔炉（两阶段右键交互）。
 * <p>阶段0：右键红石火把方块 → 给缓慢III + 发放 1 个红石火把 → 进入阶段1
 * <p>阶段1：手持红石火把右键 TNT → 消耗火把 + 给 2 秒缓慢 + 推进完成 → 2 秒后点燃 TNT + 全图通报
 * <p>完成时触发原版短暂停电 + 减供电时间 40 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_BAD} 池。
 */
public class FurnaceExplosionTask {

    static final int TORCH_PHASE = 0;
    static final int TNT_PHASE = 1;
    static final int PROGRESS_DONE = 2;

    public static void register() {
        TaskRegistry.register("habitrain_core", "furnace_explosion", builder -> builder
            .displayName("炸毁熔炉")
            .category(BlackoutMode.BLACKOUT_BAD)
            .weight(3.0f)
            .blockTypeId(23)
            .instinctColor(255, 69, 0, 200)
            .scanBlocks(Blocks.TNT, Blocks.REDSTONE_TORCH)
            .onAssign((player, task) -> {
                task.setMaxProgress(PROGRESS_DONE);
            })
            .completionChecker((player, task) -> task.getProgress() >= PROGRESS_DONE)
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTimerSystem.triggerTransientBlackout(serverPlayer.serverLevel());
                    BlackoutTimerSystem.reduceMaintenanceOrCountdown(serverPlayer.serverLevel(), 40);
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:furnace_explosion");
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.furnace_explosion"),
                            Component.literal("§a熔炉已炸毁！供电时间减少 40 秒，已触发短暂停电。"),
                            80
                    );
                }
            })
            .onRemove((player, task) -> cleanup(player))
        );
    }

    private static void cleanup(Player player) {
        if (player == null) return;
        FurnaceExplosionHandler.clearState(player.getUUID());
    }
}