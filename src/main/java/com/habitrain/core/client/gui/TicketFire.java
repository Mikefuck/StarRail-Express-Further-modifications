package com.habitrain.core.client.gui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 车票燃烧的 CPU 侧：燃烧场（与 {@code ticket_paper.fsh} 同一公式）以及沿火线飞起的火星、
 * 烧穿处飘起的纸灰与火光。
 *
 * <p>粒子在屏幕 GUI 坐标里运动；出生点取燃烧场中恰好处在火线上的票面位置，经
 * {@link PaperSheet} 当前网格换算到屏幕，因此纸面仍在变形时火星也贴着火线。</p>
 */
final class TicketFire {
    static final float FRONT_START = -0.05f;
    static final float FRONT_END = 1.45f;
    private static final int MAX_SPARKS = 320;
    private static final int MAX_ASH = 200;
    private static final int MAX_SMOKE = 40;
    private static final int MAX_LIGHTS = 8;

    private final float seed;
    private final RandomSource random = RandomSource.create();
    private final List<Mote> sparks = new ArrayList<>();
    private final List<Mote> ash = new ArrayList<>();
    private final List<Mote> smoke = new ArrayList<>();
    private final float[] lightX = new float[MAX_LIGHTS];
    private final float[] lightY = new float[MAX_LIGHTS];
    private int lights;

    TicketFire(float seed) {
        this.seed = seed;
    }

    // ==================== 燃烧场 ====================

    /** Burn 进度 → 燃烧前沿在燃烧场里的位置。 */
    static float front(float burn) {
        return Mth.lerp(burn, FRONT_START, FRONT_END);
    }

    /** 票面 (u, v)（0..1，v 向下）处的燃烧场值；小于前沿即已烧穿。 */
    float field(float u, float v, float aspect) {
        float qx = u * aspect;
        float qy = v;
        float d1x = qx - aspect * 0.18f;
        float d1y = (qy - 1.06f) * 0.72f;
        float d2x = qx - aspect * 0.90f;
        float d2y = (qy - 1.04f) * 0.72f;
        float r = Math.min(Mth.sqrt(d1x * d1x + d1y * d1y), Mth.sqrt(d2x * d2x + d2y * d2y) + 0.30f);
        return r + (fbm(qx * 3.0f + this.seed, qy * 3.0f + this.seed * 0.7f) - 0.5f) * 0.5f
                + (noise(qx * 11.0f + this.seed * 1.3f, qy * 11.0f + 4.0f) - 0.5f) * 0.06f;
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    private static float hash12(float x, float y) {
        float p3x = fract(x * 0.1031f);
        float p3y = fract(y * 0.1031f);
        float p3z = fract(x * 0.1031f);
        float dot = p3x * (p3y + 33.33f) + p3y * (p3z + 33.33f) + p3z * (p3x + 33.33f);
        p3x += dot;
        p3y += dot;
        p3z += dot;
        return fract((p3x + p3y) * p3z);
    }

    private static float noise(float x, float y) {
        float ix = (float) Math.floor(x);
        float iy = (float) Math.floor(y);
        float fx = x - ix;
        float fy = y - iy;
        float ux = fx * fx * (3.0f - 2.0f * fx);
        float uy = fy * fy * (3.0f - 2.0f * fy);
        float a = hash12(ix, iy);
        float b = hash12(ix + 1.0f, iy);
        float c = hash12(ix, iy + 1.0f);
        float d = hash12(ix + 1.0f, iy + 1.0f);
        return Mth.lerp(uy, Mth.lerp(ux, a, b), Mth.lerp(ux, c, d));
    }

    private static float fbm(float x, float y) {
        float sum = 0.0f;
        float amp = 0.5f;
        for (int i = 0; i < 4; i++) {
            sum += amp * noise(x, y);
            x = x * 2.03f + 17.1f;
            y = y * 2.03f + 9.7f;
            amp *= 0.5f;
        }
        return sum / 0.9375f;
    }

    // ==================== 粒子 ====================

    /**
     * 推进粒子；spawning 时沿当前火线撒下新的火星与纸灰。
     *
     * @param scale 速度与尺寸的基准（车票高度 / 400）
     */
    void update(float dt, float burn, boolean spawning, PaperSheet sheet, float aspect, float scale) {
        for (Iterator<Mote> it = this.sparks.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            m.age += dt;
            if (m.age >= m.life) {
                it.remove();
                continue;
            }
            m.vx += Mth.sin(m.age * 9.0f + m.phase) * 70.0f * scale * dt;
            m.vy -= 60.0f * scale * dt;
            m.vx *= 1.0f - 0.9f * dt;
            m.vy *= 1.0f - 0.5f * dt;
            m.x += m.vx * dt;
            m.y += m.vy * dt;
        }
        for (Iterator<Mote> it = this.ash.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            m.age += dt;
            if (m.age >= m.life) {
                it.remove();
                continue;
            }
            m.vx += Mth.sin(m.age * 2.6f + m.phase) * 22.0f * scale * dt;
            m.vy -= 8.0f * scale * dt;
            m.vx *= 1.0f - 0.6f * dt;
            m.vy *= 1.0f - 0.4f * dt;
            m.x += m.vx * dt;
            m.y += m.vy * dt;
            m.angle += m.spin * dt;
        }
        for (Iterator<Mote> it = this.smoke.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            m.age += dt;
            if (m.age >= m.life) {
                it.remove();
                continue;
            }
            m.vx += Mth.sin(m.age * 1.7f + m.phase) * 10.0f * scale * dt;
            m.vy *= 1.0f - 0.3f * dt;
            m.x += m.vx * dt;
            m.y += m.vy * dt;
        }
        this.lights = 0;
        if (!spawning || burn <= 0.0f || burn >= 1.0f) {
            return;
        }
        float front = front(burn);
        int tries = Math.min(500, Math.round(dt * 3200.0f));
        for (int n = 0; n < tries; n++) {
            float u = this.random.nextFloat();
            float v = this.random.nextFloat();
            float d = this.field(u, v, aspect) - front;
            if (d >= 0.0f && d < 0.02f) {
                float x = sheet.projectX(u, v);
                float y = sheet.projectY(u, v);
                if (this.lights < MAX_LIGHTS && this.random.nextFloat() < 0.3f) {
                    this.lightX[this.lights] = x;
                    this.lightY[this.lights] = y;
                    this.lights++;
                }
                if (this.sparks.size() < MAX_SPARKS && this.random.nextFloat() < 0.7f) {
                    this.sparks.add(this.spark(x, y, scale));
                }
                if (this.smoke.size() < MAX_SMOKE && this.random.nextFloat() < 0.04f) {
                    this.smoke.add(this.puff(x, y, scale));
                }
            } else if (d < 0.0f && d > -0.04f && this.ash.size() < MAX_ASH && this.random.nextFloat() < 0.25f) {
                this.ash.add(this.flake(sheet.projectX(u, v), sheet.projectY(u, v), scale));
            }
        }
    }

    private Mote puff(float x, float y, float scale) {
        Mote m = new Mote();
        m.x = x;
        m.y = y;
        m.vx = (this.random.nextFloat() - 0.5f) * 10.0f * scale;
        m.vy = -(14.0f + this.random.nextFloat() * 18.0f) * scale;
        m.life = 1.6f + this.random.nextFloat() * 1.0f;
        m.size = (9.0f + this.random.nextFloat() * 9.0f) * scale;
        m.phase = this.random.nextFloat() * Mth.TWO_PI;
        return m;
    }

    private Mote spark(float x, float y, float scale) {
        Mote m = new Mote();
        m.x = x;
        m.y = y;
        m.vx = (this.random.nextFloat() - 0.5f) * 40.0f * scale;
        m.vy = -(25.0f + this.random.nextFloat() * 75.0f) * scale;
        m.life = 0.45f + this.random.nextFloat() * 0.8f;
        m.size = (0.6f + this.random.nextFloat() * 1.0f) * Math.max(1.0f, scale);
        m.phase = this.random.nextFloat() * Mth.TWO_PI;
        return m;
    }

    private Mote flake(float x, float y, float scale) {
        Mote m = new Mote();
        m.x = x;
        m.y = y;
        m.vx = (this.random.nextFloat() - 0.5f) * 24.0f * scale;
        m.vy = -(10.0f + this.random.nextFloat() * 30.0f) * scale;
        m.life = 1.3f + this.random.nextFloat() * 1.3f;
        m.size = (1.6f + this.random.nextFloat() * 3.8f) * scale;
        m.phase = this.random.nextFloat() * Mth.TWO_PI;
        m.angle = this.random.nextFloat() * Mth.TWO_PI;
        m.spin = (this.random.nextFloat() - 0.5f) * 6.0f;
        m.tumble = 1.5f + this.random.nextFloat() * 3.0f;
        m.ember = this.random.nextFloat() < 0.6f;
        for (int i = 0; i < 4; i++) {
            m.corner[i] = 0.7f + this.random.nextFloat() * 0.5f;
        }
        return m;
    }

    /**
     * 烟 → 火光（叠加）→ 纸灰 → 纸灰余烬与火星（叠加）。
     *
     * @param intensity 火光强度
     * @param fade      整体不透明度（退场末尾让残余粒子淡出）
     */
    void render(GuiGraphics g, float intensity, float fade) {
        if (fade <= 0.01f) {
            return;
        }
        if (!this.smoke.isEmpty()) {
            GuiGeo haze = GuiGeo.begin(g);
            for (Mote m : this.smoke) {
                float t = m.age / m.life;
                float r = m.size * (1.0f + 2.2f * t);
                float alpha = Mth.sin(Math.min(1.0f, t * 4.0f) * Mth.HALF_PI) * (1.0f - t) * 0.28f * fade;
                haze.softGlow(m.x, m.y, r, r * 0.85f, 0xFF2E2925, Math.round(255 * alpha));
            }
            haze.end();
        }
        if (this.lights > 0 && intensity > 0.01f) {
            GuiGeo light = GuiGeo.glow(g);
            for (int i = 0; i < this.lights; i++) {
                float r = 34.0f + TicketArt.hash(i * 31 + this.sparks.size()) * 30.0f;
                light.softGlow(this.lightX[i], this.lightY[i], r, r * 0.8f, 0xFFFF7A2A, Math.round(30 * intensity * fade));
            }
            light.end();
        }
        if (!this.ash.isEmpty()) {
            GuiGeo dark = GuiGeo.begin(g);
            for (Mote m : this.ash) {
                float t = m.age / m.life;
                float alpha = (float) Math.pow(1.0f - t, 0.8f) * 0.85f * Math.min(1.0f, m.age * 10.0f) * fade;
                int color = GuiGeo.lerpColor(0xFF2E1A10, 0xFF0D0A08, Math.min(1.0f, t * 2.5f));
                this.flakeQuad(dark, m, 1.0f, RailArt.a(color, alpha));
            }
            dark.end();
        }
        if (this.sparks.isEmpty() && this.ash.isEmpty()) {
            return;
        }
        GuiGeo glow = GuiGeo.glow(g);
        for (Mote m : this.ash) {
            float t = m.age / m.life;
            if (m.ember && t < 0.45f) {
                float heat = 1.0f - t / 0.45f;
                this.flakeQuad(glow, m, 0.55f, RailArt.a(0xFFFF6A1E, 0.8f * heat * fade));
            }
        }
        for (Mote m : this.sparks) {
            float t = m.age / m.life;
            int color = t < 0.25f
                    ? GuiGeo.lerpColor(0xFFFFF2C0, 0xFFFFB050, t / 0.25f)
                    : GuiGeo.lerpColor(0xFFFFB050, 0xFFE0381A, (t - 0.25f) / 0.75f);
            float alpha = (float) Math.pow(1.0f - t, 1.3f) * fade;
            int head = RailArt.a(color, alpha);
            glow.line(m.x, m.y, m.x - m.vx * 0.04f, m.y - m.vy * 0.04f, m.size, head, RailArt.a(color, 0));
            glow.diamond(m.x, m.y, m.size * 0.9f, m.size * 0.9f, head);
        }
        glow.end();
    }

    /** 不规则四边形纸灰，绕自身旋转并左右翻滚。 */
    private void flakeQuad(GuiGeo geo, Mote m, float size, int color) {
        float flip = 0.25f + 0.75f * Math.abs(Mth.cos(m.age * m.tumble + m.phase));
        float[] xs = new float[4];
        float[] ys = new float[4];
        float cos = Mth.cos(m.angle);
        float sin = Mth.sin(m.angle);
        for (int i = 0; i < 4; i++) {
            float ang = i * Mth.HALF_PI + 0.35f * i;
            float r = m.size * size * m.corner[i];
            float lx = Mth.cos(ang) * r * flip;
            float ly = Mth.sin(ang) * r;
            xs[i] = m.x + lx * cos - ly * sin;
            ys[i] = m.y + lx * sin + ly * cos;
        }
        geo.quad(xs[0], ys[0], color, xs[1], ys[1], color, xs[2], ys[2], color, xs[3], ys[3], color);
    }

    private static final class Mote {
        float x;
        float y;
        float vx;
        float vy;
        float age;
        float life;
        float size;
        float phase;
        float angle;
        float spin;
        float tumble;
        boolean ember;
        final float[] corner = new float[4];
    }
}
