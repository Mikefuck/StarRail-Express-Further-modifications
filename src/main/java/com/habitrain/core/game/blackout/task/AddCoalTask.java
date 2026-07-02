package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;



/**
 * 停电模式 — 好人任务: 添加煤炭
 * 效果: 采集煤矿方块 → 总时间减少30秒
 */
public class AddCoalTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "add_coal", builder -> builder
            .displayName("添加煤炭")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(1.0f)
            .blockTypeId(20)
            .instinctColor(50, 50, 50, 200)
            .scanBlocks(Blocks.COAL_BLOCK, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                player.sendSystemMessage(Component.literal(
                    "§6【任务】找到煤矿，给锅炉添加煤炭！"));
            })
            .completionChecker((player, task) -> task.getProgress() >= 1)
            .onComplete((player, task) -> {
                BlackoutTimerSystem.reduceTime(30);
                player.sendSystemMessage(
                    Component.literal("§a✔ 找到了煤矿，给锅炉添加煤炭！总时间减少30秒！"));
            })
        );
    }
}
