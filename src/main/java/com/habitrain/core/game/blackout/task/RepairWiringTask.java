package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.awt.Color;

public class RepairWiringTask {
    public static void register() {
        TaskRegistry.register("habitrain_core", "repair_wiring", builder -> builder
            .displayName("维修线路")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(1.0f)
            .blockTypeId(-1)
            .instinctColor(new Color(255, 215, 0, 200))
            .scanBlocks(Blocks.REDSTONE_BLOCK)
            .onComplete((player, task) -> {
                // 如果在第一次永久停电阶段，恢复供电
                BlackoutTimerSystem.restorePower();
                player.sendSystemMessage(Component.literal("§a✔ 维修了线路，恢复供电！"));
            })
            .completionChecker((player, task) -> task.isFulfilled())
        );
    }
}
