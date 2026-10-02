package com.habitrain.core.client.gui;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 「星夜快车」视觉语言：投票、开局、结算三处共用的配色与列车主题美术组件。
 *
 * <p>风格取 Art Deco 豪华列车：午夜蓝底、黄铜描金、香槟色文字、车窗暖灯。
 * 所有组件都是无状态的纯绘制函数（翻牌器除外），动画量由调用方传入的时间/进度驱动。
 * 车票本身见 {@link TicketArt}。</p>
 */
final class RailArt {
    // ---- 午夜蓝 ----
    static final int NIGHT_0 = 0xFF04060D;
    static final int NIGHT_1 = 0xFF0A1022;
    static final int NIGHT_2 = 0xFF111A33;
    static final int NIGHT_3 = 0xFF1A2748;
    static final int NIGHT_4 = 0xFF26375E;
    // ---- 黄铜 ----
    static final int BRASS_DIM = 0xFF8A6A35;
    static final int BRASS = 0xFFD0A658;
    static final int BRASS_LIGHT = 0xFFF4D894;
    static final int CHAMPAGNE = 0xFFFFF4DA;
    // ---- 点缀 ----
    static final int LAMP = 0xFFFFB55E;
    static final int RUBY = 0xFFFF5A64;
    // ---- 文字 ----
    static final int TEXT = 0xFFEEE8DA;
    static final int TEXT_MUTED = 0xFF9EA7BE;
    static final int TEXT_FAINT = 0xFF5E6884;

    private RailArt() {
    }

    static int a(int color, int alpha) {
        return TransitionFx.withAlpha(color, alpha);
    }

    static int a(int color, float alpha01) {
        return TransitionFx.withAlpha(color, Math.round(255.0f * Mth.clamp(alpha01, 0.0f, 1.0f)));
    }

    // ==================== 列车与蒸汽 ====================

    /**
     * 小型蒸汽列车剪影（车头朝右）：车头 + 两节亮灯客车 + 车头灯光束。
     *
     * @param noseX 车头最前端 x
     * @param railY 轨面 y
     * @param s     缩放（1 = 约 52×10 像素）
     * @param alpha 整体不透明度 0..1
     */
    static void drawTrain(GuiGraphics g, float noseX, float railY, float s, float time, float alpha, float wheelSpin) {
        if (alpha <= 0.01f) {
            return;
        }
        int body = a(0xFF0E1430, alpha);
        int bodyTop = a(0xFF223260, alpha);
        int trim = a(BRASS, alpha);
        int window = a(LAMP, alpha);
        GuiGeo geo = GuiGeo.begin(g);
        float y = railY;
        // 客车 ×2
        for (int car = 0; car < 2; car++) {
            float right = noseX - 21.0f * s - car * 16.0f * s;
            float left = right - 14.5f * s;
            geo.rectV(left, y - 8.0f * s, right, y - 1.6f * s, bodyTop, body);
            geo.rect(left - 0.5f * s, y - 8.6f * s, right + 0.5f * s, y - 7.8f * s, trim);
            for (int win = 0; win < 3; win++) {
                float wx = left + 1.6f * s + win * 4.4f * s;
                geo.rect(wx, y - 6.6f * s, wx + 2.8f * s, y - 4.4f * s, window);
            }
            geo.rect(left, y - 3.0f * s, right, y - 2.5f * s, a(BRASS_DIM, alpha));
            geo.rect(right, y - 4.0f * s, right + 1.5f * s, y - 3.2f * s, body);
            for (int wh = 0; wh < 2; wh++) {
                geo.disc(left + 3.0f * s + wh * 8.5f * s, y - 1.1f * s, 1.3f * s, a(0xFF05070F, alpha));
            }
        }
        // 车头：驾驶室 + 锅炉 + 烟囱 + 排障器
        float cabL = noseX - 19.5f * s;
        geo.rectV(cabL, y - 9.0f * s, cabL + 6.5f * s, y - 1.6f * s, bodyTop, body);
        geo.rect(cabL - 0.8f * s, y - 10.0f * s, cabL + 7.3f * s, y - 9.0f * s, trim);
        geo.rect(cabL + 1.5f * s, y - 7.6f * s, cabL + 4.8f * s, y - 5.4f * s, window);
        geo.rectV(cabL + 6.5f * s, y - 6.8f * s, noseX - 3.0f * s, y - 1.6f * s, bodyTop, body);
        geo.rect(cabL + 6.5f * s, y - 4.6f * s, noseX - 3.0f * s, y - 4.0f * s, trim);
        geo.rect(noseX - 7.0f * s, y - 9.6f * s, noseX - 5.0f * s, y - 6.8f * s, body);
        geo.rect(noseX - 7.4f * s, y - 10.2f * s, noseX - 4.6f * s, y - 9.6f * s, trim);
        geo.rect(noseX - 11.0f * s, y - 8.0f * s, noseX - 9.4f * s, y - 6.8f * s, trim);
        geo.tri(noseX - 3.0f * s, y - 4.2f * s, body, noseX, y, body, noseX - 3.0f * s, y, body);
        float spokeA = wheelSpin;
        for (int wh = 0; wh < 3; wh++) {
            float wx = cabL + 2.2f * s + wh * 5.2f * s;
            float r = wh == 0 ? 1.9f * s : 1.6f * s;
            geo.disc(wx, y - r, r, a(0xFF05070F, alpha));
            geo.line(wx - Mth.cos(spokeA) * r * 0.8f, y - r - Mth.sin(spokeA) * r * 0.8f,
                    wx + Mth.cos(spokeA) * r * 0.8f, y - r + Mth.sin(spokeA) * r * 0.8f, 0.6f, a(BRASS_DIM, alpha));
        }
        geo.line(cabL + 2.2f * s, y - 1.8f * s, cabL + 12.6f * s, y - 1.8f * s, 0.7f * s, a(BRASS_DIM, alpha));
        geo.end();

        GuiGeo beam = GuiGeo.glow(g);
        float hx = noseX - 2.6f * s;
        float hy = y - 5.4f * s;
        beam.quad(hx, hy - 0.8f * s, a(BRASS_LIGHT, 0.5f * alpha),
                hx + 30.0f * s, hy - 5.0f * s, a(BRASS_LIGHT, 0),
                hx + 30.0f * s, hy + 5.0f * s, a(BRASS_LIGHT, 0),
                hx, hy + 0.8f * s, a(BRASS_LIGHT, 0.5f * alpha));
        beam.softGlow(hx, hy, 4.5f * s, 4.5f * s, BRASS_LIGHT, Math.round(150 * alpha));
        beam.softGlow(noseX - 30.0f * s, y - 5.0f * s, 26.0f * s, 6.0f * s, LAMP, Math.round(40 * alpha));
        beam.end();
    }

    // ==================== 顶棚发车牌 ====================

    /** 发车牌底色（上→下渐变）。 */
    static final int PANEL_TOP = 0xFF111A36;
    static final int PANEL_BOTTOM = 0xFF080D1F;
    static final int STEEL = 0xFF5B6C96;

    /** 屏幕上缘一道很淡的压暗，让面板像嵌在车站顶棚上，同时保证亮天空下的可读性。 */
    static void drawCeilingShade(GuiGraphics g, float screenWidth, float panelTop, float panelHeight) {
        float reach = panelHeight + 46.0f;
        float visible = TransitionFx.clamp01((panelTop + panelHeight) / panelHeight);
        GuiGeo geo = GuiGeo.begin(g);
        geo.rectV(0, 0, screenWidth, reach, a(NIGHT_0, 0.42f * visible), a(NIGHT_0, 0));
        geo.end();
    }

    /** 挂在屏幕上缘的发车牌本体：切角深蓝底 + 下缘黄铜双线 + 两枚顶部吊扣 + 投影。 */
    static void drawHangingPanel(GuiGraphics g, float x, float w, float top, float h) {
        float bottom = top + h;
        if (bottom <= 0.0f) {
            return;
        }
        float c = 8.0f;
        float[] shape = {x, top - 2.0f, x + w, top - 2.0f, x + w, bottom - c, x + w - c, bottom, x + c, bottom, x, bottom - c};
        GuiGeo geo = GuiGeo.begin(g);
        // 投影
        geo.rectV(x + 4.0f, bottom, x + w - 4.0f, bottom + 9.0f, a(0xFF000000, 0.32f), a(0xFF000000, 0));
        geo.convexV(shape, top, bottom, a(PANEL_TOP, 0.95f), a(PANEL_BOTTOM, 0.97f));
        // 标题行底纹
        geo.rectV(x + 1.0f, top, x + w - 1.0f, top + 22.0f, a(0xFF1C2A55, 0.55f), a(0xFF1C2A55, 0));
        // 外框 + 内细线
        geo.line(x, top - 2.0f, x, bottom - c, 1.0f, a(BRASS_DIM, 0.9f));
        geo.line(x + w, top - 2.0f, x + w, bottom - c, 1.0f, a(BRASS_DIM, 0.9f));
        geo.line(x, bottom - c, x + c, bottom, 1.0f, a(BRASS, 0.95f));
        geo.line(x + w, bottom - c, x + w - c, bottom, 1.0f, a(BRASS, 0.95f));
        geo.line(x + c, bottom, x + w - c, bottom, 1.2f, a(BRASS, 0.95f));
        geo.line(x + c + 1.5f, bottom - 3.0f, x + w - c - 1.5f, bottom - 3.0f, 0.6f, a(BRASS_DIM, 0.7f));
        // 底边中央的装饰：阶梯短线 + 菱形
        float cx = x + w / 2.0f;
        geo.diamond(cx, bottom, 3.4f, 3.4f, a(BRASS_LIGHT, 1.0f));
        geo.diamondOutline(cx, bottom, 6.0f, 5.0f, 0.8f, a(BRASS, 0.9f));
        // 顶部吊扣
        for (int side = -1; side <= 1; side += 2) {
            float bx = side < 0 ? x + 14.0f : x + w - 14.0f;
            geo.rect(bx - 1.0f, top - 2.0f, bx + 1.0f, top + 2.0f, a(BRASS_DIM, 0.9f));
        }
        geo.end();
    }

    /**
     * 发车牌上的线路轨道：枕木 + 钢轨，驶过的路段被黄铜点亮，小火车驶向终点站信号灯。
     *
     * @param travelled 已驶过的比例 0..1
     * @param signal    终点站信号灯颜色
     * @param halo      信号灯光晕颜色
     */
    static void drawRouteRail(GuiGraphics g, float left, float right, float railY, float travelled,
                              int signal, int halo, float time) {
        float s = 0.5f;
        float trainLen = 52.0f * s;
        float stationX = right - 3.0f;
        float noseX = left + trainLen + Math.max(0.0f, stationX - 10.0f - left - trainLen)
                * Mth.clamp(travelled, 0.0f, 1.0f);

        GuiGeo geo = GuiGeo.begin(g);
        for (float sx = left; sx < right; sx += 5.0f) {
            geo.rect(sx, railY - 1.0f, sx + 1.5f, railY + 2.0f, a(NIGHT_4, 0.55f));
        }
        geo.rect(left, railY, right, railY + 1.0f, a(STEEL, 0.9f));
        geo.rectH(left, railY, noseX, railY + 1.0f, a(BRASS_DIM, 0.3f), a(BRASS, 1.0f));
        geo.rect(stationX - 8.0f, railY - 2.0f, stationX + 2.0f, railY, a(NIGHT_4, 1.0f));
        geo.rect(stationX - 8.0f, railY - 2.5f, stationX + 2.0f, railY - 2.0f, a(BRASS_DIM, 1.0f));
        geo.rect(stationX - 0.8f, railY - 10.0f, stationX + 0.4f, railY - 2.0f, a(BRASS_DIM, 1.0f));
        geo.diamond(stationX - 0.2f, railY - 11.2f, 2.0f, 2.0f, signal);
        geo.end();
        GuiGeo glow = GuiGeo.glow(g);
        glow.softGlow(stationX - 0.2f, railY - 11.2f, 8.0f, 8.0f, halo, 110);
        glow.end();
        drawTrain(g, noseX, railY, s, time, 1.0f, time * 6.0f);
    }

    // ==================== Art Deco 纹样 ====================

    /** 带翼车轮——铁路路徽。spin 驱动车轮辐条旋转，spread 控制翅膀展开（0..1）。 */
    static void drawWingedWheel(GuiGraphics g, float cx, float cy, float r, float spin, float spread, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        GuiGeo halo = GuiGeo.glow(g);
        halo.softGlow(cx, cy, r * 3.6f, r * 2.2f, BRASS, Math.round(60 * alpha));
        halo.end();

        GuiGeo geo = GuiGeo.begin(g);
        float open = TransitionFx.easeOutCubic(spread);
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 5; k++) {
                float rootX = cx + side * r * 0.82f;
                float rowY = cy - r * 0.62f + k * r * 0.3f;
                float len = r * (2.25f - k * 0.36f) * open;
                if (len <= 0.5f) {
                    continue;
                }
                float tipX = rootX + side * len;
                float tipLift = -r * (0.38f - k * 0.07f) * open;
                float rootH = r * 0.13f;
                int root = a(k == 0 ? BRASS_LIGHT : BRASS, alpha);
                int tip = a(k == 0 ? CHAMPAGNE : BRASS_LIGHT, alpha * 0.85f);
                geo.quad(rootX, rowY - rootH, root, tipX, rowY + tipLift - 0.6f, tip,
                        tipX - side * 1.4f, rowY + tipLift + 0.9f, tip, rootX, rowY + rootH, root);
            }
        }
        geo.disc(cx, cy, r, r, a(BRASS_LIGHT, alpha), a(BRASS, alpha));
        geo.disc(cx, cy, r * 0.86f, r * 0.86f, a(NIGHT_2, alpha), a(NIGHT_1, alpha));
        geo.ring(cx, cy, r * 0.62f, r * 0.72f, a(BRASS, alpha));
        for (int i = 0; i < 8; i++) {
            float ang = spin + i * Mth.TWO_PI / 8.0f;
            geo.line(cx + Mth.cos(ang) * r * 0.2f, cy + Mth.sin(ang) * r * 0.2f,
                    cx + Mth.cos(ang) * r * 0.66f, cy + Mth.sin(ang) * r * 0.66f,
                    Math.max(1.0f, r * 0.08f), a(BRASS_LIGHT, alpha));
        }
        geo.disc(cx, cy, r * 0.24f, r * 0.24f, a(CHAMPAGNE, alpha), a(BRASS, alpha));
        geo.disc(cx, cy, r * 0.09f, a(NIGHT_1, alpha));
        geo.end();
    }

    /** 切角矩形的 8 个顶点（顺时针）。 */
    static float[] chamfer(float x, float y, float w, float h, float c) {
        float k = Math.min(c, Math.min(w, h) * 0.5f);
        return new float[] {
                x + k, y, x + w - k, y, x + w, y + k, x + w, y + h - k,
                x + w - k, y + h, x + k, y + h, x, y + h - k, x, y + k
        };
    }

    /** 标题两侧的 Art Deco 饰翼：渐隐长线 + 三道阶梯短线 + 菱形端点。 */
    static void drawTitleWings(GuiGeo geo, float cx, float y, float gap, float length, int color, float alpha) {
        if (length < 2.0f || alpha <= 0.01f) {
            return;
        }
        for (int side = -1; side <= 1; side += 2) {
            float start = cx + side * gap;
            float end = start + side * length;
            geo.quad(start, y - 0.5f, a(color, alpha), end, y - 0.5f, a(color, 0),
                    end, y + 0.5f, a(color, 0), start, y + 0.5f, a(color, alpha));
            for (int k = 0; k < 3; k++) {
                float sx = start + side * (3.0f + k * 4.0f);
                float half = 3.5f - k;
                geo.line(sx, y - half, sx, y + half, 1.0f, a(color, alpha * (1.0f - k * 0.25f)));
            }
            geo.diamond(start, y, 2.2f, 2.2f, a(color, alpha));
        }
    }

    // ==================== 翻牌器 ====================

    /**
     * 车站翻牌显示器（Solari board）。每个字位在内容改变时独立翻动，
     * 相邻字位错峰 60ms，形成一串"哗啦"翻过的效果。
     */
    static final class FlapBoard {
        private static final long FLIP_MILLIS = 300L;
        private static final long STAGGER_MILLIS = 60L;

        private String shown = "";
        private String previous = "";
        private long changedAt;

        void set(String text) {
            String next = text == null ? "" : text;
            if (next.equals(shown)) {
                return;
            }
            long now = Util.getMillis();
            // 翻动中途再次变化：以当前显示为旧值重新开始
            previous = shown;
            shown = next;
            changedAt = now;
        }

        String text() {
            return shown;
        }

        float tileWidth(int tileW, int gap) {
            return shown.length() * tileW + Math.max(0, shown.length() - 1) * gap;
        }

        /**
         * 绘制。必须在未做 pose 平移/缩放的坐标系中调用（内部用 scissor 裁切上下半页）。
         */
        void render(GuiGraphics g, Font font, int x, int y, int tileW, int tileH, int gap,
                    int textColor, int tileTop, int tileBottom, float alpha) {
            if (alpha <= 0.02f || shown.isEmpty()) {
                return;
            }
            long now = Util.getMillis();
            int seam = tileH / 2;
            for (int i = 0; i < shown.length(); i++) {
                int tx = x + i * (tileW + gap);
                char cur = shown.charAt(i);
                char old = i < previous.length() ? previous.charAt(i) : ' ';
                float p = cur == old ? 1.0f
                        : TransitionFx.clamp01((now - changedAt - i * STAGGER_MILLIS) / (float) FLIP_MILLIS);

                GuiGeo tile = GuiGeo.begin(g);
                tile.rect(tx - 0.5f, y + 1.0f, tx + tileW + 0.5f, y + tileH + 1.5f, a(0xFF000000, alpha * 0.6f));
                tile.rectV(tx, y, tx + tileW, y + seam, a(tileTop, alpha), a(tileBottom, alpha));
                tile.rectV(tx, y + seam, tx + tileW, y + tileH, a(tileBottom, alpha), a(tileTop, alpha * 0.9f));
                tile.end();

                if (p >= 1.0f) {
                    drawGlyph(g, font, cur, tx, y, tileW, tileH, textColor, alpha, 0, tileH, 1.0f);
                } else {
                    // 背景：上半已是新字，下半仍是旧字
                    drawGlyph(g, font, cur, tx, y, tileW, tileH, textColor, alpha, 0, seam, 1.0f);
                    drawGlyph(g, font, old, tx, y, tileW, tileH, textColor, alpha, seam, tileH, 1.0f);
                    float k;
                    if (p < 0.5f) {
                        // 旧字上半页向下翻落
                        k = Mth.cos(p * Mth.PI);
                        int flapTop = y + seam - Math.max(0, Math.round(seam * k));
                        flapFace(g, tx, flapTop, tx + tileW, y + seam, tileTop, tileBottom, alpha, 1.0f - k);
                        drawGlyph(g, font, old, tx, y, tileW, tileH, textColor, alpha, flapTop - y, seam, k);
                    } else {
                        // 新字下半页落定
                        k = -Mth.cos(p * Mth.PI);
                        int flapBottom = y + seam + Math.max(0, Math.round((tileH - seam) * k));
                        flapFace(g, tx, y + seam, tx + tileW, flapBottom, tileBottom, tileTop, alpha, 1.0f - k);
                        drawGlyph(g, font, cur, tx, y, tileW, tileH, textColor, alpha, seam, flapBottom - y, k);
                    }
                }

                GuiGeo seamGeo = GuiGeo.begin(g);
                seamGeo.rect(tx, y + seam - 0.5f, tx + tileW, y + seam + 0.5f, a(0xFF000000, alpha * 0.85f));
                seamGeo.rect(tx - 1.0f, y + seam - 1.5f, tx, y + seam + 1.5f, a(BRASS_DIM, alpha));
                seamGeo.rect(tx + tileW, y + seam - 1.5f, tx + tileW + 1.0f, y + seam + 1.5f, a(BRASS_DIM, alpha));
                seamGeo.rectV(tx, y, tx + tileW, y + 1.0f, a(0xFFFFFFFF, alpha * 0.12f), a(0xFFFFFFFF, 0));
                seamGeo.end();
            }
        }

        private static void flapFace(GuiGraphics g, int x0, int y0, int x1, int y1, int c0, int c1,
                                     float alpha, float shade) {
            if (y1 <= y0) {
                return;
            }
            GuiGeo geo = GuiGeo.begin(g);
            geo.rectV(x0, y0, x1, y1, a(c0, alpha), a(c1, alpha));
            geo.rect(x0, y0, x1, y1, a(0xFF000000, alpha * shade * 0.5f));
            geo.end();
        }

        /** 在字位内绘制单个字符，只显示 [clipTop, clipBottom) 行；squash 为纵向压缩（以中缝为轴）。 */
        private static void drawGlyph(GuiGraphics g, Font font, char ch, int tx, int ty, int tileW, int tileH,
                                      int color, float alpha, int clipTop, int clipBottom, float squash) {
            if (clipBottom <= clipTop || ch == ' ' || squash <= 0.02f) {
                return;
            }
            int textAlpha = Math.round(255 * alpha);
            if (textAlpha < 8) {
                return;
            }
            String s = String.valueOf(ch);
            float scale = tileH / 11.0f;
            float seamY = ty + tileH / 2.0f;
            g.flush();
            g.enableScissor(tx, ty + clipTop, tx + tileW, ty + clipBottom);
            g.pose().pushPose();
            g.pose().translate(tx + tileW / 2.0f, seamY, 0.0f);
            g.pose().scale(scale, scale * squash, 1.0f);
            g.pose().translate(-font.width(s) / 2.0f + 0.5f, -3.5f, 0.0f);
            g.drawString(font, s, 0, 0, a(color, textAlpha), false);
            g.pose().popPose();
            g.flush();
            g.disableScissor();
        }
    }
}
