package com.habitrain.core.game.sre;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExerciseTaskBlocksTest {

    @Test
    void deadCoralFamilyIsExerciseFloor() {
        assumeBlocks();
        assertTrue(ExerciseTaskBlocks.isExerciseFloor(Blocks.DEAD_TUBE_CORAL_BLOCK));
        assertTrue(ExerciseTaskBlocks.isExerciseFloor(Blocks.DEAD_BRAIN_CORAL_BLOCK));
        assertTrue(ExerciseTaskBlocks.isExerciseFloor(Blocks.DEAD_BUBBLE_CORAL_BLOCK));
        assertTrue(ExerciseTaskBlocks.isExerciseFloor(Blocks.DEAD_FIRE_CORAL_BLOCK));
        assertTrue(ExerciseTaskBlocks.isExerciseFloor(Blocks.DEAD_HORN_CORAL_BLOCK));
    }

    @Test
    void blackConcreteIsNotExerciseFloor() {
        assumeBlocks();
        assertFalse(ExerciseTaskBlocks.isExerciseFloor(Blocks.BLACK_CONCRETE));
        assertFalse(ExerciseTaskBlocks.isExerciseFloor(Blocks.STONE));
        assertFalse(ExerciseTaskBlocks.isExerciseFloor(Blocks.AIR));
    }

    @Test
    void scannerBlackConcreteCheckMatchesOnlyDeadCoral() {
        assumeBlocks();
        assertTrue(ExerciseTaskBlocks.matchesVanillaBlackConcreteCheck(
                Blocks.DEAD_HORN_CORAL_BLOCK.defaultBlockState(), Blocks.BLACK_CONCRETE));
        assertFalse(ExerciseTaskBlocks.matchesVanillaBlackConcreteCheck(
                Blocks.BLACK_CONCRETE.defaultBlockState(), Blocks.BLACK_CONCRETE));
        assertFalse(ExerciseTaskBlocks.matchesVanillaBlackConcreteCheck(
                Blocks.STONE.defaultBlockState(), Blocks.BLACK_CONCRETE));
        assertTrue(ExerciseTaskBlocks.matchesVanillaBlackConcreteCheck(
                Blocks.NOTE_BLOCK.defaultBlockState(), Blocks.NOTE_BLOCK));
    }

    @Test
    void tickRemapSpoofsCoralAsBlackConcreteAndRejectsRealBlackConcrete() {
        assumeBlocks();
        assertTrue(ExerciseTaskBlocks.remapForVanillaTick(Blocks.DEAD_TUBE_CORAL_BLOCK)
                == Blocks.BLACK_CONCRETE);
        assertTrue(ExerciseTaskBlocks.remapForVanillaTick(Blocks.BLACK_CONCRETE)
                == Blocks.AIR);
        assertTrue(ExerciseTaskBlocks.remapForVanillaTick(Blocks.STONE)
                == Blocks.STONE);
    }

    private static void assumeBlocks() {
        try {
            assumeTrue(Blocks.BLACK_CONCRETE != null);
            assumeTrue(Blocks.DEAD_HORN_CORAL_BLOCK != null);
        } catch (Throwable t) {
            assumeTrue(false, "Blocks not bootstrapped");
        }
    }
}
