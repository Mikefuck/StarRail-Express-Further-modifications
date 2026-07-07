package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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

                if (RestorePowerHandler.isRestoreCompleted()) {
                    return;
                }

                RestorePowerHandler.markRestoreCompleted();

                // 通过 applyTimeImpact 调用 restorePower（替代硬编码 BlackoutTimerSystem.restorePower(level)）
                BlackoutTaskHelper.applyTimeImpact(level, "habitrain_core:restore_power");

                BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:restore_power");
                SubtitleNotifier.sendTop(
                        serverPlayer,
                        Component.translatable("task.restore_power"),
                        Component.literal("§a电力已恢复！供电维护阶段开始。"),
                        80
                );

                // 同步完成其它正在做 restore_power 的 GOOD 玩家
                completeAllGoodPlayers(level, serverPlayer.getUUID());

                // 不再强制派发 maintain_power 给所有 GOOD 玩家（旧逻辑会无差别移除所有人当前任务）。
                // 任务刷新交给自然刷新机制：每个玩家下次刷新任务时自动从供电池池里抽。
                // 想做 maintain_power 同步派发？改用 SupplyTaskSyncHelper.syncCompletion 即可。
            })
            .onRemove((player, task) -> {
                if (player != null) RestorePowerHandler.clearState(player.getUUID());
            })
        );
    }

    private static void completeAllGoodPlayers(ServerLevel level, UUID completerUuid) {
        TaskManager mgr = TaskManager.getInstance();
        List<UUID> alive = BlackoutRoleManager.getAllAlive(level);
        for (UUID uuid : alive) {
            if (uuid.equals(completerUuid)) continue;
            if (BlackoutRoleManager.getFaction(level, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            TaskInstance task = mgr.getActiveTask(uuid);
            if (task == null || !"habitrain_core:restore_power".equals(task.getFullId())) continue;
            if (task.isFulfilled()) continue;

            task.setFulfilled(true);
            task.getDefinition().onComplete(
                    level.getServer().getPlayerList().getPlayer(uuid), task);
            RestorePowerHandler.clearState(uuid);
        }
    }
}