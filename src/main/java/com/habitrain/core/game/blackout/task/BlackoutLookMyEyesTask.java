package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 停电模式好人任务：LOOK MY EYES（与另一玩家7秒对视）。
 * <p>玩家与3米内的另一玩家双向注视7秒（140 tick）后完成。
 * 中断对视则进度归零。
 * <p>完成时发放默认金币 / 情绪奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutLookMyEyesTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_look_my_eyes", builder -> builder
            .displayName("LOOK MY EYES")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(-1)
            .instinctColor(255, 105, 180, 200)
            .onAssign((player, task) -> {
                task.setMaxProgress(140);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                // 与 BuiltinTaskRegistrar.look_my_eyes 对齐：每 5 tick 扫实体一次
                if (serverPlayer.tickCount % 5 != 0) return;

                Vec3 eyePos = serverPlayer.getEyePosition();
                AABB searchBox = serverPlayer.getBoundingBox().inflate(3.0);
                List<ServerPlayer> nearby = serverPlayer.serverLevel()
                        .getEntitiesOfClass(ServerPlayer.class, searchBox,
                                p -> p != serverPlayer && p.isAlive());

                Vec3 lookVec = serverPlayer.getLookAngle();
                boolean eyeContact = false;

                for (ServerPlayer otherPlayer : nearby) {
                    Vec3 toOther = otherPlayer.getEyePosition().subtract(eyePos);
                    double distance = toOther.length();
                    if (distance > 3.0) continue;

                    Vec3 dirToOther = toOther.normalize();
                    Vec3 otherLookVec = otherPlayer.getLookAngle();
                    Vec3 dirToThis = eyePos.subtract(otherPlayer.getEyePosition()).normalize();

                    double dotThis = lookVec.dot(dirToOther);
                    double dotOther = otherLookVec.dot(dirToThis);

                    if (dotThis > 0.8 && dotOther > 0.8) {
                        eyeContact = true;
                        break;
                    }
                }

                if (eyeContact) {
                    // +5 抵消 5-tick 节流 → 有效进度 1/tick → 140 tick = 7 秒
                    task.setProgress(Math.min(task.getProgress() + 5, task.getMaxProgress()));
                } else {
                    if (task.getProgress() > 0) {
                        task.setProgress(0);
                    }
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTaskHelper.grantRewards(serverPlayer, HabiTrainCore.TASK_BLACKOUT_LOOK_MY_EYES);
                }
            })
        );
    }
}