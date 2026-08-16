package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** 细轨式数值滑块；拖动语义保持不变。 */
public class SliderRow {
    private static final int SLIDER_H = 12;
    private final float min, max, step;
    private boolean dragging;
    private int x, y, w;

    public SliderRow(float min, float max, float step) { this.min = min; this.max = max; this.step = step; }

    /** 渲染轨道/渐变/刻度/拇指/数值；返回当前值（拖动期间可能变化）。 */
    public float render(GuiGraphics g, Font font, int x, int y, int w, float value) {
        this.x = x; this.y = y; this.w = w;
        int trackTop = y + (SLIDER_H - 4) / 2;
        int trackBot = trackTop + 4;
        g.fill(x, trackTop, x + w, trackBot, MenuTheme.BORDER);
        int tx = thumbX(value);
        float pct = (value - min) / (max - min);
        if (pct > 0.001f) {
            g.fill(x, trackTop, tx, trackBot, MenuTheme.ACCENT_MINT);
        }
        int tc = dragging ? 0xFFFFFFFF : MenuTheme.TEXT_PRIMARY;
        g.fill(tx - 3, y + 1, tx + 4, y + SLIDER_H - 1, tc);
        g.fill(tx - 1, y + 3, tx + 2, y + SLIDER_H - 3, MenuTheme.BG_DARK);
        for (int p = 10; p <= 80; p += 10) {
            float pf = (p / 100f - min) / (max - min);
            int px = x + (int) (pf * w);
            g.fill(px, trackBot + 2, px + 1, trackBot + 2 + (p == 50 ? 6 : 3),
                    p == 50 ? MenuTheme.ACCENT_AMBER : MenuTheme.TEXT_DIM);
        }
        g.drawString(font, String.format("%d%%", Math.round(value * 100)), x + w + 8, y + 1,
                MenuTheme.ACCENT_AMBER, false);
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
