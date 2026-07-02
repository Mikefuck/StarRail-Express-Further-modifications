package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.network.chat.Component;



/**
 * 停电模式 — 坏人任务: 破坏线路
 * 效果: 破坏电线 → 立即触发停电7秒
 */
public class SabotageWiringTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "sabotage_wiring", builder -> builder
            .displayName("破坏线路")
            .category(BlackoutMode.BLACKOUT_BAD)
            .weight(1.0f)
            .blockTypeId(22)
            .instinctColor(255, 0, 0, 200)
            .scanBlockIds("minecraft:redstone_wire")
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                player.sendSystemMessage(Component.literal(
                    "§c【任务】破坏线路，让列车停电！"));
            })
            .completionChecker((player, task) -> task.getProgress() >= 1)
            .onComplete((player, task) -> {
                BlackoutTimerSystem.triggerTransientBlackout();
                player.sendSystemMessage(
                    Component.literal("§c✔ 破坏了线路，触发短暂停电！"));
            })
        );
    }
}
