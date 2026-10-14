package com.habitrain.core.client.gui;

import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 「邮票」视觉组件：背包车票页把菜单项做成一枚枚齿孔邮票。
 *
 * <p>以当前 pose 原点为邮票左上角、使用邮票局部坐标绘制。邮票由米色齿孔纸边、带凹版细线的色块、
 * 椭圆画框里的线框 3D 图标、标签条和面值组成；另有邮戳与「作废」加盖。</p>
 */
final class StampArt {
    private StampArt() {
    }

    static final int PAPER_TOP = 0xFFF6EEDB;
    static final int PAPER_BOTTOM = 0xFFE3D4B4;
    static final int PAPER_EDGE = 0x66755A30;
    static final int INK_LIGHT = 0xFFF8EEDA;
    /** 邮戳用浅色油墨：深色邮票色块上也看得清。 */
    static final int POSTMARK = 0xFFF1E7CF;

    /** 邮票色块：上下渐变两色。 */
    record Ink(int top, int bottom) {
    }

    static final Ink BURGUNDY = new Ink(0xFF83283C, 0xFF4A1322);
    static final Ink SAPPHIRE = new Ink(0xFF2F5394, 0xFF16294F);
    static final Ink TEAL = new Ink(0xFF237473, 0xFF0F3D3E);
    static final Ink OCHRE = new Ink(0xFFA3712A, 0xFF5E3F10);
    static final Ink PLUM = new Ink(0xFF74408A, 0xFF3D1F48);
    static final Ink CHESTNUT = new Ink(0xFF86522F, 0xFF472812);
    static final Ink SLATE = new Ink(0xFF465672, 0xFF222A38);
    static final Ink VERMILION = new Ink(0xFFB0412F, 0xFF651D14);
    static final Ink EMERALD = new Ink(0xFF2A7650, 0xFF113D29);
    static final Ink INDIGO = new Ink(0xFF424A98, 0xFF1E2152);
    static final Ink VOID = new Ink(0xFF56565E, 0xFF2C2C33);
    static final Ink[] ROTATION = {BURGUNDY, SAPPHIRE, TEAL, OCHRE, PLUM, CHESTNUT, SLATE, VERMILION, EMERALD, INDIGO};

    private static final Wire3D.View ICON_VIEW = new Wire3D.View();
    private static final Wire3D.Style ICON_STYLE = new Wire3D.Style(RailArt.BRASS_LIGHT, 0.55f, 1.8f, 0.16f, 0.45f);
    private static final Wire3D.Style ICON_STYLE_VOID = new Wire3D.Style(0xFFB8B8C0, 0.5f, 1.4f, 0.08f, 0.45f);
    private static final Wire3D.Style ICON_STYLE_LIGHT = new Wire3D.Style(INK_LIGHT, 0.55f, 0, 0, 0.35f);
    private static final Component HABI = Component.literal("HABI");

    /** 一枚邮票需要的全部内容；标签已按宽度折好行并选好清晰的缩放。 */
    record Face(List<FormattedCharSequence> label, float labelScale, WireModels.Icon icon, Ink ink, int number,
                boolean locked, boolean restricted, Component numberText) {
        Face(List<FormattedCharSequence> label, float labelScale, WireModels.Icon icon, Ink ink, int number,
             boolean locked, boolean restricted) {
            this(label, labelScale, icon, ink, number, locked, restricted,
                    Component.literal(number < 10 ? "0" + number : Integer.toString(number)));
        }

        Face withLocked(boolean value) {
            return new Face(label, labelScale, icon, ink, number, value, restricted, numberText);
        }
    }

    /** Owned by one menu entry; released when leaving a page or removing the screen. */
    static final class Cache implements AutoCloseable {
        final GuiGeo.CachedMesh paper = new GuiGeo.CachedMesh();
        final GuiGeo.CachedMesh icon = new GuiGeo.CachedMesh();
        private Face face;
        private float width, height;
        private boolean labelBottom, landscape, lightweight;

        void prepare(Face face, float width, float height, boolean labelBottom, boolean landscape, boolean lightweight) {
            if (this.face != face || this.width != width || this.height != height || this.labelBottom != labelBottom
                    || this.landscape != landscape || this.lightweight != lightweight) {
                close();
                this.face = face;
                this.width = width;
                this.height = height;
                this.labelBottom = labelBottom;
                this.landscape = landscape;
                this.lightweight = lightweight;
            }
        }

        @Override
        public void close() {
            paper.close();
            icon.close();
            face = null;
        }
    }

    /**
     * 画一枚邮票。
     *
     * @param labelBottom 标签条在下方（插在票下沿后的那排）；否则在上方
     * @param iconYaw     图标偏航角（悬停时转动）
     * @param glow        悬停高亮 0..1
     * @param alpha       整体不透明度
     * @param k           pose 上已有的缩放（用于把文字缩放取整到屏幕像素）
     */
    static void draw(GuiGraphics g, Font font, Face face, float w, float h, boolean labelBottom, float iconYaw,
                     float glow, float alpha, int seed, float k, Cache cache, boolean lightweight) {
        if (alpha <= 0.01f) {
            return;
        }
        float tooth = 1.35f;
        float panelInset = 4.2f;
        Ink ink = face.locked ? VOID : face.ink;
        float px0 = panelInset;
        float py0 = panelInset;
        float px1 = w - panelInset;
        float py1 = h - panelInset;
        float bandH = bandHeight(face);
        float bandTop = labelBottom ? py1 - bandH : py0;
        float iconCx = w / 2.0f;
        float iconCy = labelBottom ? (py0 + bandTop) / 2.0f + 0.5f : (bandTop + bandH + py1) / 2.0f - 0.5f;
        float ovalRx = (px1 - px0) * 0.36f;
        float ovalRy = Math.min(ovalRx * 1.12f, ((labelBottom ? bandTop - py0 : py1 - bandTop - bandH) - 4.0f) * 0.5f);
        cache.prepare(face, w, h, labelBottom, false, lightweight);
        float opacity = alpha;
        if (!cache.paper.ready()) {
            alpha = 1.0f;
            GuiGeo geo = GuiGeo.record();
            // 落在票面/背景上的投影
            geo.convexV(RailArt.chamfer(1.2f, 1.8f, w, h, 1.5f), 0, h, RailArt.a(0xFF000000, 0.3f * alpha),
                    RailArt.a(0xFF000000, 0.42f * alpha));
            perforated(geo, w, h, tooth, PAPER_TOP, PAPER_BOTTOM, lightweight);
            // 色块 + 凹版细线
            geo.rectV(px0, py0, px1, py1, TicketArt.fade(ink.top, alpha), TicketArt.fade(ink.bottom, alpha));
            for (float y = py0 + 1.2f; !lightweight && y < py1; y += 1.6f) {
                geo.rect(px0, y, px1, y + 0.35f, RailArt.a(0xFFFFFFFF, 0.06f * alpha));
            }
            // 标签条
            geo.rect(px0, bandTop, px1, bandTop + bandH, RailArt.a(0xFF000000, 0.32f * alpha));
            // 画框：内细线 + 椭圆
            int frame = TicketArt.fade(INK_LIGHT, alpha * 0.75f);
            geo.outline(RailArt.chamfer(px0 + 1.3f, py0 + 1.3f, px1 - px0 - 2.6f, py1 - py0 - 2.6f, 1.0f), 0.45f, frame);
            geo.disc(iconCx, iconCy, ovalRx, ovalRy, RailArt.a(0xFF000000, 0.22f * alpha), RailArt.a(0xFF000000, 0.05f * alpha));
            geo.ring(iconCx, iconCy, ovalRx - 0.25f, ovalRx + 0.25f, ovalRy / ovalRx, frame, frame);
            // 四角小菱形
            for (int i = 0; i < 4; i++) {
                float cx = (i & 1) == 0 ? px0 + 3.3f : px1 - 3.3f;
                float cy = (i & 2) == 0 ? (labelBottom ? py0 + 3.3f : bandTop + bandH + 3.3f)
                        : (labelBottom ? bandTop - 3.3f : py1 - 3.3f);
                geo.diamond(cx, cy, 1.1f, 1.1f, frame);
            }
            cache.paper.upload(geo);
        }
        alpha = opacity;
        cache.paper.draw(g, alpha, false);

        // 线框图标
        if (lightweight) {
            drawLightSelection(g, w, h, glow, alpha);
            drawStaticIcon(g, face, cache, iconCx, iconCy, Math.min(ovalRx, ovalRy) * 0.78f, alpha, seed);
        } else {
            GuiGeo glowGeo = GuiGeo.glow(g);
            if (glow > 0.01f && !face.locked) {
                glowGeo.softGlow(iconCx, iconCy, ovalRx * 1.4f, ovalRy * 1.4f, RailArt.BRASS_LIGHT, Math.round(70 * glow * alpha));
            }
            float iconR = Math.min(ovalRx, ovalRy) * 0.78f;
            ICON_VIEW.orient(iconYaw, -0.42f, 0.0f).origin(0, 0, 0).at(iconCx, iconCy, iconR, 6.0f, 1.2f);
            Wire3D.draw(glowGeo, face.icon.mesh(), ICON_VIEW, face.locked ? ICON_STYLE_VOID : ICON_STYLE,
                    alpha * (face.locked ? 0.55f : 0.85f + 0.15f * glow), 1.0f, seed);
            glowGeo.end();
        }

        // 文字：标签 + 面值 + 微缩字
        int labelColor = TicketArt.fade(face.locked ? 0xFFC8C8CC : INK_LIGHT, alpha);
        float lineH = 8.0f * face.labelScale + 1.0f;
        float textTop = bandTop + (bandH - lineH * face.label.size() + 1.0f) / 2.0f;
        for (FormattedCharSequence line : face.label) {
            float lw = font.width(line) * face.labelScale;
            g.pose().pushPose();
            g.pose().translate(w / 2.0f - lw / 2.0f, textTop, 0.0f);
            g.pose().scale(face.labelScale, face.labelScale, 1.0f);
            g.drawString(font, line, 0, 0, labelColor, false);
            g.pose().popPose();
            textTop += lineH;
        }
        float tiny = crispDu("00", 0.5f, k);
        float numberY = labelBottom ? py0 + 1.9f : py1 - 1.9f - 8.0f * tiny;
        TicketArt.drawScaled(g, font, face.numberText, px0 + 2.0f, numberY, tiny,
                TicketArt.fade(INK_LIGHT, alpha * 0.9f));
        TicketArt.drawRight(g, font, HABI, px1 - 2.0f, numberY, tiny,
                TicketArt.fade(INK_LIGHT, alpha * 0.55f));

        if (face.restricted) {
            opBadge(g, font, w - 2.6f, labelBottom ? h - 2.6f : 2.6f, alpha, k);
        }
        if (face.locked) {
            voidOverprint(g, font, w, h, alpha, k);
        }
    }

    /**
     * 横版邮票（对局中右下角邮票册用）：左侧椭圆画框放图标，右侧单行标签。
     * 参数含义同 {@link #draw}；标签须已按横版可用宽度折好行。
     */
    static void drawLandscape(GuiGraphics g, Font font, Face face, float w, float h, float iconYaw, float glow,
                              float alpha, int seed, float k, Cache cache, boolean lightweight) {
        if (alpha <= 0.01f) {
            return;
        }
        float tooth = 1.2f;
        float inset = 3.4f;
        Ink ink = face.locked ? VOID : face.ink;
        float px0 = inset;
        float py0 = inset;
        float px1 = w - inset;
        float py1 = h - inset;
        float iconCx = px0 + (py1 - py0) / 2.0f + 1.0f;
        float iconCy = h / 2.0f;
        float ovalR = (py1 - py0) / 2.0f - 1.6f;
        float textLeft = iconCx + ovalR + 3.0f;

        cache.prepare(face, w, h, false, true, lightweight);
        float opacity = alpha;
        if (!cache.paper.ready()) {
            alpha = 1.0f;
            GuiGeo geo = GuiGeo.record();
            geo.convexV(RailArt.chamfer(1.2f, 1.6f, w, h, 1.5f), 0, h, RailArt.a(0xFF000000, 0.3f * alpha),
                    RailArt.a(0xFF000000, 0.42f * alpha));
            perforated(geo, w, h, tooth, PAPER_TOP, PAPER_BOTTOM, lightweight);
            geo.rectV(px0, py0, px1, py1, TicketArt.fade(ink.top, alpha), TicketArt.fade(ink.bottom, alpha));
            for (float y = py0 + 1.2f; !lightweight && y < py1; y += 1.6f) {
                geo.rect(px0, y, px1, y + 0.35f, RailArt.a(0xFFFFFFFF, 0.06f * alpha));
            }
            geo.rect(textLeft - 1.5f, py0, px1, py1, RailArt.a(0xFF000000, 0.26f * alpha));
            int frame = TicketArt.fade(INK_LIGHT, alpha * 0.75f);
            geo.outline(RailArt.chamfer(px0 + 1.2f, py0 + 1.2f, px1 - px0 - 2.4f, py1 - py0 - 2.4f, 1.0f), 0.45f, frame);
            geo.disc(iconCx, iconCy, ovalR, ovalR, RailArt.a(0xFF000000, 0.22f * alpha), RailArt.a(0xFF000000, 0.05f * alpha));
            geo.ring(iconCx, iconCy, ovalR - 0.25f, ovalR + 0.25f, frame);
            cache.paper.upload(geo);
        }
        alpha = opacity;
        cache.paper.draw(g, alpha, false);

        if (lightweight) {
            drawLightSelection(g, w, h, glow, alpha);
            drawStaticIcon(g, face, cache, iconCx, iconCy, ovalR * 0.74f, alpha, seed);
        } else {
            GuiGeo glowGeo = GuiGeo.glow(g);
            if (glow > 0.01f && !face.locked) {
                glowGeo.softGlow(iconCx, iconCy, ovalR * 1.5f, ovalR * 1.5f, RailArt.BRASS_LIGHT, Math.round(70 * glow * alpha));
            }
            ICON_VIEW.orient(iconYaw, -0.42f, 0.0f).origin(0, 0, 0).at(iconCx, iconCy, ovalR * 0.74f, 6.0f, 1.2f);
            Wire3D.draw(glowGeo, face.icon.mesh(), ICON_VIEW, face.locked ? ICON_STYLE_VOID : ICON_STYLE,
                    alpha * (face.locked ? 0.55f : 0.85f + 0.15f * glow), 1.0f, seed);
            glowGeo.end();
        }

        int labelColor = TicketArt.fade(face.locked ? 0xFFC8C8CC : INK_LIGHT, alpha);
        float lineH = 8.0f * face.labelScale + 1.0f;
        float textTop = (h - lineH * face.label.size() + 1.0f) / 2.0f;
        for (FormattedCharSequence line : face.label) {
            g.pose().pushPose();
            g.pose().translate(textLeft + 1.0f, textTop, 0.0f);
            g.pose().scale(face.labelScale, face.labelScale, 1.0f);
            g.drawString(font, line, 0, 0, labelColor, false);
            g.pose().popPose();
            textTop += lineH;
        }
        if (face.restricted) {
            opBadge(g, font, w - 2.4f, 2.4f, alpha, k);
        }
        if (face.locked) {
            voidOverprint(g, font, w, h, alpha, k);
        }
    }

    private static void drawStaticIcon(GuiGraphics g, Face face, Cache cache, float x, float y, float radius,
                                       float alpha, int seed) {
        if (!cache.icon.ready()) {
            GuiGeo geo = GuiGeo.record();
            ICON_VIEW.orient(0.45f, -0.42f, 0).origin(0, 0, 0).at(x, y, radius, 6, 1.2f);
            Wire3D.draw(geo, face.icon.mesh(), ICON_VIEW, ICON_STYLE_LIGHT, face.locked ? 0.55f : 0.85f, 1, seed);
            cache.icon.upload(geo);
        }
        cache.icon.draw(g, alpha, false);
    }

    private static void drawLightSelection(GuiGraphics g, float w, float h, float hover, float alpha) {
        if (hover <= 0.01f) return;
        GuiGeo outline = GuiGeo.begin(g);
        outline.rectOutline(1, 1, w - 1, h - 1, 1.2f, RailArt.a(RailArt.BRASS_LIGHT, hover * alpha));
        outline.end();
    }

    /** 横版邮票标签的可用宽度。 */
    static float landscapeLabelWidth(float w, float h) {
        float inset = 3.4f;
        float ovalR = (h - inset * 2.0f) / 2.0f - 1.6f;
        float textLeft = inset + (h - inset * 2.0f) / 2.0f + 1.0f + ovalR + 3.0f;
        return w - inset - textLeft - 3.0f;
    }

    /** 在已缩放 k 的 pose 下，按文字内容取整到屏幕像素的局部缩放。 */
    static float crispDu(String text, float desired, float k) {
        // A middle dot or accented Latin letter does not need the larger CJK glyph scale.
        boolean wideGlyphs = false;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) >= 0x2E80) {
                    wideGlyphs = true;
                    break;
                }
            }
        }
        return (wideGlyphs ? TicketArt.crisp(desired * k) : TicketArt.crispAscii(desired * k)) / k;
    }

    /** 标签条高度：按行数与缩放。 */
    static float bandHeight(Face face) {
        return Math.max(11.0f, (8.0f * face.labelScale + 1.0f) * face.label.size() + 3.0f);
    }

    /** 齿孔纸边：内缩矩形 + 沿四边排开的小圆齿（齿间即撕开处的孔）。 */
    private static void perforated(GuiGeo geo, float w, float h, float r, int top, int bottom, boolean lightweight) {
        if (lightweight) {
            geo.convexV(RailArt.chamfer(0, 0, w, h, 2), 0, h, top, bottom);
            return;
        }
        geo.rectV(r, r, w - r, h - r, top, bottom);
        float pitch = r * 2.7f;
        int nx = Math.max(2, Math.round((w - r * 2.0f) / pitch));
        int ny = Math.max(2, Math.round((h - r * 2.0f) / pitch));
        for (int i = 0; i <= nx; i++) {
            float x = Mth.lerp(i / (float) nx, r, w - r);
            geo.disc(x, r, r, r, top, top);
            geo.disc(x, h - r, r, r, bottom, bottom);
        }
        for (int i = 1; i < ny; i++) {
            float t = i / (float) ny;
            float y = Mth.lerp(t, r, h - r);
            int c = GuiGeo.lerpColor(top, bottom, y / h);
            geo.disc(r, y, r, r, c, c);
            geo.disc(w - r, y, r, r, c, c);
        }
    }

    /** 管理员菜单项：露在外侧的那个角上一枚红色小圆标「OP」，压在齿孔纸边上。 */
    private static void opBadge(GuiGraphics g, Font font, float cx, float cy, float alpha, float k) {
        GuiGeo geo = GuiGeo.begin(g);
        geo.disc(cx, cy, 5.0f, 5.0f, RailArt.a(0xFFE04A3C, alpha), RailArt.a(0xFFA82A22, alpha));
        geo.ring(cx, cy, 5.0f, 5.5f, RailArt.a(PAPER_TOP, alpha));
        geo.end();
        // 每个字形像素至少两个屏幕像素，否则 P 的圆肚会糊成 F
        float tiny = crispDu("OP", 0.67f, k);
        TicketArt.drawCentered(g, font, Component.literal("OP"), cx + 0.25f, cy - 3.5f * tiny, tiny,
                TicketArt.fade(PAPER_TOP, alpha));
    }

    /** 无权限：整枚邮票加盖两道红杠与「作废」字样。 */
    private static void voidOverprint(GuiGraphics g, Font font, float w, float h, float alpha, float k) {
        int red = RailArt.a(0xFFD8423A, 0.85f * alpha);
        g.pose().pushPose();
        g.pose().translate(w / 2.0f, h / 2.0f, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(-32.0f));
        float len = Mth.sqrt(w * w + h * h) * 0.5f;
        GuiGeo geo = GuiGeo.begin(g);
        geo.rect(-len, -6.5f, len, -5.3f, red);
        geo.rect(-len, 5.3f, len, 6.5f, red);
        geo.end();
        Component text = Component.translatable("inventory.habitrain_core.ticket.void");
        float scale = crispDu(text.getString(), 1.0f, k);
        TicketArt.drawCentered(g, font, text, 0.0f, -4.0f * scale, scale, red);
        g.pose().popPose();
    }

    /**
     * 邮戳：双圈日戳 + 中间日期 + 向右拖出的波浪注销线。以 (cx, cy) 为日戳圆心。
     *
     * @param slam 盖下动画 0→1（从放大落到原尺寸）；≥1 为静止
     */
    static void postmark(GuiGraphics g, Font font, float cx, float cy, float r, String date, float rotDeg,
                         float alpha, float slam, int seed, float k) {
        if (alpha <= 0.02f) {
            return;
        }
        float impact = slam >= 1.0f ? 1.0f : 1.0f + (1.0f - TransitionFx.easeOutCubic(slam)) * 0.7f;
        float a = alpha * (slam >= 1.0f ? 1.0f : TransitionFx.clamp01(slam * 3.0f));
        int ink = RailArt.a(POSTMARK, 0.78f * a);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rotDeg));
        g.pose().scale(impact, impact, 1.0f);
        GuiGeo geo = GuiGeo.begin(g);
        geo.ring(0, 0, r - 0.9f, r, ink);
        geo.ring(0, 0, r * 0.62f - 0.5f, r * 0.62f, ink);
        for (int i = 0; i < 4; i++) {
            float y = (i - 1.5f) * r * 0.36f;
            float x0 = r + 1.0f;
            float prevX = x0;
            float prevY = y;
            for (float x = x0 + 1.5f; x <= x0 + r * 1.9f; x += 1.5f) {
                float yy = y + Mth.sin(x * 0.55f + i * 0.9f) * 1.0f;
                geo.line(prevX, prevY, x, yy, 0.7f, ink);
                prevX = x;
                prevY = yy;
            }
        }
        // 缺墨：随机点几粒白
        for (int i = 0; i < 16; i++) {
            float ang = TicketArt.hash(seed * 7 + i) * Mth.TWO_PI;
            float d = TicketArt.hash(seed * 13 + i * 5) * r;
            float size = 0.4f + TicketArt.hash(seed + i * 3) * 0.6f;
            geo.rect(Mth.cos(ang) * d, Mth.sin(ang) * d, Mth.cos(ang) * d + size, Mth.sin(ang) * d + size,
                    RailArt.a(0xFF141018, 0.3f * a));
        }
        geo.end();
        float tiny = crispDu(date, 0.5f, k);
        TicketArt.drawCentered(g, font, Component.literal(date), 0.0f, -4.0f * tiny, tiny, ink);
        TicketArt.drawCentered(g, font, Component.literal("HABI"), 0.0f, -r * 0.62f - 4.0f * tiny - 1.2f, tiny, ink);
        g.pose().popPose();
    }
}
