package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 锻炼任务（ExerciseTask）目标方块：在黑混凝土之外，接受枯萎珊瑚块族（全模式）。
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
    private Block habitrain$acceptDeadCoral(BlockState state) {
        Block block = state.getBlock();
        if (isExerciseFloor(block)) {
            // 让后续 `== Blocks.BLACK_CONCRETE` 比较通过
            return Blocks.BLACK_CONCRETE;
        }
        return block;
    }

    private static boolean isExerciseFloor(Block block) {
        return block == Blocks.BLACK_CONCRETE
                || block == Blocks.DEAD_TUBE_CORAL_BLOCK
                || block == Blocks.DEAD_BRAIN_CORAL_BLOCK
                || block == Blocks.DEAD_BUBBLE_CORAL_BLOCK
                || block == Blocks.DEAD_FIRE_CORAL_BLOCK
                || block == Blocks.DEAD_HORN_CORAL_BLOCK;
    }
}
