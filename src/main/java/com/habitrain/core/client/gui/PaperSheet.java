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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * 可弯曲的纸面网格：把 {@link PaperCanvas} 上的车票按透视、摇摆与弯折贴回屏幕。
 *
 * <p>纸能弯却几乎不能伸缩，所以弯曲只沿一个方向发生（可展曲面：截面处处相同的柱面）。
 * 沿弯折方向的截面由拱起、S 形、边缘波纹与落地铰链叠加而成，再按弧长把票面点重新铺到截面上，
 * 弯得越厉害投影就收得越窄——这正是纸与「微弯硬卡片」在观感上的差别。之后依次绕 Y / X / Z 轴旋转，
 * 放到给定深度做透视投影。</p>
 *
 * <p>明暗：漫反射以平放贴屏时为 1，背光处变暗、迎光处可以变亮；另有一条随弯折滑动的高光。
 * 深色票面上单靠变暗几乎看不出曲率，高光才是纸面弯曲最主要的线索。{@link Pose#rest} 时网格与画布像素
 * 一一对应、漫反射为 1、高光为 0，静止画面与直接绘制完全一致。</p>
 */
final class PaperSheet {
    private static final int NX = 48;
    private static final int NY = 26;
    private static final int STRIDE = NX + 1;
    /** 截面采样数（弧长表）。 */
    private static final int PROFILE = 96;
    /** 焦距（相对屏幕长边）；越短透视越强，倾斜时弯折越明显。 */
    private static final float FOCAL = 0.95f;
    /** 指向光源的方向：左上方、略朝向观察者。 */
    private static final float LIGHT_X;
    private static final float LIGHT_Y;
    private static final float LIGHT_Z;
    /** 高光锐度。 */
    private static final float SHININESS = 40.0f;

    static {
        float x = -0.45f;
        float y = -0.55f;
        float z = -0.70f;
        float len = Mth.sqrt(x * x + y * y + z * z);
        LIGHT_X = x / len;
        LIGHT_Y = y / len;
        LIGHT_Z = z / len;
    }

    /**
     * 纸面姿态。弯折量都相对票面沿弯折方向的半长。
     *
     * @param cx          票面中心投影到屏幕上的 x
     * @param cy          票面中心投影到屏幕上的 y
     * @param depth       距离（1 = 贴在屏幕上；越大越远越小）
     * @param roll        绕视线旋转（弧度，正值顺时针）
     * @param pitch       绕水平轴前后倾（弧度，负值上缘远离）
     * @param yaw         绕竖直轴左右转（弧度）
     * @param bendAngle   弯折方向（票面内，弧度；0 = 沿票宽弯）
     * @param bend        对称拱起：两端远离、中间迎向观察者
     * @param curl        S 形：正值时弯折方向正端远离、负端翘向观察者
     * @param ripple      边缘波纹振幅（越靠两端越大）
     * @param ripplePhase 波纹相位
     * @param twist       扭转（对角两角一远一近）
     * @param hinge       落地铰链：hingeAt 之后的部分向后折起的程度
     * @param hingeAt     铰链位置（弯折方向上 -1..1，之前的部分已贴平）
     * @param crumple     褶皱起伏（相对票高）
     */
    record Pose(float cx, float cy, float depth, float roll, float pitch, float yaw,
                float bendAngle, float bend, float curl, float ripple, float ripplePhase, float twist,
                float hinge, float hingeAt, float crumple) {
        static Pose rest(float cx, float cy) {
            return new Pose(cx, cy, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        }

        Pose withCrumple(float amount) {
            return new Pose(cx, cy, depth, roll, pitch, yaw, bendAngle, bend, curl, ripple, ripplePhase, twist,
                    hinge, hingeAt, amount);
        }

        Pose shifted(float dx, float dy) {
            return new Pose(cx + dx, cy + dy, depth, roll, pitch, yaw, bendAngle, bend, curl, ripple, ripplePhase, twist,
                    hinge, hingeAt, crumple);
        }

        boolean flat() {
            return bend == 0.0f && curl == 0.0f && ripple == 0.0f && twist == 0.0f && (hinge == 0.0f || hingeAt >= 1.0f);
        }
    }

    /** 逐点额外抬向观察者的距离（相对票高），用于燃烧边缘卷起。 */
    @FunctionalInterface
    interface Lift {
        float at(float u, float v);
    }

    private final float[] wx = new float[STRIDE * (NY + 1)];
    private final float[] wy = new float[STRIDE * (NY + 1)];
    private final float[] wz = new float[STRIDE * (NY + 1)];
    private final float[] sx = new float[STRIDE * (NY + 1)];
    private final float[] sy = new float[STRIDE * (NY + 1)];
    private final float[] diffuse = new float[STRIDE * (NY + 1)];
    private final float[] specular = new float[STRIDE * (NY + 1)];
    private final float[] texU = new float[NX + 1];
    private final float[] texV = new float[NY + 1];
    private final float[] profT = new float[PROFILE + 1];
    private final float[] profZ = new float[PROFILE + 1];
    private final float[] profArc = new float[PROFILE + 1];

    /**
     * 按姿态计算网格。
     *
     * @param x0 票面在画布上的左上角（GUI 坐标）
     * @param w  票面宽
     */
    void layout(float screenW, float screenH, float x0, float y0, float w, float h, Pose pose, Lift lift, int seed) {
        float focal = Math.max(screenW, screenH) * FOCAL;
        float halfW = screenW / 2.0f;
        float halfH = screenH / 2.0f;
        float depth = Math.max(0.2f, pose.depth());
        float centerX = halfW + (pose.cx() - halfW) * depth;
        float centerY = halfH + (pose.cy() - halfH) * depth;
        float dist = focal * depth;
        float cosYaw = (float) Math.cos(pose.yaw());
        float sinYaw = (float) Math.sin(pose.yaw());
        float cosPitch = (float) Math.cos(pose.pitch());
        float sinPitch = (float) Math.sin(pose.pitch());
        float cosRoll = (float) Math.cos(pose.roll());
        float sinRoll = (float) Math.sin(pose.roll());
        float ex = (float) Math.cos(pose.bendAngle());
        float ey = (float) Math.sin(pose.bendAngle());
        float reach = Math.abs(w * 0.5f * ex) + Math.abs(h * 0.5f * ey);
        boolean bent = !pose.flat();
        if (bent) {
            this.buildProfile(pose);
        }
        for (int i = 0; i <= NX; i++) {
            this.texU[i] = PaperCanvas.u(x0 + w * i / NX);
        }
        for (int j = 0; j <= NY; j++) {
            this.texV[j] = PaperCanvas.v(y0 + h * j / NY);
        }
        for (int j = 0; j <= NY; j++) {
            float v = j / (float) NY;
            for (int i = 0; i <= NX; i++) {
                float u = i / (float) NX;
                int k = j * STRIDE + i;
                float lx = (u - 0.5f) * w;
                float ly = (v - 0.5f) * h;
                float lz = 0.0f;
                if (bent) {
                    // 沿弯折方向按弧长重新铺放：纸不伸长，弯起来的部分投影变短
                    float d = lx * ex + ly * ey;
                    float t = this.unrollT(d / reach);
                    float shift = t * reach - d;
                    lx += shift * ex;
                    ly += shift * ey;
                    lz += this.profileZ(t) * reach;
                    if (pose.twist() != 0.0f) {
                        lz += pose.twist() * (2.0f * u - 1.0f) * (2.0f * v - 1.0f) * h * 0.5f;
                    }
                }
                if (pose.crumple() != 0.0f) {
                    lz += pose.crumple() * h * (TicketArt.hash(seed * 7919 + k * 31) - 0.5f) * 2.0f;
                }
                if (lift != null) {
                    lz -= lift.at(u, v) * h;
                }
                float x1 = lx * cosYaw + lz * sinYaw;
                float z1 = -lx * sinYaw + lz * cosYaw;
                float y2 = ly * cosPitch - z1 * sinPitch;
                float z2 = ly * sinPitch + z1 * cosPitch;
                float x3 = x1 * cosRoll - y2 * sinRoll;
                float y3 = x1 * sinRoll + y2 * cosRoll;
                float px = centerX + x3;
                float py = centerY + y3;
                float pz = dist + z2;
                this.wx[k] = px;
                this.wy[k] = py;
                this.wz[k] = pz;
                float scale = focal / Math.max(focal * 0.2f, pz);
                this.sx[k] = halfW + (px - halfW) * scale;
                this.sy[k] = halfH + (py - halfH) * scale;
            }
        }
        this.shade(halfW, halfH);
    }

    /** 截面：拱起 + S 形 + 越往两端越大的波纹 + 铰链之后向后折起。t 为弯折方向上的 -1..1。 */
    private static float sectionZ(float t, Pose pose) {
        float env = 0.35f + 0.65f * t * t;
        float hingeDepth = Math.max(0.0f, t - pose.hingeAt());
        return pose.bend() * (t * t - 1.0f / 3.0f)
                + pose.curl() * (t * t * t - 0.6f * t)
                + pose.ripple() * env * Mth.sin(t * Mth.TWO_PI * 0.9f - pose.ripplePhase())
                + pose.hinge() * hingeDepth * hingeDepth;
    }

    /** 采样截面并累计弧长（从中心 t=0 起算，向两侧单调）。 */
    private void buildProfile(Pose pose) {
        int mid = PROFILE / 2;
        for (int i = 0; i <= PROFILE; i++) {
            float t = -1.0f + 2.0f * i / PROFILE;
            this.profT[i] = t;
            this.profZ[i] = sectionZ(t, pose);
        }
        this.profArc[mid] = 0.0f;
        for (int i = mid + 1; i <= PROFILE; i++) {
            this.profArc[i] = this.profArc[i - 1] + segment(i - 1, i);
        }
        for (int i = mid - 1; i >= 0; i--) {
            this.profArc[i] = this.profArc[i + 1] - segment(i, i + 1);
        }
    }

    private float segment(int a, int b) {
        float dt = this.profT[b] - this.profT[a];
        float dz = this.profZ[b] - this.profZ[a];
        return Mth.sqrt(dt * dt + dz * dz);
    }

    /** 弧长 s 处对应的截面参数 t（|t| ≤ |s|）。 */
    private float unrollT(float s) {
        if (s <= this.profArc[0]) {
            return this.profT[0];
        }
        if (s >= this.profArc[PROFILE]) {
            return this.profT[PROFILE];
        }
        int lo = 0;
        int hi = PROFILE;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (this.profArc[m] <= s) {
                lo = m;
            } else {
                hi = m;
            }
        }
        float f = (s - this.profArc[lo]) / Math.max(1.0e-6f, this.profArc[hi] - this.profArc[lo]);
        return Mth.lerp(f, this.profT[lo], this.profT[hi]);
    }

    /** 截面高度（按弧长表插值，与 {@link #unrollT} 配套）。 */
    private float profileZ(float t) {
        float f = Mth.clamp((t + 1.0f) * 0.5f * PROFILE, 0.0f, PROFILE);
        int i = Math.min(PROFILE - 1, (int) f);
        return Mth.lerp(f - i, this.profZ[i], this.profZ[i + 1]);
    }

    /** 逐顶点明暗：漫反射（相对平放贴屏）与随弯折滑动的高光（平放时为 0）。 */
    private void shade(float eyeX, float eyeY) {
        float restLambert = -LIGHT_Z;
        for (int j = 0; j <= NY; j++) {
            for (int i = 0; i <= NX; i++) {
                int k = j * STRIDE + i;
                int kr = j * STRIDE + Math.min(NX, i + 1);
                int kl = j * STRIDE + Math.max(0, i - 1);
                int kd = Math.min(NY, j + 1) * STRIDE + i;
                int ku = Math.max(0, j - 1) * STRIDE + i;
                float ux = this.wx[kr] - this.wx[kl];
                float uy = this.wy[kr] - this.wy[kl];
                float uz = this.wz[kr] - this.wz[kl];
                float vx = this.wx[kd] - this.wx[ku];
                float vy = this.wy[kd] - this.wy[ku];
                float vz = this.wz[kd] - this.wz[ku];
                // 朝向观察者的法线 = -(dU × dV)
                float nx = -(uy * vz - uz * vy);
                float ny = -(uz * vx - ux * vz);
                float nz = -(ux * vy - uy * vx);
                float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1.0e-6f) {
                    this.diffuse[k] = 1.0f;
                    this.specular[k] = 0.0f;
                    continue;
                }
                nx /= len;
                ny /= len;
                nz /= len;
                float lambert = nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z;
                float rel = Mth.clamp(lambert / restLambert, 0.0f, 1.3f);
                this.diffuse[k] = rel < 1.0f ? 1.0f - (1.0f - rel) * 0.85f : rel;
                // Blinn-Phong：视线指向屏幕中央的眼睛（z = 0）
                float tx = eyeX - this.wx[k];
                float ty = eyeY - this.wy[k];
                float tz = -this.wz[k];
                float tl = Mth.sqrt(tx * tx + ty * ty + tz * tz);
                float hx = LIGHT_X + tx / tl;
                float hy = LIGHT_Y + ty / tl;
                float hz = LIGHT_Z + tz / tl;
                float hl = Mth.sqrt(hx * hx + hy * hy + hz * hz);
                float nh = Math.max(0.0f, (nx * hx + ny * hy + nz * hz) / hl);
                float nh0 = Math.max(0.0f, -hz / hl);
                this.specular[k] = Math.max(0.0f, (float) (Math.pow(nh, SHININESS) - Math.pow(nh0, SHININESS)));
            }
        }
    }

    /** 票面 (u, v) 处当前在屏幕上的位置（网格双线性插值）。 */
    float projectX(float u, float v) {
        return sample(this.sx, u, v);
    }

    float projectY(float u, float v) {
        return sample(this.sy, u, v);
    }

    private static float sample(float[] values, float u, float v) {
        float fu = Mth.clamp(u, 0.0f, 1.0f) * NX;
        float fv = Mth.clamp(v, 0.0f, 1.0f) * NY;
        int i = Math.min(NX - 1, (int) fu);
        int j = Math.min(NY - 1, (int) fv);
        float tu = fu - i;
        float tv = fv - j;
        float top = Mth.lerp(tu, values[j * STRIDE + i], values[j * STRIDE + i + 1]);
        float bottom = Mth.lerp(tu, values[(j + 1) * STRIDE + i], values[(j + 1) * STRIDE + i + 1]);
        return Mth.lerp(tv, top, bottom);
    }

    /** 柔和投影：网格整体平移并逐层外扩，叠出由深到浅的边缘。 */
    void shadow(GuiGraphics g, float dx, float dy, float spread, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        float pcx = projectX(0.5f, 0.5f);
        float pcy = projectY(0.5f, 0.5f);
        int color = RailArt.a(0xFF000000, alpha * 0.22f);
        GuiGeo geo = GuiGeo.begin(g);
        for (int layer = 3; layer >= 1; layer--) {
            float grow = 1.0f + spread * layer / 3.0f;
            for (int j = 0; j < NY; j++) {
                for (int i = 0; i < NX; i++) {
                    int a = j * STRIDE + i;
                    int b = (j + 1) * STRIDE + i;
                    int c = b + 1;
                    int d = a + 1;
                    geo.quad(pcx + (this.sx[a] - pcx) * grow + dx, pcy + (this.sy[a] - pcy) * grow + dy, color,
                            pcx + (this.sx[b] - pcx) * grow + dx, pcy + (this.sy[b] - pcy) * grow + dy, color,
                            pcx + (this.sx[c] - pcx) * grow + dx, pcy + (this.sy[c] - pcy) * grow + dy, color,
                            pcx + (this.sx[d] - pcx) * grow + dx, pcy + (this.sy[d] - pcy) * grow + dy, color);
                }
            }
        }
        geo.end();
    }

    /**
     * 把画布纹理按网格贴到屏幕上（预乘 alpha 混合）。
     *
     * <p>用纸面着色器时顶点色编码为 r = 漫反射 / 2、g = 高光、a = 不透明度，由着色器解码；
     * shader 为 null 时退回原版 position_tex_color，只能表现变暗（rgb = 漫反射 × 不透明度）。
     * 调用前由调用方设置好着色器的自定义 uniform。</p>
     */
    void draw(GuiGraphics g, int texture, ShaderInstance shader, float alpha) {
        if (alpha <= 0.002f || texture == 0) {
            return;
        }
        g.flush();
        if (shader != null) {
            RenderSystem.setShader(() -> shader);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        }
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        Matrix4f matrix = g.pose().last().pose();
        boolean encoded = shader != null;
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int j = 0; j < NY; j++) {
            for (int i = 0; i < NX; i++) {
                this.emit(buffer, matrix, i, j, alpha, encoded);
                this.emit(buffer, matrix, i, j + 1, alpha, encoded);
                this.emit(buffer, matrix, i + 1, j + 1, alpha, encoded);
                this.emit(buffer, matrix, i + 1, j, alpha, encoded);
            }
        }
        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private void emit(BufferBuilder buffer, Matrix4f matrix, int i, int j, float alpha, boolean encoded) {
        int k = j * STRIDE + i;
        int color;
        if (encoded) {
            color = argb(this.diffuse[k] * 0.5f, this.specular[k], 0.0f, alpha);
        } else {
            float lit = Math.min(1.0f, this.diffuse[k]) * alpha;
            color = argb(lit, lit, lit, alpha);
        }
        buffer.addVertex(matrix, this.sx[k], this.sy[k], 0.0f)
                .setUv(this.texU[i], this.texV[j])
                .setColor(color);
    }

    static int argb(float r, float g, float b, float a) {
        return Math.round(Mth.clamp(a, 0.0f, 1.0f) * 255.0f) << 24
                | Math.round(Mth.clamp(r, 0.0f, 1.0f) * 255.0f) << 16
                | Math.round(Mth.clamp(g, 0.0f, 1.0f) * 255.0f) << 8
                | Math.round(Mth.clamp(b, 0.0f, 1.0f) * 255.0f);
    }
}
