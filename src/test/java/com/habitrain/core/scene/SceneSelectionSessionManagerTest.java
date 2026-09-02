package com.habitrain.core.scene;

import com.habitrain.core.scene.server.SceneSelectionSessionManager;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneSelectionSessionManagerTest {
    private final SceneSelectionSessionManager manager = SceneSelectionSessionManager.getInstance();

    @AfterEach
    void clearSessions() {
        manager.clearAll();
    }

    @Test
    void retargetKeepsSameMapSelectionAndClearsItWhenMapChanges() {
        UUID playerId = UUID.randomUUID();
        manager.handleShiftRightClickBlock(playerId, "minecraft:overworld", "map_a", new BlockPos(1, 2, 3));
        manager.handleShiftRightClickBlock(playerId, "minecraft:overworld", "map_a", new BlockPos(4, 5, 6));

        var sameMap = manager.retarget(playerId, "minecraft:overworld", "map_a");
        assertFalse(sameMap.toBounds().isEmpty());

        var changedMap = manager.retarget(playerId, "minecraft:overworld", "map_b");
        assertEquals("map_b", changedMap.getMapKey());
        assertTrue(changedMap.toBounds().isEmpty());
    }
}
