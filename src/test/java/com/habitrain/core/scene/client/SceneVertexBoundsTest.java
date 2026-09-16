package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class SceneVertexBoundsTest {
    @Test
    void boundsIncludeGeometryAcrossSplitsAndBeyondCaptureBounds() {
        SceneVertexBounds bounds = new SceneVertexBounds();
        ByteBuffer vertices = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        vertices.putFloat(0, 31).putFloat(4, -2).putFloat(8, 0);
        vertices.putFloat(32, 49).putFloat(36, 6).putFloat(40, 24);
        bounds.include(vertices, 2, 32);
        SceneMeshSet.Batch batch = bounds.batch(new SceneMeshSet());
        assertTrue(batch.minX() <= 31);
        assertTrue(batch.maxX() >= 49, "跨出预设分割线的 Section/模型不能被裁掉");
        assertTrue(batch.minY() <= -2);
        assertTrue(batch.maxZ() >= 24);
        assertEquals(0, vertices.position());
    }

    @Test
    void mergesMultipleMaterialsAndHonorsBufferPosition() {
        SceneVertexBounds bounds = new SceneVertexBounds();
        ByteBuffer vertices = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        vertices.position(16);
        vertices.putFloat(16, 3).putFloat(20, 4).putFloat(24, 5);
        bounds.include(vertices, 1, 32);
        vertices.putFloat(16, -8).putFloat(20, 2).putFloat(24, 9);
        bounds.include(vertices, 1, 32);
        SceneMeshSet.Batch batch = bounds.batch(new SceneMeshSet());
        assertTrue(batch.minX() < -8 && batch.maxX() > 3);
        assertTrue(batch.minY() < 2 && batch.maxY() > 4);
        assertTrue(batch.minZ() < 5 && batch.maxZ() > 9);
        assertEquals(16, vertices.position());
    }
}
