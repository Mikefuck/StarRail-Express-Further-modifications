package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Clips replay quads against the four vertical tray walls, preserving all vertex attributes. */
final class ReplayClippedConsumer implements VertexConsumer, AutoCloseable {
    private static final int ATTRIBUTES = 18;
    private final VertexConsumer output;
    private final Matrix4f inverseBoard;
    private final float hw, hd;
    private final float[][] quad = new float[4][ATTRIBUTES];
    private final float[][][] polygons = new float[2][12][ATTRIBUTES];
    private final Vector3f local = new Vector3f();
    private int count, fields;
    private float[] vertex;

    ReplayClippedConsumer(VertexConsumer output, Matrix4f inverseBoard, float hw, float hd) {
        this.output = output;
        this.inverseBoard = inverseBoard;
        this.hw = hw;
        this.hd = hd;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if (count == 4) flush();
        vertex = quad[count++];
        vertex[0] = x; vertex[1] = y; vertex[2] = z;
        inverseBoard.transformPosition(x, y, z, local);
        vertex[16] = local.x; vertex[17] = local.z;
        return this;
    }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        fields |= 1;
        vertex[3] = r; vertex[4] = g; vertex[5] = b; vertex[6] = a;
        return this;
    }

    @Override public VertexConsumer setUv(float u, float v) {
        fields |= 2;
        vertex[7] = u; vertex[8] = v;
        return this;
    }

    @Override public VertexConsumer setUv1(int u, int v) {
        fields |= 4;
        vertex[9] = u; vertex[10] = v;
        return this;
    }

    @Override public VertexConsumer setUv2(int u, int v) {
        fields |= 8;
        vertex[11] = u; vertex[12] = v;
        return this;
    }

    @Override public VertexConsumer setNormal(float x, float y, float z) {
        fields |= 16;
        vertex[13] = x; vertex[14] = y; vertex[15] = z;
        return this;
    }

    private void flush() {
        if (count == 0) return;
        if (count != 4) throw new IllegalStateException("Replay clipping requires complete quads");
        boolean inside = true;
        for (float[] v : quad) inside &= Math.abs(v[16]) <= hw && Math.abs(v[17]) <= hd;
        if (inside) {
            for (float[] v : quad) emit(v);
        } else {
            for (int plane = 0; plane < 4; plane++) {
                boolean outside = true;
                for (float[] v : quad) outside &= distance(v, plane) < 0;
                if (outside) { count = fields = 0; return; }
            }
            for (int i = 0; i < 4; i++) System.arraycopy(quad[i], 0, polygons[0][i], 0, ATTRIBUTES);
            int n = 4, source = 0;
            for (int plane = 0; plane < 4 && n > 0; plane++) {
                float[][] input = polygons[source], result = polygons[1 - source];
                int written = 0;
                float[] previous = input[n - 1];
                float dp = distance(previous, plane);
                for (int i = 0; i < n; i++) {
                    float[] next = input[i];
                    float dn = distance(next, plane);
                    if ((dp >= 0) != (dn >= 0)) {
                        float t = dp / (dp - dn);
                        float[] intersection = result[written++];
                        for (int a = 0; a < ATTRIBUTES; a++) intersection[a] = previous[a] + (next[a] - previous[a]) * t;
                    }
                    if (dn >= 0) System.arraycopy(next, 0, result[written++], 0, ATTRIBUTES);
                    previous = next;
                    dp = dn;
                }
                n = written;
                source = 1 - source;
            }
            // A triangle fan represented by degenerate quads fits vanilla QUADS render types.
            for (int i = 1; i < n - 1; i++) {
                emit(polygons[source][0]); emit(polygons[source][i]);
                emit(polygons[source][i + 1]); emit(polygons[source][i + 1]);
            }
        }
        count = fields = 0;
    }

    private float distance(float[] v, int plane) {
        return switch (plane) {
            case 0 -> hw + v[16];
            case 1 -> hw - v[16];
            case 2 -> hd + v[17];
            default -> hd - v[17];
        };
    }

    private void emit(float[] v) {
        output.addVertex(v[0], v[1], v[2]);
        if ((fields & 1) != 0) output.setColor(Math.round(v[3]), Math.round(v[4]), Math.round(v[5]), Math.round(v[6]));
        if ((fields & 2) != 0) output.setUv(v[7], v[8]);
        if ((fields & 4) != 0) output.setUv1(Math.round(v[9]), Math.round(v[10]));
        if ((fields & 8) != 0) output.setUv2(Math.round(v[11]), Math.round(v[12]));
        if ((fields & 16) != 0) output.setNormal(v[13], v[14], v[15]);
    }

    @Override public void close() { flush(); }
}
