package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.betel.BetelTaskFacade;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 停电模式好人任务：你想咀嚼...（复用 BetelQuestState 槟榔系统）。
 * <p>玩家吃下槟榔后任务完成。
 * <p>完成时增加供电时间 15 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutBetelQuestTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_betel_quest", builder -> builder
            .displayName("你想咀嚼...")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(36)
            .instinctColor(46, 139, 87, 180)
            .scanBlockIds("betel-nut-mod:betel_palm_leaves")
            .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 15)
            .onAssign((player, task) -> {
                BetelTaskFacade.markQuestAssigned(player);
                BetelTaskFacade.resetEatenStatus((ServerPlayer) player);
            })
            .completionChecker((player, task) ->
                BetelTaskFacade.hasPlayerEatenBetelNut(player))
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTaskHelper.applyTimeImpact(serverPlayer.serverLevel(),
                            "habitrain_core:blackout_betel_quest");
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:blackout_betel_quest");
                }
            })
        );
    }
}
