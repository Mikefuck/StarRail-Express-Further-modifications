package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Per-frame, mesh-local fan openings shared by the renderer and geometry tests. */
public final class ReplayFanCutaway {
    public static final int MAX_FANS = LastMatchReplay.MAX_PLAYERS;
    public static final float HALF_ANGLE_TANGENT = (float) Math.tan(Math.toRadians(40));
    // Four vec4 anchors per mat4: Minecraft's uniform loader supports matrix arrays directly.
    public static final int PACKED_FLOATS = MAX_FANS * 4;

    private final float[] anchors = new float[PACKED_FLOATS];
    private int count;
    private float directionX, directionZ = 1;

    public void clear() { count = 0; }
    public int count() { return count; }
    public float[] anchors() { return anchors; }
    public float directionX() { return directionX; }
    public float directionZ() { return directionZ; }

    /** Camera-relative source transform, including table facing, source rotation and display edits. */
    public void faceViewer(Matrix4f model, Vector3f look, SceneBounds source) {
        Matrix4f inverse = new Matrix4f(model).invert();
        Vector3f towards = inverse.transformDirection(new Vector3f(look).negate());
        if (Math.hypot(towards.x, towards.z) < towards.length() * 1.0e-4) {
            // Looking straight down has no horizontal facing; use the camera's map side.
            towards = inverse.transformPosition(new Vector3f());
            towards.x -= source.sizeX() * 0.5f;
            towards.z -= source.sizeZ() * 0.5f;
        }
        direction(towards.x, towards.z);
    }

    /** Direction from the replay avatars towards the viewer, expressed in source-map axes. */
    public void direction(double x, double z) {
        double length = Math.hypot(x, z);
        if (!Double.isFinite(length) || length < 1.0e-5) {
            directionX = 0;
            directionZ = 1;
        } else {
            directionX = (float) (x / length);
            directionZ = (float) (z / length);
        }
    }

    /** Adds both living heads and visible death markers; departed/unsampled players do not cut. */
    public void add(ReplayTrackSampler.Pose pose, SceneBounds origin, SceneBounds source, double coreRadius) {
        if (!pose.visible() || count >= MAX_FANS || !Double.isFinite(coreRadius) || coreRadius <= 0) return;
        double x = origin.minX() - source.minX() + pose.x();
        double y = origin.minY() - source.minY() + pose.y();
        double z = origin.minZ() - source.minZ() + pose.z();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        int offset = count++ * 4;
        anchors[offset] = (float) x;
        anchors[offset + 1] = (float) y;
        anchors[offset + 2] = (float) z;
        anchors[offset + 3] = (float) coreRadius;
    }

    /** Mirrors the shader's voxel-centre predicate. The supporting floor stays below the opening. */
    public boolean removes(double x, double y, double z) {
        for (int i = 0; i < count; i++) {
            int offset = i * 4;
            if (y <= anchors[offset + 1] + 0.01) continue;
            double dx = x - anchors[offset], dz = z - anchors[offset + 2];
            double radius = anchors[offset + 3];
            if (dx * dx + dz * dz <= radius * radius) return true;
            double forward = dx * directionX + dz * directionZ;
            double sideways = Math.abs(dx * directionZ - dz * directionX);
            if (forward >= 0 && sideways <= radius + forward * HALF_ANGLE_TANGENT) return true;
        }
        return false;
    }
}
