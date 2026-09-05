package com.habitrain.core.scene;

import com.habitrain.core.scene.server.SceneSelectionSessionManager;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SceneSelectionSessionManagerTest {
    private final SceneSelectionSessionManager manager = SceneSelectionSessionManager.getInstance();

    @AfterEach
    void clearSessions() {
        manager.clearAll();
    }

    @Test
    void changingEditorMapDoesNotRelabelOrClearExistingSelection() {
        UUID playerId = UUID.randomUUID();
        manager.handleShiftRightClickBlock(playerId, "minecraft:overworld", "map_a", new BlockPos(1, 2, 3));
        manager.handleShiftRightClickBlock(playerId, "minecraft:overworld", "map_a", new BlockPos(4, 5, 6));

        manager.selectEditorMap(playerId, "map_b");

        assertEquals("map_b", manager.getEditorMapKey(playerId));
        assertEquals("map_a", manager.getSession(playerId).getMapKey());
        assertFalse(manager.getSession(playerId).toBounds().isEmpty());
    }

    @Test
    void clearingSelectionKeepsRememberedEditorMapUntilDisconnect() {
        UUID playerId = UUID.randomUUID();
        manager.selectEditorMap(playerId, "map_b");
        manager.handleShiftRightClickBlock(playerId, "minecraft:overworld", "map_b", new BlockPos(1, 2, 3));

        manager.clear(playerId);

        assertEquals("map_b", manager.getEditorMapKey(playerId));
        assertTrue(manager.getBounds(playerId).isEmpty());

        manager.onPlayerDisconnect(playerId);
        assertEquals("", manager.getEditorMapKey(playerId));
    }

    @Test
    void dimensionChangeClearsSelectionButKeepsEditorMap() {
        UUID playerId = UUID.randomUUID();
        manager.selectEditorMap(playerId, "map_b");
        manager.handleShiftRightClickBlock(playerId, "minecraft:overworld", "map_b", new BlockPos(1, 2, 3));

        manager.onPlayerChangeDimension(playerId, "minecraft:the_nether");

        assertTrue(manager.getBounds(playerId).isEmpty());
        assertEquals("map_b", manager.getEditorMapKey(playerId));
    }

    @Test
    void stagingToolTokenStaysStableForSameMapAndRotatesOnContextChanges() {
        UUID playerId = UUID.randomUUID();
        manager.selectEditorMap(playerId, "map_a");
        String initial = manager.getEditorSessionId(playerId);
        manager.selectEditorMap(playerId, "map_a");
        assertEquals(initial, manager.getEditorSessionId(playerId));

        manager.handleShiftRightClickBlock(
                playerId, "minecraft:overworld", "map_a", new BlockPos(1, 2, 3));
        String afterSelection = manager.getEditorSessionId(playerId);
        assertNotEquals(initial, afterSelection);

        manager.selectEditorMap(playerId, "map_b");
        assertNotEquals(afterSelection, manager.getEditorSessionId(playerId));
    }

    @Test
    void remembersEditorBackgroundPerPlayerAndMap() {
        UUID playerId = UUID.randomUUID();
        // Defaults to DEFAULT_ID
        assertEquals(com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID,
                manager.getEditorBackgroundId(playerId, "map_a"));

        manager.selectEditorBackground(playerId, "map_a", "custom_bg_1");
        assertEquals("custom_bg_1", manager.getEditorBackgroundId(playerId, "map_a"));
        // map_b is independent and still default
        assertEquals(com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID,
                manager.getEditorBackgroundId(playerId, "map_b"));

        manager.selectEditorBackground(playerId, "map_b", "custom_bg_2");
        assertEquals("custom_bg_2", manager.getEditorBackgroundId(playerId, "map_b"));
        assertEquals("custom_bg_1", manager.getEditorBackgroundId(playerId, "map_a"));

        manager.onPlayerDisconnect(playerId);
        assertEquals(com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID,
                manager.getEditorBackgroundId(playerId, "map_a"));
    }
}
