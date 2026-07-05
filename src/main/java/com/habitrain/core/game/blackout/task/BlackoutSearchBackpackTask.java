package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

/**
 * 停电模式好人任务：翻找背包（复用 BackpackSearchHandler）。
 * <p>玩家右键 {@code decocraft:backpack_red} 方块后进入翻找状态（缓慢6秒），
 * 6 秒后任务自动完成。
 * <p>完成时增加供电时间 15 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutSearchBackpackTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_search_backpack", builder -> builder
            .displayName("翻找一下自己的背包...")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(35)
            .instinctColor(139, 90, 43, 200)
            .scanBlockIds("decocraft:backpack_red")
            .canAssign((player, task) ->
                !BackpackQuestState.hasCompleted(player.getUUID()))
            .onAssign((player, task) -> {
                task.setMaxProgress(120);
                if (player instanceof ServerPlayer serverPlayer) {
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.blackout_search_backpack"),
                            Component.literal("§6【任务】右键背包来翻找有用的东西！"),
                            80
                    );
                }
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                if (BackpackSearchHandler.isSearching(player.getUUID())) {
                    task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                BackpackQuestState.markCompleted(serverPlayer.getUUID());
                BackpackSearchHandler.stopSearching(serverPlayer.getUUID());
                serverPlayer.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);

                BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:blackout_search_backpack");
            })
            .onRemove((player, task) -> cleanup(player))
        );
    }

    private static void cleanup(Player player) {
        if (player == null) return;
        BackpackSearchHandler.stopSearching(player.getUUID());
        if (player instanceof ServerPlayer sp) {
            sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }
}