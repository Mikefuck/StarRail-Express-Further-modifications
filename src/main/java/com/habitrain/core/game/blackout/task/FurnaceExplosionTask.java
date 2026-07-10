package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.blackout.shop.BlackoutTaskShopState;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * 停电模式坏人任务：炸毁发电机（两阶段右键交互）。
 * <p>阶段0：右键红石火把方块 → 给缓慢III + 发放 1 个红石火把 → 进入阶段1
 * <p>阶段1：手持红石火把右键 TNT → 消耗火把 + 推进完成 → 2 秒后点燃 TNT + 全图通报
 * <p>完成时：触发永久停电（若本局尚未恢复过供电）→ 强制派发恢复供电给所有好人；
 *          减少 40 秒供电时间 + 发放奖励。全局只能完成一次。
 * <p>属于 {@link BlackoutMode#BLACKOUT_BAD} 池（仅通过红色电话商店购买）。
 */
public class FurnaceExplosionTask {

    static final int TORCH_PHASE = 0;
    static final int TNT_PHASE = 1;
    static final int PROGRESS_DONE = 2;

    public static void register() {
        TaskRegistry.register("habitrain_core", "furnace_explosion", builder -> builder
            .displayName("炸毁发电机")
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
                    ServerLevel level = serverPlayer.serverLevel();

                    // 标记发电机已毁：好人供电三件套隐藏，临时电源上架
                    BlackoutTaskShopState.markGeneratorDestroyed(level);

                    // 减供电时间 40 秒
                    BlackoutTimerSystem.reduceMaintenanceOrCountdown(level, 40);

                    // 触发永久停电 + 强制给好人派发恢复供电（仅本局未恢复过时）
                    if (!BlackoutTaskShopState.isRestoreUsed(level)) {
                        triggerGeneratorPermanentBlackout(level);
                    } else {
                        // 已恢复过一次 → 第二次停电无法恢复，但仍做短暂停电视觉
                        BlackoutTimerSystem.triggerTransientBlackout(level);
                    }

                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:furnace_explosion");
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.furnace_explosion"),
                            Component.literal("§a发电机已炸毁！供电时间减少 40 秒。"),
                            80
                    );
                }
            })
            .onRemove((player, task) -> cleanup(player))
        );
    }

    /** 触发永久停电并强制给所有存活好人派发恢复供电。 */
    private static void triggerGeneratorPermanentBlackout(ServerLevel level) {
        try {
            var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(level);
            if (blackout != null) {
                blackout.triggerBlackout(true, 600000);
            }
        } catch (Throwable t) {
            com.habitrain.core.HabiTrainCore.LOGGER.error("triggerGeneratorPermanentBlackout: SRE blackout failed", t);
        }
        com.habitrain.core.game.blackout.task.RestorePowerHandler.resetCompleted(level);
        // 通过当前激活的 BlackoutMode 委派给胜利检查器的强制派发逻辑
        var gm = com.habitrain.core.api.GameModeRegistry.getActiveForLevel(level).orElse(null);
        if (gm instanceof com.habitrain.core.game.blackout.BlackoutMode bm) {
            bm.forceAssignRestorePower(level);
        }
    }

    private static void cleanup(Player player) {
        if (player == null) return;
        FurnaceExplosionHandler.clearState(player.getUUID());
    }
}