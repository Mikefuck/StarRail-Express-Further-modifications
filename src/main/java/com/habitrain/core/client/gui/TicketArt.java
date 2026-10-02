package com.habitrain.core.client.gui;

import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 「车票」视觉组件：投票面板的小票、开局转场的票堆与登车凭证、结算纪念票共用。
 *
 * <p>所有绘制都以当前 pose 原点为票面左上角、使用票面局部坐标；调用方只需平移/旋转/缩放
 * pose 就能让整张票飞行、飘动或撕开。票面右侧为票根，二者之间是上下带半圆缺口的虚线撕票线；
 * {@link Part} 允许把票身与票根分开绘制，用于撕票动画。</p>
 */
final class TicketArt {
    private TicketArt() {
    }

    /** 票面配色；颜色均为 ARGB，guilloche 自带透明度。 */
    record Palette(int paperTop, int paperBottom, int band, int bandInk, int ink, int inkSoft,
                   int trim, int guilloche, int serial, int edge) {
        /** 两套配色按 t 线性混合（用于小票选中时由蓝票过渡为头等票）。 */
        static Palette mix(Palette a, Palette b, float t) {
            if (t <= 0.0f) {
                return a;
            }
            if (t >= 1.0f) {
                return b;
            }
            return new Palette(GuiGeo.lerpColor(a.paperTop, b.paperTop, t),
                    GuiGeo.lerpColor(a.paperBottom, b.paperBottom, t),
                    GuiGeo.lerpColor(a.band, b.band, t), GuiGeo.lerpColor(a.bandInk, b.bandInk, t),
                    GuiGeo.lerpColor(a.ink, b.ink, t), GuiGeo.lerpColor(a.inkSoft, b.inkSoft, t),
                    GuiGeo.lerpColor(a.trim, b.trim, t), GuiGeo.lerpColor(a.guilloche, b.guilloche, t),
                    GuiGeo.lerpColor(a.serial, b.serial, t), GuiGeo.lerpColor(a.edge, b.edge, t));
        }
    }

    /** 头等座：象牙纸 + 海军蓝票头 + 棕墨与金箔。登车凭证与已投票的小票使用。 */
    static final Palette IVORY = new Palette(0xFFF8EED3, 0xFFE9D7AD, 0xFF1D2A52, 0xFFF4D894,
            0xFF2A2114, 0xFF7C6849, 0xFFB38D47, 0x40A88848, 0xFFB3362C, 0x99594522);
    static final Palette SAPPHIRE = new Palette(0xFF243D78, 0xFF132352, 0xFF0A1330, 0xFFF4D894,
            0xFFF3EAD3, 0xFFA3B4DA, 0xFFD0A658, 0x308FB0FF, 0xFFFF8A80, 0x70B9C8F0);
    static final Palette BURGUNDY = new Palette(0xFF6E2438, 0xFF471527, 0xFF260912, 0xFFF4D894,
            0xFFF8EADA, 0xFFDDAEB6, 0xFFD8B064, 0x34FFB4C4, 0xFFF4D894, 0x70F0C0C8);
    static final Palette EMERALD = new Palette(0xFF1F5E4F, 0xFF113A30, 0xFF071E18, 0xFFF4D894,
            0xFFF0F2E2, 0xFFA3CDBC, 0xFFD0A658, 0x309CFFD8, 0xFFFF9C88, 0x70BFEFE0);
    static final Palette MIDNIGHT = new Palette(0xFF1B2650, 0xFF0B1127, 0xFF050916, 0xFFF4D894,
            0xFFFFF4DA, 0xFFA2ABC4, 0xFFD0A658, 0x26C4D8FF, 0xFFFF7070, 0x99D0A658);

    static final Palette[] PILE = {SAPPHIRE, BURGUNDY, IVORY, EMERALD, SAPPHIRE, MIDNIGHT};

    /** 撕票时分别绘制票身与票根。 */
    enum Part {
        WHOLE, BODY, STUB
    }

    /**
     * 票面几何。
     *
     * @param stubX  撕票线 x
     * @param notch  撕票线两端半圆缺口半径
     * @param corner 外角切角
     * @param band   票头色带高度
     * @param inset  内框到票边的距离
     */
    record Shape(float w, float h, float stubX, float notch, float corner, float band, float inset) {
        static Shape of(float w, float h, float stubFraction) {
            float notch = Mth.clamp(h * 0.065f, 2.5f, 8.0f);
            float corner = Mth.clamp(h * 0.05f, 2.0f, 7.0f);
            float band = Mth.clamp(h * 0.15f, 8.0f, 30.0f);
            float inset = Mth.clamp(h * 0.035f, 2.0f, 5.0f);
            return new Shape(w, h, Math.round(w * stubFraction), notch, corner, band, inset);
        }

        /** 票身内容区右缘（撕票线左侧留白之后）。 */
        float bodyRight() {
            return stubX - notch - inset;
        }

        /** 票根内容区左缘。 */
        float stubLeft() {
            return stubX + notch + inset;
        }
    }

    // ==================== 票纸 ====================

    /** 票纸底色 + 票头色带。 */
    static void paper(GuiGraphics g, Shape s, Palette p, Part part, float alpha) {
        float w = s.w;
        float h = s.h;
        float px = s.stubX;
        float r = s.notch;
        float c = s.corner;
        float b = s.band;
        int top = fade(p.paperTop, alpha);
        int bottom = fade(p.paperBottom, alpha);
        GuiGeo geo = GuiGeo.begin(g);
        if (part != Part.STUB) {
            geo.convexV(new float[] {c, 0, px - r, 0, px - r, h, c, h, 0, h - c, 0, c}, 0, h, top, bottom);
            notchColumn(geo, px - r, px, px, r, h, top, bottom);
        }
        if (part != Part.BODY) {
            notchColumn(geo, px, px + r, px, r, h, top, bottom);
            geo.convexV(new float[] {px + r, 0, w - c, 0, w, c, w, h - c, w - c, h, px + r, h}, 0, h, top, bottom);
        }
        int band = fade(p.band, alpha);
        int bandLow = GuiGeo.lerpColor(band, fade(0xFF000000, alpha), 0.18f);
        if (part != Part.STUB) {
            geo.convexV(new float[] {c, 0, px - r, 0, px - r, b, 0, b, 0, c}, 0, b, band, bandLow);
        }
        if (part != Part.BODY) {
            geo.convexV(new float[] {px + r, 0, w - c, 0, w, c, w, b, px + r, b}, 0, b, band, bandLow);
        }
        geo.end();
    }

    /** 撕票线所在竖条：上下各被半圆缺口挖去。 */
    private static void notchColumn(GuiGeo geo, float xa, float xb, float cx, float r, float h, int top, int bottom) {
        int slices = 6;
        for (int i = 0; i < slices; i++) {
            float x0 = Mth.lerp(i / (float) slices, xa, xb);
            float x1 = Mth.lerp((i + 1) / (float) slices, xa, xb);
            float d0 = notchDepth(x0, cx, r);
            float d1 = notchDepth(x1, cx, r);
            geo.quad(x0, d0, GuiGeo.lerpColor(top, bottom, d0 / h),
                    x0, h - d0, GuiGeo.lerpColor(top, bottom, (h - d0) / h),
                    x1, h - d1, GuiGeo.lerpColor(top, bottom, (h - d1) / h),
                    x1, d1, GuiGeo.lerpColor(top, bottom, d1 / h));
        }
    }

    private static float notchDepth(float x, float cx, float r) {
        float dx = x - cx;
        return dx * dx >= r * r ? 0.0f : Mth.sqrt(r * r - dx * dx);
    }

    /**
     * 票面细节：防伪波纹、团花、双线内框、撕票虚线与纸边。
     *
     * @param rich 是否绘制团花与纸面斑点（大票使用；小票省略以保持清爽）
     */
    static void details(GuiGraphics g, Shape s, Palette p, Part part, float alpha, int seed, boolean rich) {
        float w = s.w;
        float h = s.h;
        float px = s.stubX;
        float r = s.notch;
        float in = s.inset;
        float b = s.band;
        GuiGeo geo = GuiGeo.begin(g);
        if (part != Part.STUB) {
            // 防伪波纹：几道相位不同的正弦细线，像钞票底纹
            float left = in + 3.0f;
            float right = s.bodyRight() - 3.0f;
            int lines = h > 70 ? 7 : 4;
            int ink = fade(p.guilloche, alpha);
            float step = Math.max(3.0f, (right - left) / Math.max(12.0f, (right - left) / 5.0f));
            for (int k = 0; k < lines; k++) {
                float base = Mth.lerp((k + 0.5f) / lines, b + 4.0f, h - in - 3.0f);
                float amp = Math.min(3.5f, (h - b) * 0.035f + 1.0f);
                float phase = hash(seed * 7 + k) * Mth.TWO_PI;
                float prevX = left;
                float prevY = base + wave(left, amp, phase);
                for (float x = left + step; x <= right + 0.01f; x += step) {
                    float y = base + wave(x, amp, phase);
                    geo.line(prevX, prevY, x, y, 0.6f, ink);
                    prevX = x;
                    prevY = y;
                }
            }
            if (rich) {
                rosette(geo, s.bodyRight() - (h - b) * 0.42f, (b + h) * 0.5f + 1.0f, (h - b) * 0.36f,
                        fade(p.guilloche, alpha * 1.4f));
            }
            // 内框：外细线 + 内更细线
            int trim = fade(p.trim, alpha);
            geo.outline(RailArt.chamfer(in, in, px - r - in * 2.0f, h - in * 2.0f, s.corner * 0.7f), 0.8f, trim);
            if (rich) {
                geo.outline(RailArt.chamfer(in + 2.0f, b + 2.0f, px - r - in * 2.0f - 4.0f, h - b - in - 4.0f, 1.5f), 0.5f,
                        fade(p.trim, alpha * 0.55f));
            }
        }
        if (part != Part.BODY) {
            int trim = fade(p.trim, alpha);
            float sx = px + r + in;
            geo.outline(RailArt.chamfer(sx, in, w - sx - in, h - in * 2.0f, s.corner * 0.7f), 0.8f, trim);
        }
        if (part == Part.WHOLE) {
            int dash = fade(p.inkSoft, alpha * 0.85f);
            for (float y = r + 2.0f; y < h - r - 2.0f; y += 4.0f) {
                geo.rect(px - 0.5f, y, px + 0.5f, Math.min(h - r - 2.0f, y + 2.0f), dash);
            }
        }
        if (rich && part != Part.STUB && (p.paperTop & 0x00FFFFFF) > 0x00C00000) {
            // 浅色纸的旧纸斑点
            for (int i = 0; i < 26; i++) {
                float x = in + hash(seed * 13 + i) * (s.bodyRight() - in);
                float y = b + hash(seed * 29 + i * 3) * (h - b - in);
                float size = 0.6f + hash(seed + i * 17) * 0.8f;
                geo.rect(x, y, x + size, y + size, fade(0x22604020, alpha));
            }
        }
        edge(geo, s, part, fade(p.edge, alpha));
        geo.end();
    }

    private static float wave(float x, float amp, float phase) {
        return Mth.sin(x * 0.11f + phase) * amp + Mth.sin(x * 0.043f + phase * 1.7f) * amp * 0.6f;
    }

    /** 防伪团花（内摆线），票面右侧的淡色水印。 */
    private static void rosette(GuiGeo geo, float cx, float cy, float radius, int color) {
        if (radius < 6.0f) {
            return;
        }
        float bigR = radius * 0.62f;
        float smallR = radius * 0.16f;
        float d = radius * 0.38f;
        int segments = 144;
        float prevX = 0.0f;
        float prevY = 0.0f;
        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments * Mth.TWO_PI * 4.0f;
            float k = (bigR - smallR) / smallR;
            float x = cx + (bigR - smallR) * Mth.cos(t) + d * Mth.cos(k * t);
            float y = cy + (bigR - smallR) * Mth.sin(t) - d * Mth.sin(k * t);
            if (i > 0) {
                geo.line(prevX, prevY, x, y, 0.5f, color);
            }
            prevX = x;
            prevY = y;
        }
        geo.ring(cx, cy, radius - 0.6f, radius, color);
    }

    /** 纸边细线，沿外轮廓（含半圆缺口）描一圈；撕开时补上撕口的锯齿边。 */
    private static void edge(GuiGeo geo, Shape s, Part part, int color) {
        float w = s.w;
        float h = s.h;
        float px = s.stubX;
        float r = s.notch;
        float c = s.corner;
        float t = 0.7f;
        if (part != Part.STUB) {
            geo.line(c, 0, px - r, 0, t, color);
            geo.line(0, c, c, 0, t, color);
            geo.line(0, c, 0, h - c, t, color);
            geo.line(0, h - c, c, h, t, color);
            geo.line(c, h, px - r, h, t, color);
            geo.arc(px, 0, r - t * 0.5f, r + t * 0.5f, 1.0f, Mth.HALF_PI, Mth.PI, color, color);
            geo.arc(px, h, r - t * 0.5f, r + t * 0.5f, 1.0f, Mth.PI, Mth.PI + Mth.HALF_PI, color, color);
        }
        if (part != Part.BODY) {
            geo.line(px + r, 0, w - c, 0, t, color);
            geo.line(w - c, 0, w, c, t, color);
            geo.line(w, c, w, h - c, t, color);
            geo.line(w, h - c, w - c, h, t, color);
            geo.line(w - c, h, px + r, h, t, color);
            geo.arc(px, 0, r - t * 0.5f, r + t * 0.5f, 1.0f, 0.0f, Mth.HALF_PI, color, color);
            geo.arc(px, h, r - t * 0.5f, r + t * 0.5f, 1.0f, -Mth.HALF_PI, 0.0f, color, color);
        }
        if (part != Part.WHOLE) {
            // 撕口：沿撕票线的细锯齿
            float dir = part == Part.BODY ? 1.0f : -1.0f;
            float prevX = px;
            float prevY = r;
            int i = 0;
            for (float y = r + 2.0f; y <= h - r; y += 2.0f, i++) {
                float x = px + dir * ((i & 1) == 0 ? 0.9f : -0.2f);
                geo.line(prevX, prevY, x, y, t, color);
                prevX = x;
                prevY = y;
            }
        }
    }

    // ==================== 光影 ====================

    /**
     * 柔和投影：三层逐渐外扩的半透明切角矩形，叠出由深到浅的边缘。
     * 调用方负责把 pose 平移到投影偏移位置。
     */
    static void shadow(GuiGraphics g, Shape s, float spread, float alpha) {
        shadow(g, s, Part.WHOLE, spread, alpha);
    }

    /** 只投出票身或票根那一部分的影子（撕开后两部分各自投影）。 */
    static void shadow(GuiGraphics g, Shape s, Part part, float spread, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        float left = part == Part.STUB ? s.stubX : 0.0f;
        float right = part == Part.BODY ? s.stubX : s.w;
        GuiGeo geo = GuiGeo.begin(g);
        for (int i = 3; i >= 1; i--) {
            float e = spread * i / 3.0f;
            float[] shape = RailArt.chamfer(left - e, -e, right - left + e * 2.0f, s.h + e * 2.0f, s.corner + e);
            int color = RailArt.a(0xFF000000, alpha * 0.24f);
            geo.convexV(shape, -e, s.h + e, color, color);
        }
        geo.end();
    }

    /**
     * 斜向光泽扫过票面（叠加混合）。progress 0→1 时光带从左上扫到右下。
     */
    static void sheen(GuiGraphics g, Shape s, float progress, float alpha) {
        if (alpha <= 0.01f || progress <= 0.0f || progress >= 1.0f) {
            return;
        }
        float slope = 0.55f;
        float half = Math.max(10.0f, s.w * 0.07f);
        float travel = s.w + s.h * slope + half * 2.0f;
        float center = -half + travel * progress;
        int strips = 14;
        GuiGeo geo = GuiGeo.glow(g);
        int clear = RailArt.a(RailArt.CHAMPAGNE, 0);
        int bright = RailArt.a(RailArt.CHAMPAGNE, alpha * 0.32f);
        for (int i = 0; i < strips; i++) {
            float y0 = s.h * i / strips;
            float y1 = s.h * (i + 1) / strips;
            float c0 = center - y0 * slope;
            float c1 = center - y1 * slope;
            quadClipped(geo, c0 - half, c0, c1, c1 - half, y0, y1, s.w, clear, bright);
            quadClipped(geo, c0, c0 + half, c1 + half, c1, y0, y1, s.w, bright, clear);
        }
        geo.end();
    }

    /** 水平条带内的斜四边形，x 夹到 [0, maxX]；左右两边分别着色。 */
    private static void quadClipped(GuiGeo geo, float topLeft, float topRight, float bottomRight, float bottomLeft,
                                    float y0, float y1, float maxX, int leftColor, int rightColor) {
        float tl = Mth.clamp(topLeft, 0.0f, maxX);
        float tr = Mth.clamp(topRight, 0.0f, maxX);
        float br = Mth.clamp(bottomRight, 0.0f, maxX);
        float bl = Mth.clamp(bottomLeft, 0.0f, maxX);
        if (tr - tl < 0.05f && br - bl < 0.05f) {
            return;
        }
        geo.quad(tl, y0, leftColor, bl, y1, leftColor, br, y1, rightColor, tr, y0, rightColor);
    }

    // ==================== 印章 / 打孔 / 条码 ====================

    /**
     * 检票印章：双线切角框 + 粗体主文字 + 宽字距副文字，带些许缺墨颗粒。
     * 以 (cx, cy) 为中心绘制。
     *
     * @param scale 整体缩放（盖章冲击动画由调用方从 &gt;1 落到 1）
     * @param paper 纸色，用于点出缺墨颗粒
     */
    static void stamp(GuiGraphics g, Font font, float cx, float cy, Component main, String sub,
                      float textScale, float scale, float rotDeg, int color, float alpha, int paper, int seed) {
        if (alpha <= 0.02f || scale <= 0.01f) {
            return;
        }
        Component bold = main.copy().withStyle(ChatFormatting.BOLD);
        float mainW = font.width(bold) * textScale;
        boolean hasSub = sub != null && !sub.isBlank();
        float subScale = hasSub ? crispFor(sub, textScale * 0.42f) : 0.0f;
        float[] box = stampBox(font, main, sub, textScale);
        float boxW = box[0];
        float boxH = box[1];
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rotDeg));
        g.pose().scale(scale, scale, 1.0f);
        int ink = RailArt.a(color, alpha * 0.92f);
        GuiGeo geo = GuiGeo.begin(g);
        geo.outline(RailArt.chamfer(-boxW / 2.0f, -boxH / 2.0f, boxW, boxH, 4.0f), 1.6f, ink);
        geo.outline(RailArt.chamfer(-boxW / 2.0f + 2.6f, -boxH / 2.0f + 2.6f, boxW - 5.2f, boxH - 5.2f, 2.5f), 0.7f, ink);
        float pad = 6.0f * Math.max(1.0f, textScale * 0.6f);
        geo.diamond(-boxW / 2.0f + pad, 0.0f, 1.6f, 1.6f, ink);
        geo.diamond(boxW / 2.0f - pad, 0.0f, 1.6f, 1.6f, ink);
        geo.end();
        float textTop = -boxH / 2.0f + 5.0f;
        drawScaled(g, font, bold, -mainW / 2.0f, textTop, textScale, ink);
        if (hasSub) {
            TransitionFx.drawTracked(g, font, sub, 0.0f, textTop + 8.0f * textScale + 3.0f, subScale, 1.4f,
                    ink, 1.0f, 0.0f);
        }
        // 缺墨颗粒：在印面上点几粒纸色
        GuiGeo grain = GuiGeo.begin(g);
        int dots = 30;
        for (int i = 0; i < dots; i++) {
            float x = (hash(seed * 41 + i) - 0.5f) * boxW;
            float y = (hash(seed * 59 + i * 7) - 0.5f) * boxH;
            float size = 0.5f + hash(seed * 3 + i * 11) * 0.9f;
            grain.rect(x, y, x + size, y + size, RailArt.a(paper, alpha * 0.55f));
        }
        grain.end();
        g.pose().popPose();
    }

    /** 印章外框的宽高（未缩放、未旋转），与 {@link #stamp} 的排版一致。 */
    static float[] stampBox(Font font, Component main, String sub, float textScale) {
        float mainW = font.width(main.copy().withStyle(ChatFormatting.BOLD)) * textScale;
        boolean hasSub = sub != null && !sub.isBlank();
        float subScale = hasSub ? crispFor(sub, textScale * 0.42f) : 0.0f;
        float subW = hasSub ? TransitionFx.trackedWidth(font, sub, 1.4f) * subScale : 0.0f;
        float boxW = Math.max(mainW, subW) + 26.0f * Math.max(1.0f, textScale * 0.6f);
        float boxH = 8.0f * textScale + (hasSub ? 8.0f * subScale + 4.0f : 0.0f) + 10.0f;
        return new float[] {boxW, boxH};
    }

    /**
     * 盖得太重的印泥顺着章框下缘往下淌：几道粗细不一的墨痕，末端坠着一颗墨珠。
     * 坐标与旋转同 {@link #stamp}；progress 0→1 时墨痕从章框下缘流到最长。
     *
     * @param length 最长一道墨痕的长度
     */
    static void stampDrips(GuiGraphics g, float cx, float cy, float boxW, float boxH, float rotDeg, float length,
                           int color, float progress, int seed) {
        if (progress <= 0.0f) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rotDeg));
        GuiGeo geo = GuiGeo.begin(g);
        int drips = 5;
        float bottom = boxH / 2.0f - 0.4f;
        for (int i = 0; i < drips; i++) {
            float x = (-0.4f + 0.8f * (i + hash(seed * 13 + i) * 0.7f) / drips) * boxW;
            // 每道墨痕起流时刻与流速不同，粗的流得长
            float start = hash(seed * 17 + i * 3) * 0.35f;
            float t = TransitionFx.clamp01((progress - start) / (1.0f - start));
            if (t <= 0.0f) {
                continue;
            }
            float width = 0.9f + hash(seed * 23 + i * 5) * 1.3f;
            float reach = length * (0.3f + 0.7f * hash(seed * 29 + i * 7)) * (0.55f + 0.45f * width / 2.2f);
            float flow = reach * (1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t));
            int ink = RailArt.a(color, ((color >>> 24) & 0xFF) / 255.0f * 0.9f);
            // 章框下缘处的墨堆：上宽下窄
            geo.quad(x - width * 1.6f, bottom, ink, x - width * 0.5f, bottom + Math.min(flow, width * 2.5f), ink,
                    x + width * 0.5f, bottom + Math.min(flow, width * 2.5f), ink, x + width * 1.6f, bottom, ink);
            geo.rect(x - width * 0.5f, bottom, x + width * 0.5f, bottom + flow, ink);
            float bead = width * (0.75f + 0.25f * t);
            geo.disc(x, bottom + flow, bead, bead * 1.25f, ink, ink);
        }
        geo.end();
        g.pose().popPose();
    }

    /** 盖章落下瞬间溅出的墨点（随 progress 0→1 外扩并淡出）。 */
    static void inkSplash(GuiGraphics g, float cx, float cy, float radius, int color, float progress, int seed) {
        if (progress <= 0.0f || progress >= 1.0f) {
            return;
        }
        float fadeOut = 1.0f - progress;
        GuiGeo geo = GuiGeo.begin(g);
        for (int i = 0; i < 14; i++) {
            float ang = hash(seed + i * 5) * Mth.TWO_PI;
            float dist = radius * (0.75f + hash(seed * 3 + i) * 0.55f) * (0.7f + 0.3f * TransitionFx.easeOutCubic(progress));
            float size = 0.7f + hash(seed * 7 + i) * 1.3f;
            float x = cx + Mth.cos(ang) * dist;
            float y = cy + Mth.sin(ang) * dist * 0.6f;
            geo.rect(x, y, x + size, y + size, RailArt.a(color, 0.8f * fadeOut));
        }
        geo.end();
    }

    /** 检票打孔：深色孔 + 一圈压痕。 */
    static void punchHole(GuiGeo geo, float cx, float cy, float r, int hole, int rim) {
        geo.disc(cx, cy, r + 0.9f, rim);
        geo.disc(cx, cy, r, hole);
    }

    /** 条码：按种子生成粗细不一的竖条。 */
    static void barcode(GuiGeo geo, float x, float y, float w, float h, int seed, int color) {
        float cursor = x;
        int i = 0;
        while (cursor < x + w - 0.5f) {
            float bar = 0.6f + hash(seed * 31 + i) * 1.5f;
            if ((i & 1) == 0) {
                geo.rect(cursor, y, Math.min(x + w, cursor + bar), y + h, color);
            }
            cursor += bar + 0.5f;
            i++;
        }
    }

    // ==================== 文字 ====================

    /** 左对齐缩放文字。 */
    static void drawScaled(GuiGraphics g, Font font, Component text, float x, float y, float scale, int color) {
        if (((color >>> 24) & 0xFF) < 6) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 居中缩放文字。 */
    static void drawCentered(GuiGraphics g, Font font, Component text, float cx, float y, float scale, int color) {
        drawScaled(g, font, text, cx - font.width(text) * scale / 2.0f, y, scale, color);
    }

    /** 右对齐缩放文字。 */
    static void drawRight(GuiGraphics g, Font font, Component text, float right, float y, float scale, int color) {
        drawScaled(g, font, text, right - font.width(text) * scale, y, scale, color);
    }

    /** 左对齐的宽字距小字（英文副标签）。 */
    static void drawTrackedLeft(GuiGraphics g, Font font, String text, float x, float y, float scale, int color) {
        if (text == null || text.isBlank()) {
            return;
        }
        float w = TransitionFx.trackedWidth(font, text, 1.2f) * scale;
        TransitionFx.drawTracked(g, font, text, x + w / 2.0f, y, scale, 1.2f, color, 1.0f, 0.0f);
    }

    /** 宽字距小字的宽度。 */
    static float trackedWidth(Font font, String text, float scale) {
        return TransitionFx.trackedWidth(font, text, 1.2f) * scale;
    }

    /** 副标签（另一种语言）按内容取整后的宽度；desired 为期望缩放。 */
    static float subWidth(Font font, String text, float desired) {
        if (text == null || text.isBlank()) {
            return 0.0f;
        }
        return trackedWidth(font, text, crispFor(text, desired));
    }

    /** 副标签与主文字底边对齐，画在 x 处；纯英文时更小，含中文时保证可读。 */
    static void drawSub(GuiGraphics g, Font font, String text, float x, float mainY, float mainScale,
                        float desired, int color) {
        if (text == null || text.isBlank()) {
            return;
        }
        float scale = crispFor(text, desired);
        drawTrackedLeft(g, font, text, x, mainY + 8.0f * mainScale - 8.0f * scale, scale, color);
    }

    /** 按宽度截断并补省略号。 */
    static Component fit(Font font, Component text, float maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String plain = font.plainSubstrByWidth(text.getString(), Math.max(0, Math.round(maxWidth) - font.width("…")));
        return Component.literal(plain + "…").withStyle(text.getStyle());
    }

    // ==================== 工具 ====================

    /**
     * 把文字缩放取整到「每个字形像素占整数个屏幕像素」，避免非整数缩放下像素字发虚。
     */
    static float crisp(float scale) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        if (guiScale <= 0.0) {
            return scale;
        }
        // 中文走 unifont 的 16px 字形（显示为 8 GUI 像素），每个字形像素至少要占一个屏幕像素才看得清
        double minimum = Math.min(1.0, 2.0 / guiScale);
        return (float) Math.max(minimum, Math.max(1L, Math.round(scale * guiScale)) / guiScale);
    }

    /** 纯 ASCII（英文副标签、数字）可以缩得更小：只需每个字形像素占整数个屏幕像素。 */
    static float crispAscii(float scale) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        if (guiScale <= 0.0) {
            return scale;
        }
        return (float) (Math.max(1L, Math.round(scale * guiScale)) / guiScale);
    }

    /** 按文字内容选缩放：含中日韩字符时保证可读，纯 ASCII 时允许更小。 */
    static float crispFor(String text, float scale) {
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 0x7F) {
                    return crisp(scale);
                }
            }
        }
        return crispAscii(scale);
    }

    /** 把坐标对齐到屏幕像素网格（静止的大票用，保证文字清晰）。 */
    static float snap(float value) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        if (guiScale <= 0.0) {
            return value;
        }
        return (float) (Math.round(value * guiScale) / guiScale);
    }

    /** 在颜色原有 alpha 上再乘 factor。 */
    static int fade(int color, float factor) {
        return TransitionFx.fade(color, factor);
    }

    static float hash(int n) {
        int x = n * 0x27D4EB2D;
        x ^= x >>> 15;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        return (x & 0xFFFFFF) / (float) 0xFFFFFF;
    }
}
