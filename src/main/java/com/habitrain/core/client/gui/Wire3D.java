package com.habitrain.core.client.gui;

import net.minecraft.util.Mth;

import java.util.Arrays;

/**
 * 线框三维：三维线段网格 + 透视投影，用 {@link GuiGeo} 画成发光细线。
 *
 * <p>背包车票页两侧的线框机车与星穹仪、邮票里的小图标共用。模型坐标 y 向上，投影后翻转为
 * 屏幕 y 向下；视线沿 +z，z 越大越远、越暗。{@link #draw} 的组装进度让每根线段从散落处
 * 旋转飞入并咬合到位——即「线条拼凑」成模型。</p>
 */
final class Wire3D {
    private Wire3D() {
    }

    /** 线段集合：每段 6 个 float（x0 y0 z0 x1 y1 z1）。 */
    static final class Mesh {
        private float[] seg = new float[6 * 64];
        private int count;

        int size() {
            return count;
        }

        Mesh clear() {
            count = 0;
            return this;
        }

        Mesh line(float x0, float y0, float z0, float x1, float y1, float z1) {
            if ((count + 1) * 6 > seg.length) {
                seg = Arrays.copyOf(seg, seg.length * 2);
            }
            int o = count * 6;
            seg[o] = x0;
            seg[o + 1] = y0;
            seg[o + 2] = z0;
            seg[o + 3] = x1;
            seg[o + 4] = y1;
            seg[o + 5] = z1;
            count++;
            return this;
        }

        /** 折线（xyz 交替），closed 时首尾相连。 */
        Mesh path(boolean closed, float... xyz) {
            int n = xyz.length / 3;
            for (int i = 0; i + 1 < n; i++) {
                line(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], xyz[i * 3 + 3], xyz[i * 3 + 4], xyz[i * 3 + 5]);
            }
            if (closed && n > 2) {
                line(xyz[n * 3 - 3], xyz[n * 3 - 2], xyz[n * 3 - 1], xyz[0], xyz[1], xyz[2]);
            }
            return this;
        }

        /** 平面圆弧：圆心 c，平面基向量 u、v（单位向量），从角 a0 到 a1。 */
        Mesh arc(float cx, float cy, float cz, float r, float ux, float uy, float uz,
                 float vx, float vy, float vz, float a0, float a1, int sides) {
            float step = (a1 - a0) / sides;
            float pc = Mth.cos(a0);
            float ps = Mth.sin(a0);
            for (int i = 1; i <= sides; i++) {
                float a = a0 + step * i;
                float c = Mth.cos(a);
                float s = Mth.sin(a);
                line(cx + (ux * pc + vx * ps) * r, cy + (uy * pc + vy * ps) * r, cz + (uz * pc + vz * ps) * r,
                        cx + (ux * c + vx * s) * r, cy + (uy * c + vy * s) * r, cz + (uz * c + vz * s) * r);
                pc = c;
                ps = s;
            }
            return this;
        }

        Mesh circle(float cx, float cy, float cz, float r, float ux, float uy, float uz,
                    float vx, float vy, float vz, int sides) {
            return arc(cx, cy, cz, r, ux, uy, uz, vx, vy, vz, 0.0f, Mth.TWO_PI, sides);
        }

        /** 垂直于 x 轴的圆（车轮、锅炉截面）。 */
        Mesh circleX(float cx, float cy, float cz, float r, int sides) {
            return circle(cx, cy, cz, r, 0, 1, 0, 0, 0, 1, sides);
        }

        /** 垂直于 y 轴的圆（水平环）。 */
        Mesh circleY(float cx, float cy, float cz, float r, int sides) {
            return circle(cx, cy, cz, r, 1, 0, 0, 0, 0, 1, sides);
        }

        /** 垂直于 z 轴的圆（正对镜头的圆）。 */
        Mesh circleZ(float cx, float cy, float cz, float r, int sides) {
            return circle(cx, cy, cz, r, 1, 0, 0, 0, 1, 0, sides);
        }

        /** 轴对齐长方体的 12 条棱。 */
        Mesh box(float x0, float y0, float z0, float x1, float y1, float z1) {
            line(x0, y0, z0, x1, y0, z0);
            line(x1, y0, z0, x1, y0, z1);
            line(x1, y0, z1, x0, y0, z1);
            line(x0, y0, z1, x0, y0, z0);
            line(x0, y1, z0, x1, y1, z0);
            line(x1, y1, z0, x1, y1, z1);
            line(x1, y1, z1, x0, y1, z1);
            line(x0, y1, z1, x0, y1, z0);
            line(x0, y0, z0, x0, y1, z0);
            line(x1, y0, z0, x1, y1, z0);
            line(x1, y0, z1, x1, y1, z1);
            line(x0, y0, z1, x0, y1, z1);
            return this;
        }

        /** z 为常数平面上的矩形框。 */
        Mesh rectZ(float z, float x0, float y0, float x1, float y1) {
            return path(true, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z);
        }

        /** x 为常数平面上的矩形框。 */
        Mesh rectX(float x, float y0, float z0, float y1, float z1) {
            return path(true, x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0);
        }

        /**
         * 沿 x 轴的圆柱：两端圆 + generators 条母线；bands 为中间加箍的 x 位置。
         */
        Mesh cylinderX(float x0, float x1, float cy, float cz, float r, int sides, int generators, float... bands) {
            circleX(x0, cy, cz, r, sides);
            circleX(x1, cy, cz, r, sides);
            for (float band : bands) {
                circleX(band, cy, cz, r * 1.02f, sides);
            }
            for (int i = 0; i < generators; i++) {
                float a = Mth.TWO_PI * i / generators + 0.3f;
                float y = cy + Mth.cos(a) * r;
                float z = cz + Mth.sin(a) * r;
                line(x0, y, z, x1, y, z);
            }
            return this;
        }

        /** 沿 y 轴的圆柱。 */
        Mesh cylinderY(float y0, float y1, float cx, float cz, float r, int sides, int generators) {
            circleY(cx, y0, cz, r, sides);
            circleY(cx, y1, cz, r, sides);
            for (int i = 0; i < generators; i++) {
                float a = Mth.TWO_PI * i / generators + 0.4f;
                float x = cx + Mth.cos(a) * r;
                float z = cz + Mth.sin(a) * r;
                line(x, y0, z, x, y1, z);
            }
            return this;
        }

        /** 沿 z 轴的圆柱（钟表、齿轮之类正对镜头的扁圆柱）。 */
        Mesh cylinderZ(float z0, float z1, float cx, float cy, float r, int sides, int generators) {
            circleZ(cx, cy, z0, r, sides);
            circleZ(cx, cy, z1, r, sides);
            for (int i = 0; i < generators; i++) {
                float a = Mth.TWO_PI * i / generators + 0.2f;
                float x = cx + Mth.cos(a) * r;
                float y = cy + Mth.sin(a) * r;
                line(x, y, z0, x, y, z1);
            }
            return this;
        }

        /**
         * 绕竖直轴的旋转体：profile 为 (半径, 高度) 对，每个剖面点一圈纬线，meridians 条经线连接相邻剖面点。
         */
        Mesh lathe(float cx, float cy, float cz, float[] profile, int sides, int meridians) {
            int n = profile.length / 2;
            for (int i = 0; i < n; i++) {
                float r = profile[i * 2];
                if (r > 0.001f) {
                    circleY(cx, cy + profile[i * 2 + 1], cz, r, sides);
                }
            }
            for (int m = 0; m < meridians; m++) {
                float a = Mth.TWO_PI * m / meridians + 0.25f;
                float c = Mth.cos(a);
                float s = Mth.sin(a);
                for (int i = 0; i + 1 < n; i++) {
                    float r0 = profile[i * 2];
                    float y0 = profile[i * 2 + 1];
                    float r1 = profile[i * 2 + 2];
                    float y1 = profile[i * 2 + 3];
                    line(cx + c * r0, cy + y0, cz + s * r0, cx + c * r1, cy + y1, cz + s * r1);
                }
            }
            return this;
        }

        /** 沿 z 拉伸的棱柱：闭合多边形 xy 在 z0、z1 两面描边，并连起各顶点。 */
        Mesh prism(float[] xy, float z0, float z1) {
            int n = xy.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                line(xy[i * 2], xy[i * 2 + 1], z0, xy[j * 2], xy[j * 2 + 1], z0);
                line(xy[i * 2], xy[i * 2 + 1], z1, xy[j * 2], xy[j * 2 + 1], z1);
                line(xy[i * 2], xy[i * 2 + 1], z0, xy[i * 2], xy[i * 2 + 1], z1);
            }
            return this;
        }

        Mesh append(Mesh other) {
            for (int i = 0; i < other.count; i++) {
                int o = i * 6;
                line(other.seg[o], other.seg[o + 1], other.seg[o + 2], other.seg[o + 3], other.seg[o + 4],
                        other.seg[o + 5]);
            }
            return this;
        }
    }

    /**
     * 观察变换：先平移模型原点，再依次偏航（绕 y）、俯仰（绕 x）、滚转（绕 z），最后透视投影到 (cx, cy)。
     * scale 是模型单位到屏幕单位的比例，dist 是相机到模型原点的距离（模型单位）。
     */
    static final class View {
        private float r00 = 1, r01, r02, r10, r11 = 1, r12, r20, r21, r22 = 1;
        private float ox, oy, oz;
        float cx;
        float cy;
        float scale = 1.0f;
        float dist = 12.0f;
        /** 深度渐暗所用的模型半径。 */
        float radius = 1.0f;
        // 最近一次 project 的结果
        float px;
        float py;
        float pz;

        View orient(float yaw, float pitch, float roll) {
            float cyw = Mth.cos(yaw);
            float syw = Mth.sin(yaw);
            float cp = Mth.cos(pitch);
            float sp = Mth.sin(pitch);
            float cr = Mth.cos(roll);
            float sr = Mth.sin(roll);
            // R = Rz(roll) · Rx(pitch) · Ry(yaw)
            float a00 = cyw;
            float a02 = syw;
            float a10 = sp * syw;
            float a11 = cp;
            float a12 = -sp * cyw;
            float a20 = -cp * syw;
            float a21 = sp;
            float a22 = cp * cyw;
            r00 = cr * a00 - sr * a10;
            r01 = -sr * a11;
            r02 = cr * a02 - sr * a12;
            r10 = sr * a00 + cr * a10;
            r11 = cr * a11;
            r12 = sr * a02 + cr * a12;
            r20 = a20;
            r21 = a21;
            r22 = a22;
            return this;
        }

        View origin(float x, float y, float z) {
            ox = x;
            oy = y;
            oz = z;
            return this;
        }

        View at(float cx, float cy, float scale, float dist, float radius) {
            this.cx = cx;
            this.cy = cy;
            this.scale = scale;
            this.dist = dist;
            this.radius = Math.max(0.001f, radius);
            return this;
        }

        void project(float x, float y, float z) {
            float lx = x - ox;
            float ly = y - oy;
            float lz = z - oz;
            float rx = r00 * lx + r01 * ly + r02 * lz;
            float ry = r10 * lx + r11 * ly + r12 * lz;
            float rz = r20 * lx + r21 * ly + r22 * lz;
            float f = dist / Math.max(0.2f, dist + rz);
            px = cx + rx * f * scale;
            py = cy - ry * f * scale;
            pz = rz;
        }

        /** 深度亮度：最近处 1，最远处 1 - fade。 */
        float depthLight(float z, float fade) {
            return 1.0f - fade * Mth.clamp((z + radius) / (radius * 2.0f), 0.0f, 1.0f);
        }
    }

    /** 线条风格：主线 + 外层光晕，颜色为 RGB（alpha 由 {@link #draw} 的参数给出）。 */
    record Style(int color, float width, float glowWidth, float glowAlpha, float depthFade) {
    }

    /**
     * 画网格。必须在 {@link GuiGeo#glow} 批次中调用（叠加混合让线条发光，交叉处更亮）。
     *
     * @param alpha    整体不透明度 0..1
     * @param assemble 组装进度 0..1；≥1 时直接画成品
     * @param seed     组装飞入方向的随机种子
     */
    static void draw(GuiGeo geo, Mesh mesh, View view, Style style, float alpha, float assemble, int seed) {
        if (alpha <= 0.01f || mesh.count == 0) {
            return;
        }
        float[] s = mesh.seg;
        boolean assembling = assemble < 1.0f;
        for (int i = 0; i < mesh.count; i++) {
            int o = i * 6;
            view.project(s[o], s[o + 1], s[o + 2]);
            float x0 = view.px;
            float y0 = view.py;
            float l0 = view.depthLight(view.pz, style.depthFade);
            view.project(s[o + 3], s[o + 4], s[o + 5]);
            float x1 = view.px;
            float y1 = view.py;
            float l1 = view.depthLight(view.pz, style.depthFade);
            float a = alpha;
            if (assembling) {
                float delay = TicketArt.hash(seed * 131 + i * 7) * 0.62f;
                float local = TransitionFx.clamp01((assemble - delay) / 0.38f);
                if (local <= 0.0f) {
                    continue;
                }
                float e = TransitionFx.easeOutCubic(local);
                float away = (1.0f - e) * (1.0f - e);
                if (away > 0.0005f) {
                    // 线段绕中点旋转、整体从散落处飞回
                    float mx = (x0 + x1) * 0.5f;
                    float my = (y0 + y1) * 0.5f;
                    float spin = (TicketArt.hash(seed * 17 + i * 3) - 0.5f) * Mth.TWO_PI * away;
                    float dir = TicketArt.hash(seed * 29 + i * 11) * Mth.TWO_PI;
                    float reach = (24.0f + TicketArt.hash(seed * 37 + i) * 46.0f) * away;
                    float c = Mth.cos(spin);
                    float sn = Mth.sin(spin);
                    float dx0 = x0 - mx;
                    float dy0 = y0 - my;
                    float dx1 = x1 - mx;
                    float dy1 = y1 - my;
                    mx += Mth.cos(dir) * reach;
                    my += Mth.sin(dir) * reach;
                    x0 = mx + dx0 * c - dy0 * sn;
                    y0 = my + dx0 * sn + dy0 * c;
                    x1 = mx + dx1 * c - dy1 * sn;
                    y1 = my + dx1 * sn + dy1 * c;
                }
                a *= e;
            }
            if (style.glowAlpha > 0.0f) {
                geo.line(x0, y0, x1, y1, style.glowWidth, RailArt.a(style.color, a * style.glowAlpha * l0),
                        RailArt.a(style.color, a * style.glowAlpha * l1));
            }
            geo.line(x0, y0, x1, y1, style.width, RailArt.a(style.color, a * l0), RailArt.a(style.color, a * l1));
        }
    }
}
