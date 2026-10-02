package com.habitrain.core.client.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * 转场/投票界面用的立即模式顶点几何。
 *
 * <p>{@link GuiGraphics#fill} 只能画轴对齐矩形；这里直接提交带逐顶点颜色的四边形，
 * 因而可以画平滑渐变、斜线、圆环、光锥和射线，并可选叠加混合（additive）做发光。
 * 一个批次只产生一次 draw call。</p>
 *
 * <p>使用约定：{@link #begin}/{@link #glow} 会先 {@code flush} 掉 GuiGraphics 里已缓冲的
 * 绘制，保证画家顺序；批次期间不得穿插其它 GuiGraphics 绘制，结束时必须调用
 * {@link #end()}。几何不写深度、不参与深度测试，永远按调用顺序叠在之前的内容上。</p>
 */
final class GuiGeo {
    private final Matrix4f pose;
    private final BufferBuilder buffer;
    private final boolean additive;

    private GuiGeo(GuiGraphics g, boolean additive) {
        this.pose = new Matrix4f(g.pose().last().pose());
        this.additive = additive;
        this.buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    }

    /** 普通 alpha 混合批次。 */
    static GuiGeo begin(GuiGraphics g) {
        g.flush();
        return new GuiGeo(g, false);
    }

    /** 叠加混合批次：颜色相加，适合光晕、射线和火花。 */
    static GuiGeo glow(GuiGraphics g) {
        g.flush();
        return new GuiGeo(g, true);
    }

    void end() {
        MeshData mesh = buffer.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.enableBlend();
        if (additive) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    // ==================== 基本图元 ====================

    private void vertex(float x, float y, int color) {
        buffer.addVertex(pose, x, y, 0.0f).setColor(color);
    }

    /** 凸四边形，顶点按周长顺序给出（顺/逆时针均可）。 */
    GuiGeo quad(float x0, float y0, int c0, float x1, float y1, int c1,
                float x2, float y2, int c2, float x3, float y3, int c3) {
        if (((c0 | c1 | c2 | c3) >>> 24) == 0) {
            return this;
        }
        vertex(x0, y0, c0);
        vertex(x1, y1, c1);
        vertex(x2, y2, c2);
        vertex(x3, y3, c3);
        return this;
    }

    GuiGeo tri(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2) {
        return quad(x0, y0, c0, x1, y1, c1, x2, y2, c2, x2, y2, c2);
    }

    GuiGeo rect(float x0, float y0, float x1, float y1, int color) {
        return quad(x0, y0, color, x0, y1, color, x1, y1, color, x1, y0, color);
    }

    /** 竖向渐变矩形。 */
    GuiGeo rectV(float x0, float y0, float x1, float y1, int top, int bottom) {
        return quad(x0, y0, top, x0, y1, bottom, x1, y1, bottom, x1, y0, top);
    }

    /** 横向渐变矩形。 */
    GuiGeo rectH(float x0, float y0, float x1, float y1, int left, int right) {
        return quad(x0, y0, left, x0, y1, left, x1, y1, right, x1, y0, right);
    }

    /** 两端可不同色的粗线段。 */
    GuiGeo line(float x0, float y0, float x1, float y1, float thickness, int c0, int c1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = Mth.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) {
            return this;
        }
        float nx = -dy / len * thickness * 0.5f;
        float ny = dx / len * thickness * 0.5f;
        return quad(x0 + nx, y0 + ny, c0, x1 + nx, y1 + ny, c1, x1 - nx, y1 - ny, c1, x0 - nx, y0 - ny, c0);
    }

    GuiGeo line(float x0, float y0, float x1, float y1, float thickness, int color) {
        return line(x0, y0, x1, y1, thickness, color, color);
    }

    /** 实心椭圆（中心色 → 边缘色）。 */
    GuiGeo disc(float cx, float cy, float rx, float ry, int center, int edge) {
        int segments = segmentsFor(Math.max(rx, ry));
        float step = Mth.TWO_PI / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = i * step;
            float a1 = a0 + step;
            tri(cx, cy, center,
                    cx + Mth.cos(a0) * rx, cy + Mth.sin(a0) * ry, edge,
                    cx + Mth.cos(a1) * rx, cy + Mth.sin(a1) * ry, edge);
        }
        return this;
    }

    GuiGeo disc(float cx, float cy, float r, int color) {
        return disc(cx, cy, r, r, color, color);
    }

    /** 椭圆环带，内外缘可不同色（用于冲击波、渐隐光环）。 */
    GuiGeo ring(float cx, float cy, float rInner, float rOuter, float yScale, int inner, int outer) {
        return arc(cx, cy, rInner, rOuter, yScale, 0.0f, Mth.TWO_PI, inner, outer);
    }

    GuiGeo ring(float cx, float cy, float rInner, float rOuter, int color) {
        return ring(cx, cy, rInner, rOuter, 1.0f, color, color);
    }

    /** 圆弧环带（弧度，从 a0 到 a1，屏幕坐标 y 向下，0 指向右侧）。 */
    GuiGeo arc(float cx, float cy, float rInner, float rOuter, float yScale,
               float a0, float a1, int inner, int outer) {
        float sweep = a1 - a0;
        int segments = Math.max(3, Math.round(segmentsFor(rOuter) * Math.abs(sweep) / Mth.TWO_PI));
        float step = sweep / segments;
        for (int i = 0; i < segments; i++) {
            float s0 = a0 + i * step;
            float s1 = s0 + step;
            float c0 = Mth.cos(s0);
            float n0 = Mth.sin(s0) * yScale;
            float c1 = Mth.cos(s1);
            float n1 = Mth.sin(s1) * yScale;
            quad(cx + c0 * rInner, cy + n0 * rInner, inner,
                    cx + c0 * rOuter, cy + n0 * rOuter, outer,
                    cx + c1 * rOuter, cy + n1 * rOuter, outer,
                    cx + c1 * rInner, cy + n1 * rInner, inner);
        }
        return this;
    }

    /**
     * 柔光：中心最亮，约 35% 半径处降到一半，边缘归零。比单层线性衰减更像真实光源。
     */
    GuiGeo softGlow(float cx, float cy, float rx, float ry, int color, int alpha) {
        if (alpha <= 0 || rx < 0.5f || ry < 0.5f) {
            return this;
        }
        int core = TransitionFx.withAlpha(color, alpha);
        int mid = TransitionFx.withAlpha(color, Math.round(alpha * 0.42f));
        int edge = TransitionFx.withAlpha(color, 0);
        disc(cx, cy, rx * 0.35f, ry * 0.35f, core, mid);
        int segments = segmentsFor(Math.max(rx, ry));
        float step = Mth.TWO_PI / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = i * step;
            float a1 = a0 + step;
            float c0 = Mth.cos(a0);
            float s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1);
            float s1 = Mth.sin(a1);
            quad(cx + c0 * rx * 0.35f, cy + s0 * ry * 0.35f, mid,
                    cx + c0 * rx, cy + s0 * ry, edge,
                    cx + c1 * rx, cy + s1 * ry, edge,
                    cx + c1 * rx * 0.35f, cy + s1 * ry * 0.35f, mid);
        }
        return this;
    }

    /** 自中心放射的楔形光束（Art Deco 放射纹的一根）。 */
    GuiGeo ray(float cx, float cy, float angle, float halfWidth, float rInner, float rOuter,
               float yScale, int inner, int outer) {
        float a0 = angle - halfWidth;
        float a1 = angle + halfWidth;
        return quad(cx + Mth.cos(a0) * rInner, cy + Mth.sin(a0) * rInner * yScale, inner,
                cx + Mth.cos(a0) * rOuter, cy + Mth.sin(a0) * rOuter * yScale, outer,
                cx + Mth.cos(a1) * rOuter, cy + Mth.sin(a1) * rOuter * yScale, outer,
                cx + Mth.cos(a1) * rInner, cy + Mth.sin(a1) * rInner * yScale, inner);
    }

    /** 菱形。 */
    GuiGeo diamond(float cx, float cy, float rx, float ry, int color) {
        return quad(cx, cy - ry, color, cx - rx, cy, color, cx, cy + ry, color, cx + rx, cy, color);
    }

    /** 菱形描边。 */
    GuiGeo diamondOutline(float cx, float cy, float rx, float ry, float thickness, int color) {
        line(cx, cy - ry, cx + rx, cy, thickness, color);
        line(cx + rx, cy, cx, cy + ry, thickness, color);
        line(cx, cy + ry, cx - rx, cy, thickness, color);
        line(cx - rx, cy, cx, cy - ry, thickness, color);
        return this;
    }

    /** 凸多边形扇形填充（xy 交替），颜色按 y 在 top..bottom 间竖向插值。 */
    GuiGeo convexV(float[] xy, float yTop, float yBottom, int top, int bottom) {
        int n = xy.length / 2;
        if (n < 3) {
            return this;
        }
        float cx = 0.0f;
        float cy = 0.0f;
        for (int i = 0; i < n; i++) {
            cx += xy[i * 2];
            cy += xy[i * 2 + 1];
        }
        cx /= n;
        cy /= n;
        int cc = lerpColor(top, bottom, (cy - yTop) / Math.max(0.001f, yBottom - yTop));
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float x0 = xy[i * 2];
            float y0 = xy[i * 2 + 1];
            float x1 = xy[j * 2];
            float y1 = xy[j * 2 + 1];
            tri(cx, cy, cc,
                    x0, y0, lerpColor(top, bottom, (y0 - yTop) / Math.max(0.001f, yBottom - yTop)),
                    x1, y1, lerpColor(top, bottom, (y1 - yTop) / Math.max(0.001f, yBottom - yTop)));
        }
        return this;
    }

    /** 闭合折线描边。 */
    GuiGeo outline(float[] xy, float thickness, int color) {
        int n = xy.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            line(xy[i * 2], xy[i * 2 + 1], xy[j * 2], xy[j * 2 + 1], thickness, color);
        }
        return this;
    }

    // ==================== 工具 ====================

    private static int segmentsFor(float radius) {
        return Mth.clamp(Math.round(radius * 0.9f), 12, 72);
    }

    /** ARGB 四通道线性插值。 */
    static int lerpColor(int from, int to, float t) {
        float k = Mth.clamp(t, 0.0f, 1.0f);
        int a = Math.round(Mth.lerp(k, (from >>> 24) & 0xFF, (to >>> 24) & 0xFF));
        int r = Math.round(Mth.lerp(k, (from >>> 16) & 0xFF, (to >>> 16) & 0xFF));
        int gr = Math.round(Mth.lerp(k, (from >>> 8) & 0xFF, (to >>> 8) & 0xFF));
        int b = Math.round(Mth.lerp(k, from & 0xFF, to & 0xFF));
        return a << 24 | r << 16 | gr << 8 | b;
    }
}
