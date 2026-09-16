package com.habitrain.core.scene.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounds of emitted BLOCK-format vertices, including geometry outside its owning section. */
final class SceneVertexBounds {
    private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

    void include(ByteBuffer vertices, int vertexCount, int stride) {
        ByteBuffer data = vertices.duplicate().order(ByteOrder.nativeOrder());
        int start = data.position();
        for (int i = 0; i < vertexCount; i++) {
            int offset = start + i * stride;
            float x = data.getFloat(offset), y = data.getFloat(offset + 4), z = data.getFloat(offset + 8);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Non-finite scene vertex position");
            }
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
    }

    SceneMeshSet.Batch batch(SceneMeshSet mesh) {
        // A tiny margin also keeps planar geometry from producing a zero-volume box.
        double margin = 0.001;
        return new SceneMeshSet.Batch(mesh, minX - margin, minY - margin, minZ - margin,
                maxX + margin, maxY + margin, maxZ + margin);
    }
}
