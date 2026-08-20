package com.habitrain.core.game.sre.mixin;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.agmas.noellesroles.utils.MapScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MapScanner 扫描锻炼任务高亮点时，用枯萎珊瑚块族替换上游默认黑混凝土（instinct id 5）。
 * <p>上游 4.3.0 已将实际方块判断拆到 testTaskBlocksAndAddToGameUtils，
 * 因此 Redirect 必须指向该方法，否则扫描逻辑不会命中（require=0 会静默失效）。
 */
@Mixin(MapScanner.class)
public abstract class ExerciseMapScannerMixin {

    @Redirect(
            method = "testTaskBlocksAndAddToGameUtils",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"
            ),
            require = 1
    )
    private static boolean habitrain$matchExerciseBlocks(BlockState state, net.minecraft.world.level.block.Block block) {
        // 原比较目标是 BLACK_CONCRETE 时：兼容原版黑混凝土与枯萎珊瑚族
        if (block == Blocks.BLACK_CONCRETE) {
            var b = state.getBlock();
            return state.is(Blocks.BLACK_CONCRETE)
                    || b == Blocks.DEAD_TUBE_CORAL_BLOCK
                    || b == Blocks.DEAD_BRAIN_CORAL_BLOCK
                    || b == Blocks.DEAD_BUBBLE_CORAL_BLOCK
                    || b == Blocks.DEAD_FIRE_CORAL_BLOCK
                    || b == Blocks.DEAD_HORN_CORAL_BLOCK;
        }
        return state.is(block);
    }
}
