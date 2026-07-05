package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 停电模式好人任务：一个人静静（远离所有玩家持续60秒）。
 * <p>玩家与其他所有玩家距离均 &gt;15 格时，每秒进度 +1，累计 60 秒完成。
 * 进度只暂停累积不回退，避免玩家靠近一下就归零太苛刻。
 * <p>完成时增加供电时间 20 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutBeAloneTask {

    private static final double MIN_DISTANCE = 15.0;
    private static final int REQUIRED_SECONDS = 60;

    private static final Map<UUID, Integer> tickCounters = new HashMap<>();

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_be_alone", builder -> builder
            .displayName("一个人静静")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(-1)
            .instinctColor(100, 149, 237, 200)
            .onAssign((player, task) -> {
                task.setMaxProgress(REQUIRED_SECONDS);
                tickCounters.put(player.getUUID(), 0);
                if (player instanceof ServerPlayer serverPlayer) {
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.blackout_be_alone"),
                            Component.literal("§6【任务】远离所有人，独自待上 60 秒。"),
                            80
                    );
                }
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                int counter = tickCounters.getOrDefault(player.getUUID(), 0) + 1;
                if (counter < 20) {
                    tickCounters.put(player.getUUID(), counter);
                    return;
                }
                tickCounters.put(player.getUUID(), 0);

                boolean alone = true;
                for (ServerPlayer other : serverPlayer.serverLevel().players()) {
                    if (other == serverPlayer || !other.isAlive()) continue;
                    if (serverPlayer.distanceToSqr(other) <= MIN_DISTANCE * MIN_DISTANCE) {
                        alone = false;
                        break;
                    }
                }

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
                    tickCounters.remove(player.getUUID());
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:blackout_be_alone");
                }
            })
            .onRemove((player, task) -> tickCounters.remove(player.getUUID()))
        );
    }
}