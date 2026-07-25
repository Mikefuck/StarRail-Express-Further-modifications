package com.habitrain.core.game.sre.mixin;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.agmas.noellesroles.utils.MapScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MapScanner 扫描锻炼任务高亮点时，除黑混凝土外也识别枯萎珊瑚块（instinct id 5）。
 */
@Mixin(MapScanner.class)
public abstract class ExerciseMapScannerMixin {

    @Redirect(
            method = "scanAllTaskBlocks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"
            ),
            require = 0
    )
    private static boolean habitrain$matchExerciseBlocks(BlockState state, net.minecraft.world.level.block.Block block) {
        if (state.is(block)) return true;
        // 仅当原比较目标是 BLACK_CONCRETE 时扩展到枯萎珊瑚
        if (block == Blocks.BLACK_CONCRETE) {
            var b = state.getBlock();
            return b == Blocks.DEAD_TUBE_CORAL_BLOCK
                    || b == Blocks.DEAD_BRAIN_CORAL_BLOCK
                    || b == Blocks.DEAD_BUBBLE_CORAL_BLOCK
                    || b == Blocks.DEAD_FIRE_CORAL_BLOCK
                    || b == Blocks.DEAD_HORN_CORAL_BLOCK;
        }
        return false;
    }
}
