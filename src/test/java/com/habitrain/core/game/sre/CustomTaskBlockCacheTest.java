package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CustomTaskBlockCacheTest {

    @BeforeEach
    @AfterEach
    void resetCache() {
        CustomTaskBlockCache.clear();
    }

    @Test
    void putRefusesAfterDefaultTypeCap() {
        int cap = CustomTaskBlockIndexLimits.DEFAULT_TYPE_CAP;
        for (int i = 0; i < cap; i++) {
            assertTrue(CustomTaskBlockCache.put(new BlockPos(i, 0, 0), 20));
        }
        assertFalse(CustomTaskBlockCache.put(new BlockPos(cap, 0, 0), 20));
        assertEquals(cap, CustomTaskBlockCache.size());
        assertEquals(cap, CustomTaskBlockCache.positionsForType(20).size());
        assertTrue(CustomTaskBlockCache.positionsForType(21).isEmpty());
    }

    @Test
    void putRefusesAfterGlobalCap() {
        int phoneType = BlackoutOverlayTypes.STREET_PHONE;
        int global = CustomTaskBlockCache.MAX_ENTRIES;
        for (int i = 0; i < global; i++) {
            assertTrue(CustomTaskBlockCache.put(new BlockPos(i, 1, 0), phoneType));
        }
        assertFalse(CustomTaskBlockCache.put(new BlockPos(global, 1, 0), phoneType));
        assertEquals(global, CustomTaskBlockCache.size());
    }

    @Test
    void bucketsReturnOnlyThatType() {
        BlockPos coal = new BlockPos(1, 2, 3);
        BlockPos other = new BlockPos(4, 5, 6);
        assertTrue(CustomTaskBlockCache.put(coal, 20));
        assertTrue(CustomTaskBlockCache.put(coal, 21));
        assertTrue(CustomTaskBlockCache.put(other, 21));

        Set<BlockPos> type20 = CustomTaskBlockCache.positionsForType(20);
        Set<BlockPos> type21 = CustomTaskBlockCache.positionsForType(21);
        assertEquals(Set.of(coal), Set.copyOf(type20));
        assertEquals(Set.of(coal, other), Set.copyOf(type21));
        assertEquals(Set.of(coal, other), CustomTaskBlockCache.positionsForTypes(20, 21));
        assertTrue(CustomTaskBlockCache.positionsForType(99).isEmpty());
    }

    @Test
    void snapshotLoadRestoresEntriesAndBuckets() {
        BlockPos a = new BlockPos(1, 2, 3);
        BlockPos b = new BlockPos(4, 5, 6);
        CustomTaskBlockCache.put(a, 20);
        CustomTaskBlockCache.put(a, 21);
        CustomTaskBlockCache.put(b, 21);

        Map<BlockPos, Set<Integer>> snapshot = CustomTaskBlockCache.snapshot();
        CustomTaskBlockCache.clear();
        assertTrue(CustomTaskBlockCache.isEmpty());

        CustomTaskBlockCache.loadFromSnapshot(snapshot);

        assertEquals(2, CustomTaskBlockCache.size());
        assertEquals(Set.of(20, 21), CustomTaskBlockCache.get(a));
        assertEquals(Set.of(21), CustomTaskBlockCache.get(b));
        assertEquals(Set.of(a), Set.copyOf(CustomTaskBlockCache.positionsForType(20)));
        assertEquals(Set.of(a, b), Set.copyOf(CustomTaskBlockCache.positionsForType(21)));
    }

    @Test
    void bulkBlockCapUsesCoalWhenMinecraftBlocksAvailable() {
        net.minecraft.world.level.block.Block coal;
        try {
            coal = net.minecraft.world.level.block.Blocks.COAL_BLOCK;
        } catch (Throwable t) {
            assumeTrue(false, "Blocks not bootstrapped");
            return;
        }
        assumeTrue(coal != null);

        int cap = CustomTaskBlockIndexLimits.BULK_BLOCK_CAP;
        for (int i = 0; i < cap; i++) {
            assertTrue(CustomTaskBlockCache.put(new BlockPos(i, 2, 0), 20, coal));
        }
        assertFalse(CustomTaskBlockCache.put(new BlockPos(cap, 2, 0), 20, coal));
        assertEquals(cap, CustomTaskBlockCache.positionsForType(20).size());

        assertTrue(CustomTaskBlockCache.put(new BlockPos(0, 3, 0), 21));
        assertEquals(cap + 1, CustomTaskBlockCache.size());
    }

    @Test
    void snapshotLoadRestoresUniqueScanBlockWhenAvailable() {
        net.minecraft.world.level.block.Block stone;
        try {
            stone = net.minecraft.world.level.block.Blocks.STONE;
        } catch (Throwable t) {
            assumeTrue(false, "Blocks not bootstrapped");
            return;
        }
        assumeTrue(stone != null);

        String taskId = "cache_restore_" + UUID.randomUUID();
        int typeId = 77;
        TaskRegistry.register("habitrain_test", taskId, builder -> builder
                .blockTypeId(typeId)
                .scanBlocks(stone));

        BlockPos pos = new BlockPos(8, 8, 8);
        assertTrue(CustomTaskBlockCache.put(pos, typeId));
        assertNull(CustomTaskBlockCache.getBlockAt(pos));

        Map<BlockPos, Set<Integer>> snapshot = CustomTaskBlockCache.snapshot();
        CustomTaskBlockCache.clear();
        CustomTaskBlockCache.loadFromSnapshot(snapshot);

        assertEquals(stone, CustomTaskBlockCache.getBlockAt(pos));
        assertEquals(Set.of(pos), Set.copyOf(CustomTaskBlockCache.positionsForType(typeId)));
    }
}
