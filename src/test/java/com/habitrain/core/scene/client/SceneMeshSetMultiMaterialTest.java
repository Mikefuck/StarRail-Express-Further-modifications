package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneMeshSetMultiMaterialTest {

    @Test
    public void testEmptySceneMeshSet() {
        SceneMeshSet meshSet = new SceneMeshSet();
        assertTrue(meshSet.isEmpty());
        assertFalse(meshSet.isClosed());
        assertTrue(meshSet.getCustomBuffers().isEmpty());
        assertNull(meshSet.getBuffer(SceneMeshSet.Layer.SOLID));
        assertNull(meshSet.getCustomBuffer(SceneMaterialKey.SOLID));

        meshSet.close();
        assertTrue(meshSet.isClosed());
        // Closing twice should be safe
        meshSet.close();
        assertTrue(meshSet.isClosed());
    }

    @Test
    public void testCustomBufferMappingNullSafe() {
        SceneMeshSet meshSet = new SceneMeshSet();
        meshSet.setCustomBuffer(null, null);
        assertTrue(meshSet.getCustomBuffers().isEmpty());
        meshSet.close();
    }
}
