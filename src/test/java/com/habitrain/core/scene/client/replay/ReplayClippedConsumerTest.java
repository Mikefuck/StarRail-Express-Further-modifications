package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayClippedConsumerTest {
    @Test void cutsCrossingQuadsExactlyAndInterpolatesSkinUvsColorAndLighting() {
        var output = new Collector();
        try (var clip = new ReplayClippedConsumer(output, new Matrix4f(), 1, 1)) {
            vertex(clip, -2, -0.5f, 0, 0);
            vertex(clip, 2, -0.5f, 1, 240);
            vertex(clip, 2, 0.5f, 1, 240);
            vertex(clip, -2, 0.5f, 0, 0);
        }
        assertFalse(output.vertices.isEmpty());
        assertEquals(0, output.vertices.size() % 4);
        for (float[] v : output.vertices) {
            assertTrue(Math.abs(v[0]) <= 1.00001 && Math.abs(v[2]) <= 1.00001);
            assertEquals((v[0] + 2) / 4, v[3], 1e-6, "UV stays attached to clipped face");
            assertEquals(Math.round((v[0] + 2) / 4 * 240), v[4], 1);
            assertEquals(16, v[5]);
            assertEquals(240, v[6]);
            assertEquals(1, v[7]);
        }
    }

    @Test void clipsAroundTheWholeTrayInsteadOfDroppingSpanningFacesAndKeepsTheFinalQuad() {
        var output = new Collector();
        try (var clip = new ReplayClippedConsumer(output, new Matrix4f(), 1, 1)) {
            vertex(clip, -20, -20, 0, 255); vertex(clip, 20, -20, 0, 255);
            vertex(clip, 20, 20, 0, 255); vertex(clip, -20, 20, 0, 255);
        }
        assertTrue(output.vertices.stream().allMatch(v -> Math.abs(v[0]) <= 1 && Math.abs(v[2]) <= 1));
        double area = 0;
        for (int i = 0; i < output.vertices.size(); i += 4) {
            var a = output.vertices.get(i); var b = output.vertices.get(i + 1); var c = output.vertices.get(i + 2);
            area += Math.abs((b[0] - a[0]) * (c[2] - a[2]) - (c[0] - a[0]) * (b[2] - a[2])) / 2;
        }
        assertEquals(4, area, 1e-5);
    }

    @Test void transformedTablesKeepInteriorGeometryAndDiscardExteriorGeometry() {
        for (int facing = 0; facing < 360; facing += 90) {
            Matrix4f board = new Matrix4f().translate(5, -2, -9).rotateY((float) Math.toRadians(facing + 37)).scale(2.7f);
            var output = new Collector();
            try (var clip = new ReplayClippedConsumer(output, new Matrix4f(board).invert(), 1, 1)) {
                for (float x : new float[]{0, 10}) {
                    for (var v : List.of(new Vector3f(x - 0.2f, 4, -0.2f), new Vector3f(x + 0.2f, 4, -0.2f),
                            new Vector3f(x + 0.2f, 4, 0.2f), new Vector3f(x - 0.2f, 4, 0.2f))) {
                        board.transformPosition(v);
                        clip.addVertex(v.x, v.y, v.z).setColor(-1);
                    }
                }
            }
            assertEquals(4, output.vertices.size());
        }
    }

    private static void vertex(VertexConsumer c, float x, float z, float u, int red) {
        c.addVertex(x, 2, z).setColor(red, 100, 200, 255).setUv(u, 0.5f)
                .setUv1(16, 0).setUv2(240, 240).setNormal(0, 1, 0);
    }

    private static final class Collector implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();
        float[] current;
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = new float[]{x, y, z, 0, 0, 0, 0, 0}; vertices.add(current); return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { current[4] = r; return this; }
        @Override public VertexConsumer setUv(float u, float v) { current[3] = u; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { current[5] = u; return this; }
        @Override public VertexConsumer setUv2(int u, int v) { current[6] = u; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { current[7] = y; return this; }
    }
}
