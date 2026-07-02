package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;



public class MaintainPowerTask {
    public static void register() {
        TaskRegistry.register("habitrain_core", "maintain_power", builder -> builder
            .displayName("维护供电")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(1.0f)
            .blockTypeId(-1)
            .instinctColor(0, 200, 255, 200)
            .scanBlocks(Blocks.REDSTONE_LAMP)
            .onComplete((player, task) -> {
                BlackoutTimerSystem.delayMaintenanceOrCountdown(15);
                player.sendSystemMessage(
                    Component.literal("§a✔ 维护了供电系统，供电时间延长15秒！"));
            })
            .completionChecker((player, task) -> task.isFulfilled())
        );
    }
}
