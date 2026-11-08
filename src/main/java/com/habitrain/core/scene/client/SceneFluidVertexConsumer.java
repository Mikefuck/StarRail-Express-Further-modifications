package com.habitrain.core.scene.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;

import java.util.function.LongConsumer;

/** Buffer a complete liquid quad, restore section coordinates and its actual surface normal. */
final class SceneFluidVertexConsumer implements VertexConsumer {
    private final VertexConsumer target;
    private final LongConsumer countVertices;
    private final int blockX, blockY, blockZ;
    private final float[] positions = new float[12], uv = new float[8];
    private final int[] colors = new int[4], lights = new int[4];
    private int count;

    SceneFluidVertexConsumer(VertexConsumer target, LongConsumer countVertices, int x, int y, int z) {
        this.target = target;
        this.countVertices = countVertices;
        blockX = x; blockY = y; blockZ = z;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if (count == 4) flush();
        positions[count * 3] = x + (blockX & ~15);
        positions[count * 3 + 1] = y + (blockY & ~15);
        positions[count * 3 + 2] = z + (blockZ & ~15);
        colors[count] = 0xffffffff;
        lights[count] = 0;
        count++;
        return this;
    }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        colors[count - 1] = a << 24 | r << 16 | g << 8 | b;
        return this;
    }
    @Override public VertexConsumer setUv(float u, float v) {
        uv[(count - 1) * 2] = u; uv[(count - 1) * 2 + 1] = v;
        return this;
    }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { lights[count - 1] = u | v << 16; return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }

    void finish() {
        if (count != 0) flush();
    }

    private void flush() {
        if (count != 4) throw new IllegalStateException("Incomplete liquid quad");
        countVertices.accept(4);
        Vector3f a = new Vector3f(positions[3] - positions[0], positions[4] - positions[1], positions[5] - positions[2]);
        Vector3f b = new Vector3f(positions[6] - positions[0], positions[7] - positions[1], positions[8] - positions[2]);
        Vector3f normal = a.cross(b);
        if (!Float.isFinite(normal.lengthSquared()) || normal.lengthSquared() < 1.0e-10f) normal.set(0, 1, 0);
        else normal.normalize();
        int code;
        if (Math.abs(normal.y) > 0.1f) {
            boolean bottom = true;
            for (int i = 0; i < 4; i++) bottom &= positions[i * 3 + 1] - blockY < 0.01f;
            if (bottom) { normal.set(0, -1, 0); code = 1; }
            else { if (normal.y < 0) normal.negate(); code = 4; }
        } else if (Math.abs(normal.x) > Math.abs(normal.z)) {
            normal.set(positions[0] - blockX < 0.5f ? -1 : 1, 0, 0); code = 2;
        } else {
            normal.set(0, 0, positions[2] - blockZ < 0.5f ? -1 : 1); code = 3;
        }
        for (int i = 0; i < 4; i++) {
            target.addVertex(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
                    .setColor(colors[i]).setUv(uv[i * 2], uv[i * 2 + 1])
                    .setLight((lights[i] & ~15) | code).setNormal(normal.x, normal.y, normal.z);
        }
        count = 0;
    }
}
