package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 停电模式好人任务：一个人静静（周围 6 格内无其他玩家持续 10 秒）。
 * <p>玩家周围 6 格水平 / 3 格纵向范围内无其他玩家时，每秒进度 +1，累计 10 秒完成。
 * 进度不回退，有人靠近时暂停累积，走开后继续。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutBeAloneTask {

    private static final double HORIZONTAL_RADIUS = 6.0;
    private static final double VERTICAL_RADIUS = 3.0;
    private static final int REQUIRED_TICKS = 10 * 20;

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_be_alone", builder -> builder
            .displayName("一个人静静")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(-1)
            .instinctColor(100, 149, 237, 200)
            .onAssign((player, task) -> {
                task.setMaxProgress(REQUIRED_TICKS);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                AABB box = serverPlayer.getBoundingBox().inflate(HORIZONTAL_RADIUS, VERTICAL_RADIUS, HORIZONTAL_RADIUS);
                boolean alone = serverPlayer.level().getEntitiesOfClass(Player.class, box,
                        other -> other != serverPlayer && other.isAlive() && !other.isSpectator()).isEmpty();

                if (alone) {
                    int newProgress = Math.min(task.getProgress() + 1, task.getMaxProgress());
                    if (newProgress != task.getProgress()) {
                        task.setProgress(newProgress);
                    }
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:blackout_be_alone");
                }
            })
        );
    }
}
