package com.habitrain.core.client.gui.menu.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** DLC 概率滑块：拖动即改值，页面写入配置。常量与旧 GlobalTabScreen 一致。 */
public class SliderRow {
    private static final int SLIDER_H = 12;
    private final float min, max, step;
    private boolean dragging;
    private int x, y, w;

    public SliderRow(float min, float max, float step) { this.min = min; this.max = max; this.step = step; }

    /** 渲染轨道/渐变/刻度/拇指/数值；返回当前值（拖动期间可能变化）。 */
    public float render(GuiGraphics g, Font font, int x, int y, int w, float value) {
        this.x = x; this.y = y; this.w = w;
        int trackTop = y + (SLIDER_H - 6) / 2;
        int trackBot = trackTop + 6;
        g.fill(x, trackTop, x + w, trackBot, 0x44FFFFFF);
        int tx = thumbX(value);
        float pct = (value - min) / (max - min);
        if (pct > 0.001f) {
            int color = pct < 0.25f ? 0xAAFF5555 : pct < 0.5f ? 0xAAFFAA00 : pct < 0.75f ? 0xAA55FF55 : 0xAA55AAFF;
            g.fill(x, trackTop, tx, trackBot, color);
        }
        int tc = dragging ? 0xFFFFFFFF : 0xCCFFFFFF;
        g.fill(tx - 5, y, tx + 5, y + SLIDER_H, tc);
        g.fill(tx - 2, y + 4, tx + 2, y + SLIDER_H - 4, 0xFF333333);
        for (int p = 10; p <= 80; p += 10) {
            float pf = (p / 100f - min) / (max - min);
            int px = x + (int) (pf * w);
            g.fill(px, trackBot + 2, px + 1, trackBot + 2 + (p == 50 ? 8 : 4), p == 50 ? 0x88FFFF00 : 0x44FFFFFF);
        }
        g.drawString(font, String.format("§6§l%d%%", Math.round(value * 100)), x + w + 8, y + 1, 0xFFFFFFFF, false);
        return value;
    }

    public boolean mouseClicked(double mx, double my) {
        if (mx < x - 4 || mx > x + w + 4 || my < y - 4 || my > y + SLIDER_H + 4) return false;
        dragging = true;
        return true;
    }
    public boolean mouseDragged() { return dragging; }
    public boolean mouseReleased() { if (!dragging) return false; dragging = false; return true; }

    public float valueFromMouse(double mx) {
        float rel = Mth.clamp((float) ((mx - x) / w), 0f, 1f);
        float raw = min + rel * (max - min);
        return Math.round(raw / step) * step;
    }
    private int thumbX(float value) {
        float pct = (value - min) / (max - min);
        return x + (int) (pct * w);
    }
}
