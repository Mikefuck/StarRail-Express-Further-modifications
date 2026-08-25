package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomTaskBlockScanTrackerTest {

    private static final long DEDUP_WINDOW_MS = 5_000L;

    @Test
    void completedSnapshotIsNotCurrentAfterMapChangesWithSameBounds() {
        CustomTaskBlockScanTracker tracker = new CustomTaskBlockScanTracker(DEDUP_WINDOW_MS);
        CustomTaskBlockScanKey testMap = key("test");
        CustomTaskBlockScanKey mapTwo = key("map2");

        tracker.markCompleted(testMap, 1_000L);

        assertFalse(tracker.isCurrent(mapTwo, false));
        assertFalse(tracker.shouldSkipScan(mapTwo, false, 1_100L));
    }

    @Test
    void completedSnapshotCanBeRebroadcastForSameMapAndBounds() {
        CustomTaskBlockScanTracker tracker = new CustomTaskBlockScanTracker(DEDUP_WINDOW_MS);
        CustomTaskBlockScanKey mapTwo = key("map2");

        tracker.markCompleted(mapTwo, 1_000L);

        assertTrue(tracker.isCurrent(mapTwo, false));
        assertTrue(tracker.shouldSkipScan(mapTwo, false, 1_100L));
        assertFalse(tracker.shouldSkipScan(mapTwo, false, 6_001L));
        assertFalse(tracker.isCurrent(mapTwo, true));
    }

    @Test
    void completedSnapshotIsNotCurrentAfterDimensionOrBoundsChange() {
        CustomTaskBlockScanTracker tracker = new CustomTaskBlockScanTracker(DEDUP_WINDOW_MS);
        CustomTaskBlockScanKey overworld = key("map2");

        tracker.markCompleted(overworld, 1_000L);

        assertFalse(tracker.isCurrent(new CustomTaskBlockScanKey(
                "minecraft:the_nether", "map2", 10, 20, 30, 110, 70, 130), false));
        assertFalse(tracker.isCurrent(new CustomTaskBlockScanKey(
                "minecraft:overworld", "map2", 11, 20, 30, 111, 70, 130), false));
    }

    private static CustomTaskBlockScanKey key(String mapName) {
        return new CustomTaskBlockScanKey(
                "minecraft:overworld", mapName, 10, 20, 30, 110, 70, 130);
    }
}
