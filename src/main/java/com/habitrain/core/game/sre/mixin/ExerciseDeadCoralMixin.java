package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 锻炼任务（ExerciseTask）目标方块：将上游默认的黑混凝土替换为枯萎珊瑚块族（全模式）。
 */
@Mixin(SREPlayerTaskComponent.ExerciseTask.class)
public abstract class ExerciseDeadCoralMixin {

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"
            ),
            require = 0
    )
    private Block habitrain$replaceWithDeadCoral(BlockState state) {
        Block block = state.getBlock();
        if (isExerciseFloor(block)) {
            // 让后续 `== Blocks.BLACK_CONCRETE` 比较通过
            return Blocks.BLACK_CONCRETE;
        }
        // 黑混凝土不再是锻炼任务方块：让它无法通过上游的 `== BLACK_CONCRETE` 判断
        if (block == Blocks.BLACK_CONCRETE) {
            return Blocks.AIR;
        }
        return block;
    }

    private static boolean isExerciseFloor(Block block) {
        return block == Blocks.DEAD_TUBE_CORAL_BLOCK
                || block == Blocks.DEAD_BRAIN_CORAL_BLOCK
                || block == Blocks.DEAD_BUBBLE_CORAL_BLOCK
                || block == Blocks.DEAD_FIRE_CORAL_BLOCK
                || block == Blocks.DEAD_HORN_CORAL_BLOCK;
    }
}
