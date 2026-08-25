package com.habitrain.core.game.sre;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 锻炼 / 跑步任务方块：枯萎珊瑚块族。黑混凝土不再算作任务点。
 */
public final class ExerciseTaskBlocks {

    private ExerciseTaskBlocks() {}

    public static boolean isExerciseFloor(Block block) {
        return block == Blocks.DEAD_TUBE_CORAL_BLOCK
                || block == Blocks.DEAD_BRAIN_CORAL_BLOCK
                || block == Blocks.DEAD_BUBBLE_CORAL_BLOCK
                || block == Blocks.DEAD_FIRE_CORAL_BLOCK
                || block == Blocks.DEAD_HORN_CORAL_BLOCK;
    }

    /**
     * MapScanner {@code BlockState.is(BLACK_CONCRETE)} 替换：只让枯萎珊瑚通过。
     * 其它 {@code is(Block)} 原样转交，避免劫持售货机 / 音符盒等判断。
     */
    public static boolean matchesVanillaBlackConcreteCheck(BlockState state, Block compared) {
        if (compared == Blocks.BLACK_CONCRETE) {
            return isExerciseFloor(state.getBlock());
        }
        return state.is(compared);
    }

    /**
     * ExerciseTask.tick 里 {@code getBlock() == BLACK_CONCRETE} 的替换。
     * 珊瑚伪装成黑混凝土让上游计时继续；真黑混凝土改成空气，避免误完成。
     */
    public static Block remapForVanillaTick(Block block) {
        if (isExerciseFloor(block)) {
            return Blocks.BLACK_CONCRETE;
        }
        if (block == Blocks.BLACK_CONCRETE) {
            return Blocks.AIR;
        }
        return block;
    }
}
