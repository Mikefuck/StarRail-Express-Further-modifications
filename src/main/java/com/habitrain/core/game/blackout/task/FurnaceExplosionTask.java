package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.awt.Color;

/**
 * 停电模式 — 坏人任务: 熔炉爆炸
 * 效果: 熔炉爆炸 → 总时间 +15秒 + 点燃附近TNT
 */
public class FurnaceExplosionTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "furnace_explosion", builder -> builder
            .displayName("熔炉爆炸")
            .category(BlackoutMode.BLACKOUT_BAD)
            .weight(1.0f)
            .blockTypeId(23)
            .instinctColor(new Color(255, 69, 0, 200))
            .scanBlocks(Blocks.FURNACE, Blocks.BLAST_FURNACE)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                player.sendSystemMessage(Component.literal(
                    "§c【任务】引爆熔炉，制造混乱！"));
            })
            .completionChecker((player, task) -> task.getProgress() >= 1)
            .onComplete((player, task) -> {
                BlackoutTimerSystem.addTime(15);
                if (player.level() instanceof ServerLevel serverLevel) {
                    var center = player.blockPosition();
                    for (int x = -5; x <= 5; x++) {
                        for (int y = -5; y <= 5; y++) {
                            for (int z = -5; z <= 5; z++) {
                                var targetPos = center.offset(x, y, z);
                                var state = serverLevel.getBlockState(targetPos);
                                if (state.is(Blocks.TNT)) {
                                    serverLevel.destroyBlock(targetPos, false);
                                    serverLevel.explode(null, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
                                            4.0f, Level.ExplosionInteraction.BLOCK);
                                }
                            }
                        }
                    }
                }
                player.sendSystemMessage(
                    Component.literal("§c✔ 引爆熔炉，制造混乱！总时间增加15秒！"));
            })
        );
    }
}
