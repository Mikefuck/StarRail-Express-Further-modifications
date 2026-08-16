package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.blackout.shop.BlackoutTaskShopState;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

public class RestorePowerTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "restore_power", builder -> builder
            .displayName("恢复供电")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(41)
            .instinctColor(255, 255, 0, 200)
            .scanBlocks(Blocks.LEVER)
            // 声明时间影响：恢复供电（从停电拉回维护期）
            .timeImpact(TaskDefinition.TimeImpact.TimeAxis.RESTORE_POWER, 0)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
            })
            .onTick((player, task) -> {
            })
            .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (!(player instanceof ServerPlayer serverPlayer)) return;
                if (!(serverPlayer.level() instanceof ServerLevel level)) return;

                if (RestorePowerHandler.isRestoreCompleted(level)) {
                    return;
                }

                // 第二次永久停电（SECOND_BLACKOUT）无法再恢复供电：不允许假完成。
                if (BlackoutTimerSystem.getPhase(level) != BlackoutTimerSystem.Phase.FIRST_BLACKOUT) {
                    com.habitrain.core.HabiTrainCore.LOGGER.warn(
                            "[RestorePower] restore completed outside FIRST_BLACKOUT phase={}, skipping rewards/effects",
                            BlackoutTimerSystem.getPhase(level));
                    RestorePowerHandler.clearState(serverPlayer.getUUID());
                    return;
                }

                RestorePowerHandler.markRestoreCompleted(level);
                // 本局已用掉一次恢复供电（第二次永久停电无法再恢复）
                BlackoutTaskShopState.markRestoreUsed(level);

                // 通过 applyTimeImpact 调用 restorePower（替代硬编码 BlackoutTimerSystem.restorePower(level)）
                BlackoutTaskHelper.applyTimeImpact(level, com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_RESTORE_POWER);

                // 仅完成者拿奖励
                BlackoutTaskHelper.grantRewards(serverPlayer, com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_RESTORE_POWER);
                // 恢复提示由 BlackoutTimerSystem.restorePower 统一广播，此处不发个人 toast。

                // 同步完成其它正在做 restore_power 的 GOOD 玩家（仅清理状态，不发奖、不重复 restorePower）
                completeAllGoodPlayers(level, serverPlayer.getUUID());
            })
            .onRemove((player, task) -> {
                if (player != null) RestorePowerHandler.clearState(player.getUUID());
            })
        );
    }

    /**
     * 同步完成其它正在做 restore_power 的 GOOD 玩家。
     * 仅清理状态（setFulfilled + onRemove + clearState），不调用 onComplete —— 不发奖、
     * 不重复 restorePower（时间效果由真正完成者施加一次）。
     */
    private static void completeAllGoodPlayers(ServerLevel level, UUID completerUuid) {
        TaskManager mgr = TaskManager.getInstance();
        List<UUID> alive = BlackoutRoleManager.getAllAlive(level);
        for (UUID uuid : alive) {
            if (uuid.equals(completerUuid)) continue;
            if (BlackoutRoleManager.getFaction(level, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            TaskInstance task = mgr.getActiveTask(uuid);
            if (task == null || !com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_RESTORE_POWER.equals(task.getFullId())) continue;
            if (task.isFulfilled()) continue;

            task.setFulfilled(true);
            ServerPlayer other = level.getServer().getPlayerList().getPlayer(uuid);
            try {
                task.getDefinition().onRemove(other, task);
            } catch (Throwable t) {
                com.habitrain.core.HabiTrainCore.LOGGER.error(
                        "[RestorePower] onRemove failed for synced player {}", uuid, t);
            }
            RestorePowerHandler.clearState(uuid);
            // 不调 onComplete — 不发奖、不重复 restorePower
        }
    }
}