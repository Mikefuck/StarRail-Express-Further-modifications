package com.habitrain.core.scene.replay;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Source-space XZ bounds of a fixed tray after the zoom, pan and automatic quarter-turn. */
public final class ReplayMapViewport {
    private ReplayMapViewport() {}

    public record Bounds(float minX, float minZ, float maxX, float maxZ) {
        public boolean contains(float x, float z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    public static Bounds sourceBounds(Matrix4f sourceToTray, float halfWidth, float halfDepth) {
        Matrix4f inverse = new Matrix4f(sourceToTray).invert();
        float minX = Float.POSITIVE_INFINITY, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxZ = maxX;
        Vector3f point = new Vector3f();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                inverse.transformPosition(sx * halfWidth, 0, sz * halfDepth, point);
                minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                minZ = Math.min(minZ, point.z); maxZ = Math.max(maxZ, point.z);
            }
        }
        return new Bounds(minX, minZ, maxX, maxZ);
    }
}
