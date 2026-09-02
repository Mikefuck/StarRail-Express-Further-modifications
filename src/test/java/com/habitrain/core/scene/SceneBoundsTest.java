package com.habitrain.core.scene;

import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneBoundsTest {

    @Test
    public void testEmptyBounds() {
        SceneBounds empty = SceneBounds.EMPTY;
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.volume());
        assertEquals(0, empty.totalSections());
    }

    @Test
    public void testNormalizedBounds() {
        // Point A = (10, 20, 30), Point B = (5, 40, 10)
        SceneBounds bounds = SceneBounds.fromPoints(new BlockPos(10, 20, 30), new BlockPos(5, 40, 10));
        assertFalse(bounds.isEmpty());
        assertEquals(5, bounds.minX());
        assertEquals(20, bounds.minY());
        assertEquals(10, bounds.minZ());
        assertEquals(11, bounds.maxX());
        assertEquals(41, bounds.maxY());
        assertEquals(31, bounds.maxZ());

        assertEquals(6, bounds.sizeX()); // 10 - 5 + 1
        assertEquals(21, bounds.sizeY()); // 40 - 20 + 1
        assertEquals(21, bounds.sizeZ()); // 30 - 10 + 1
        assertEquals(6 * 21 * 21, bounds.volume());
    }

    @Test
    public void testSectionCalculation() {
        // Section aligned bounds: (0, 0, 0) to (16, 16, 16) -> block coords 0..15 -> exactly 1 section
        SceneBounds singleSec = new SceneBounds(0, 0, 0, 16, 16, 16);
        assertEquals(1, singleSec.totalSections());

        // Spanning across section borders (32x32x32 -> 2x2x2 = 8 sections)
        SceneBounds multiSec = new SceneBounds(0, 0, 0, 32, 32, 32);
        assertEquals(2, multiSec.sectionsX());
        assertEquals(2, multiSec.sectionsY());
        assertEquals(2, multiSec.sectionsZ());
        assertEquals(8, multiSec.totalSections());
    }

    @Test
    public void testContains() {
        SceneBounds bounds = new SceneBounds(10, 10, 10, 20, 20, 20);
        assertTrue(bounds.contains(10, 15, 19));
        assertTrue(bounds.contains(15, 15, 15));
        assertFalse(bounds.contains(9, 15, 15));
        assertFalse(bounds.contains(15, 20, 15));
    }
}
