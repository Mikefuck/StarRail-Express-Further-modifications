package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 停电模式好人任务：维持供电（右键发电机触发延迟奖励）。
 * <p>玩家右键发电机 → 获得 10 秒缓慢 → 缓慢结束后发放奖励 + 断电倒计时增加 80 秒。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class MaintainPowerTask {
    public static void register() {
        TaskRegistry.register("habitrain_core", "maintain_power", builder -> builder
            .displayName("维持供电")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(38)
            .instinctColor(0, 200, 255, 200)
            .scanBlockIds("yuushya:generator")
            // 声明时间影响：完成后增加停电倒计时/维护时间 80 秒。
            // 自适应刷新概率会从 delta=80 派生阈值（low=60s, high=240s）。
            .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 80)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
            })
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    // 通过 applyTimeImpact 统一调用（替代硬编码 delayMaintenanceOrCountdown(level, 80)）
                    BlackoutTaskHelper.applyTimeImpact(serverPlayer.serverLevel(), "habitrain_core:maintain_power");
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:maintain_power");
                    // 同步完成其它正在做 maintain_power 的 GOOD 玩家
                    SupplyTaskSyncHelper.syncCompletion(
                            serverPlayer.serverLevel(), serverPlayer.getUUID(), "habitrain_core:maintain_power");
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