package com.habitrain.core.game.sre;

import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CustomTaskBlockIndexLimitsTest {

    @Test
    void phoneTypesAreUncapped() {
        assertTrue(CustomTaskBlockIndexLimits.isUncappedType(BlackoutOverlayTypes.STREET_PHONE));
        assertTrue(CustomTaskBlockIndexLimits.isUncappedType(BlackoutOverlayTypes.ROTARY_PHONE_RED));
        assertEquals(Integer.MAX_VALUE,
                CustomTaskBlockIndexLimits.capFor(null, BlackoutOverlayTypes.STREET_PHONE));
    }

    @Test
    void defaultTypeCapAppliesWithoutBlock() {
        assertEquals(CustomTaskBlockIndexLimits.DEFAULT_TYPE_CAP,
                CustomTaskBlockIndexLimits.capFor(null, 20));
        assertFalse(CustomTaskBlockIndexLimits.isUncappedType(20));
    }

    @Test
    void acceptHelpersMatchCaps() {
        assertTrue(CustomTaskBlockIndexLimits.acceptGroup(47, CustomTaskBlockIndexLimits.BULK_BLOCK_CAP));
        assertFalse(CustomTaskBlockIndexLimits.acceptGroup(48, CustomTaskBlockIndexLimits.BULK_BLOCK_CAP));
        assertTrue(CustomTaskBlockIndexLimits.acceptNewPosition(0, CustomTaskBlockIndexLimits.GLOBAL_MAX_ENTRIES));
        assertFalse(CustomTaskBlockIndexLimits.acceptNewPosition(
                CustomTaskBlockIndexLimits.GLOBAL_MAX_ENTRIES,
                CustomTaskBlockIndexLimits.GLOBAL_MAX_ENTRIES));
    }

    @Test
    void spectatorDrawsSparseCustomTypesIncludingCatsAndPhones() {
        assertTrue(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, Set.of(13)));
        assertTrue(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, Set.of(37)));
        assertTrue(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, Set.of(BlackoutOverlayTypes.STREET_PHONE)));
        assertTrue(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, Set.of(BlackoutOverlayTypes.ROTARY_PHONE_RED)));
        assertTrue(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, Set.of(15, 20)));
    }

    @Test
    void spectatorSkipsVanillaTypeIdsAndEmptySetsWithoutBootstrap() {
        assertFalse(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, Set.of(11)));
        assertFalse(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, Set.of()));
        assertFalse(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(
                null, null));
    }

    @Test
    void spectatorSkipsBulkDecorativeWhenBlocksAreBootstrapped() {
        Block coal = tryBlock(() -> Blocks.COAL_BLOCK);
        Block tnt = tryBlock(() -> Blocks.TNT);
        Block torch = tryBlock(() -> Blocks.REDSTONE_TORCH);
        assumeTrue(coal != null && tnt != null && torch != null, "Blocks not bootstrapped");

        assertFalse(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(coal, Set.of(20)));
        assertFalse(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(tnt, Set.of(23)));
        assertFalse(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(torch, Set.of(23)));
    }

    @Test
    void spectatorKeepsGeneratorWhenSharingTypeIdWithCoal() {
        Block coal = tryBlock(() -> Blocks.COAL_BLOCK);
        Block furnace = tryBlock(() -> Blocks.FURNACE);
        assumeTrue(coal != null && furnace != null, "Blocks not bootstrapped");

        assertTrue(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(furnace, Set.of(20)));
        assertFalse(CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(coal, Set.of(20)));
    }

    private static Block tryBlock(java.util.function.Supplier<Block> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return null;
        }
    }
}
