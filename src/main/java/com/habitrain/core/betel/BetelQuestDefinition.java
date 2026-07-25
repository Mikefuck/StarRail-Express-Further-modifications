package com.habitrain.core.betel;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskRegistry;



/**
 * 槟榔任务定义
 * 注册"感觉嘴巴缺了点东西"任务到任务API系统
 */
public class BetelQuestDefinition {

    public static void register() {
        TaskRegistry.register(HabiTrainCore.MOD_ID, "betel_quest", builder -> builder
                .displayName("你想咀嚼...")
                .category(TaskCategory.MURDER)
                .weight(1.0f)
                .blockTypeId(14)
                .instinctColor(46, 139, 87, 180)
                .scanBlockIds("betel-nut-mod:betel_palm_leaves")
                .onAssign((player, task) -> {
                    // 标记槟榔任务已在本局刷新（后续可自由采集槟榔）
                    BetelQuestState.markQuestAssigned(player.getUUID());
                    BetelQuestState.resetEatenStatus(player);
                })
                .completionChecker((player, task) -> {
                    return BetelQuestState.hasPlayerEatenBetelNut(player.getUUID());
                })
                .onComplete((player, task) -> {
                    // 不再发送「你满足了对槟榔的渴望」TOP 字幕
                })
                .canAssign((player, task) -> {
                    return true;
                })
        );

        HabiTrainCore.LOGGER.info("已注册槟榔任务: 感觉嘴巴缺了点东西");
    }
}
