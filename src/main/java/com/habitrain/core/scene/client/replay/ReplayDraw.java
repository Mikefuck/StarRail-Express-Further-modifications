package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * World-space drawing primitives for the replay board.
 *
 * <p>All glow geometry (trails, beams, rings, wireframes) is emitted as camera-facing
 * triangle strips expressed as quads: {@code GL_LINES} widths are clamped to 1 px on most
 * drivers, which is unusable for a miniature. Positions passed to the "camera space" helpers
 * are camera-relative world coordinates (the world pass keeps the view rotation in the
 * model-view stack, so no further matrix is applied).</p>
 */
final class ReplayDraw {
    private ReplayDraw() {}

    /** Additive hologram light (src-alpha, one): overlaps brighten, never darken. */
    static final RenderType GLOW = RenderType.create("habitrain_replay_glow", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1 << 16, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false));

    /** Alpha-blended flat fills (panels, shadows, tombstones). */
    static final RenderType FILL = RenderType.create("habitrain_replay_fill", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1 << 15, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false));

    // ------------------------------------------------------------------ flat quads in a local frame

    static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                     float x2, float y2, float z2, float x3, float y3, float z3, int c0, int c1, int c2, int c3) {
        vc.addVertex(m, x0, y0, z0).setColor(c0);
        vc.addVertex(m, x1, y1, z1).setColor(c1);
        vc.addVertex(m, x2, y2, z2).setColor(c2);
        vc.addVertex(m, x3, y3, z3).setColor(c3);
    }

    /** Axis-aligned rectangle in the local XY plane (z constant), top/bottom gradient. */
    static void rectXY(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int top, int bottom) {
        quad(vc, m, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, bottom, bottom, top, top);
    }

    /** Rectangle in the local XZ plane (y constant). */
    static void rectXZ(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1, float y, int color) {
        quad(vc, m, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, color, color, color, color);
    }

    /** Flat annulus in the local XZ plane. */
    static void ring(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius, float thickness,
                     int segments, int inner, int outer) {
        float r0 = Math.max(0, radius - thickness * 0.5f), r1 = radius + thickness * 0.5f;
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            quad(vc, m, cx + c0 * r0, cy, cz + s0 * r0, cx + c0 * r1, cy, cz + s0 * r1,
                    cx + c1 * r1, cy, cz + s1 * r1, cx + c1 * r0, cy, cz + s1 * r0, inner, outer, outer, inner);
        }
    }

    /** Filled disc in the local XZ plane, bright centre fading out. */
    static void disc(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius, int segments,
                     int center, int edge) {
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
            quad(vc, m, cx, cy, cz, cx, cy, cz, cx + Mth.cos(a1) * radius, cy, cz + Mth.sin(a1) * radius,
                    cx + Mth.cos(a0) * radius, cy, cz + Mth.sin(a0) * radius, center, center, edge, edge);
        }
    }

    /** Vertical light column (two crossed planes) in a local frame. */
    static void column(VertexConsumer vc, Matrix4f m, float cx, float y0, float cz, float height, float radius,
                       int bottom, int top) {
        quad(vc, m, cx - radius, y0, cz, cx + radius, y0, cz, cx + radius, y0 + height, cz, cx - radius, y0 + height, cz,
                bottom, bottom, top, top);
        quad(vc, m, cx, y0, cz - radius, cx, y0, cz + radius, cx, y0 + height, cz + radius, cx, y0 + height, cz - radius,
                bottom, bottom, top, top);
    }

    // ------------------------------------------------------------------ camera-space strips

    /**
     * Camera-facing ribbon through {@code n} camera-relative points ({@code xyz} interleaved).
     * Per-point width and colour allow comet tails and gradients; joints share averaged normals
     * so there are no cracks at corners.
     */
    static void ribbon(VertexConsumer vc, float[] xyz, int n, float[] widths, int[] colors) {
        if (n < 2) return;
        float pax = 0, pay = 0, paz = 0, pbx = 0, pby = 0, pbz = 0;
        for (int i = 0; i < n; i++) {
            int o = i * 3;
            int prev = Math.max(0, i - 1) * 3, next = Math.min(n - 1, i + 1) * 3;
            float tx = xyz[next] - xyz[prev], ty = xyz[next + 1] - xyz[prev + 1], tz = xyz[next + 2] - xyz[prev + 2];
            float px = xyz[o], py = xyz[o + 1], pz = xyz[o + 2];
            // side = tangent x (point - camera); the camera is the origin.
            float sx = ty * pz - tz * py, sy = tz * px - tx * pz, sz = tx * py - ty * px;
            float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (len < 1.0e-7f) { sx = 0; sy = 1; sz = 0; len = 1; }
            float h = widths[i] * 0.5f / len;
            sx *= h; sy *= h; sz *= h;
            float ax = px - sx, ay = py - sy, az = pz - sz, bx = px + sx, by = py + sy, bz = pz + sz;
            if (i > 0) {
                int cPrev = colors[i - 1], cCur = colors[i];
                if (((cPrev | cCur) >>> 24) != 0) {
                    vc.addVertex(pax, pay, paz).setColor(cPrev);
                    vc.addVertex(pbx, pby, pbz).setColor(cPrev);
                    vc.addVertex(bx, by, bz).setColor(cCur);
                    vc.addVertex(ax, ay, az).setColor(cCur);
                }
            }
            pax = ax; pay = ay; paz = az; pbx = bx; pby = by; pbz = bz;
        }
    }

    private static final float[] SEG = new float[6];
    private static final float[] SEG_W = new float[2];
    private static final int[] SEG_C = new int[2];

    /** Single glowing segment between two camera-relative points. */
    static void segment(VertexConsumer vc, Vector3f a, Vector3f b, float width, int ca, int cb) {
        SEG[0] = a.x; SEG[1] = a.y; SEG[2] = a.z; SEG[3] = b.x; SEG[4] = b.y; SEG[5] = b.z;
        SEG_W[0] = SEG_W[1] = width;
        SEG_C[0] = ca; SEG_C[1] = cb;
        ribbon(vc, SEG, 2, SEG_W, SEG_C);
    }

    /** Segment in a local frame (transformed through {@code m}). */
    static void segment(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                        float width, int c0, int c1) {
        Vector3f a = m.transformPosition(x0, y0, z0, new Vector3f());
        Vector3f b = m.transformPosition(x1, y1, z1, new Vector3f());
        segment(vc, a, b, width, c0, c1);
    }

    /** Twelve glowing edges of a local axis-aligned box. */
    static void wireBox(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                        float width, int color) {
        segment(vc, m, x0, y0, z0, x1, y0, z0, width, color, color);
        segment(vc, m, x1, y0, z0, x1, y0, z1, width, color, color);
        segment(vc, m, x1, y0, z1, x0, y0, z1, width, color, color);
        segment(vc, m, x0, y0, z1, x0, y0, z0, width, color, color);
        segment(vc, m, x0, y1, z0, x1, y1, z0, width, color, color);
        segment(vc, m, x1, y1, z0, x1, y1, z1, width, color, color);
        segment(vc, m, x1, y1, z1, x0, y1, z1, width, color, color);
        segment(vc, m, x0, y1, z1, x0, y1, z0, width, color, color);
        segment(vc, m, x0, y0, z0, x0, y1, z0, width, color, color);
        segment(vc, m, x1, y0, z0, x1, y1, z0, width, color, color);
        segment(vc, m, x1, y0, z1, x1, y1, z1, width, color, color);
        segment(vc, m, x0, y0, z1, x0, y1, z1, width, color, color);
    }

    /** Camera-facing star burst (muzzle flash, sparkles) around a camera-relative centre. */
    static void star(VertexConsumer vc, Vector3f c, Vector3f right, Vector3f up, float radius, int rays,
                     float spin, int core, int tip) {
        for (int i = 0; i < rays; i++) {
            float a = spin + Mth.TWO_PI * i / rays;
            float dx = Mth.cos(a), dy = Mth.sin(a);
            float len = (i % 2 == 0 ? 1f : 0.55f) * radius;
            Vector3f tipPos = new Vector3f(c).add(right.x * dx * len + up.x * dy * len,
                    right.y * dx * len + up.y * dy * len, right.z * dx * len + up.z * dy * len);
            segment(vc, c, tipPos, radius * 0.18f, core, tip);
        }
    }

    /** Camera-facing filled quad centred on a camera-relative point. */
    static void billboard(VertexConsumer vc, Vector3f c, Vector3f right, Vector3f up, float halfW, float halfH, int color) {
        float rx = right.x * halfW, ry = right.y * halfW, rz = right.z * halfW;
        float ux = up.x * halfH, uy = up.y * halfH, uz = up.z * halfH;
        vc.addVertex(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz).setColor(color);
        vc.addVertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz).setColor(color);
        vc.addVertex(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz).setColor(color);
        vc.addVertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz).setColor(color);
    }

    // ------------------------------------------------------------------ textured cuboids (entity format)

    /**
     * Textured, lit cuboid. Faces larger than {@code tile} blocks are split into repeated tiles so
     * herringbone / plank textures keep their real-world scale instead of being stretched.
     */
    static void box(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1,
                    TextureAtlasSprite side, TextureAtlasSprite top, int tint, int light, float tile) {
        // up / down
        face(vc, pose, top, tint, light, 0, 1, 0, x0, y1, z0, x1, y1, z0, x0, y1, z1, tile);
        face(vc, pose, side, scaleTint(tint, 0.55f), light, 0, -1, 0, x0, y0, z1, x1, y0, z1, x0, y0, z0, tile);
        // north / south
        face(vc, pose, side, scaleTint(tint, 0.8f), light, 0, 0, -1, x1, y1, z0, x0, y1, z0, x1, y0, z0, tile);
        face(vc, pose, side, scaleTint(tint, 0.8f), light, 0, 0, 1, x0, y1, z1, x1, y1, z1, x0, y0, z1, tile);
        // west / east
        face(vc, pose, side, scaleTint(tint, 0.65f), light, -1, 0, 0, x0, y1, z0, x0, y1, z1, x0, y0, z0, tile);
        face(vc, pose, side, scaleTint(tint, 0.65f), light, 1, 0, 0, x1, y1, z1, x1, y1, z0, x1, y0, z1, tile);
    }

    /**
     * Parallelogram face spanned by origin {@code o}, u-edge end {@code u} and v-edge end {@code v}.
     */
    static void face(VertexConsumer vc, PoseStack.Pose pose, TextureAtlasSprite sprite, int tint, int light,
                     float nx, float ny, float nz, float ox, float oy, float oz, float ux, float uy, float uz,
                     float vx, float vy, float vz, float tile) {
        float eux = ux - ox, euy = uy - oy, euz = uz - oz;
        float evx = vx - ox, evy = vy - oy, evz = vz - oz;
        float lu = (float) Math.sqrt(eux * eux + euy * euy + euz * euz);
        float lv = (float) Math.sqrt(evx * evx + evy * evy + evz * evz);
        if (lu < 1.0e-5f || lv < 1.0e-5f) return;
        int nu = Math.max(1, Math.min(48, (int) Math.ceil(lu / tile - 1.0e-3f)));
        int nvv = Math.max(1, Math.min(48, (int) Math.ceil(lv / tile - 1.0e-3f)));
        for (int i = 0; i < nu; i++) {
            float a0 = i * tile / lu, a1 = Math.min(1f, (i + 1) * tile / lu);
            float ua1 = (a1 - a0) * lu / tile;
            for (int j = 0; j < nvv; j++) {
                float b0 = j * tile / lv, b1 = Math.min(1f, (j + 1) * tile / lv);
                float vb1 = (b1 - b0) * lv / tile;
                float u0 = sprite.getU(0), u1 = sprite.getU(Math.min(1f, ua1));
                float v0 = sprite.getV(0), v1 = sprite.getV(Math.min(1f, vb1));
                vertex(vc, pose, ox + eux * a0 + evx * b0, oy + euy * a0 + evy * b0, oz + euz * a0 + evz * b0, tint, u0, v0, light, nx, ny, nz);
                vertex(vc, pose, ox + eux * a1 + evx * b0, oy + euy * a1 + evy * b0, oz + euz * a1 + evz * b0, tint, u1, v0, light, nx, ny, nz);
                vertex(vc, pose, ox + eux * a1 + evx * b1, oy + euy * a1 + evy * b1, oz + euz * a1 + evz * b1, tint, u1, v1, light, nx, ny, nz);
                vertex(vc, pose, ox + eux * a0 + evx * b1, oy + euy * a0 + evy * b1, oz + euz * a0 + evz * b1, tint, u0, v1, light, nx, ny, nz);
            }
        }
    }

    static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, int color, float u, float v,
                       int light, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }

    private static int scaleTint(int tint, float k) {
        return ReplayPalette.scale(tint, k);
    }
}
