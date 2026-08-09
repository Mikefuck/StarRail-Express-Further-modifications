package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 控制台式开关：左侧状态轨道 + 右侧短标签。 */
public final class PillToggle {
    private PillToggle() {}

    public static void render(GuiGraphics g, Font font, int x, int y, int w, int h, boolean on, String onText, String offText) {
        int trackW = Math.min(30, Math.max(24, w / 4));
        int trackH = Math.min(12, h - 4);
        int trackY = y + (h - trackH) / 2;
        int color = on ? MenuTheme.ACCENT_MINT : MenuTheme.DANGER;
        g.fill(x, trackY, x + trackW, trackY + trackH,
                on ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        MenuTheme.outline(g, x, trackY, trackW, trackH, MenuTheme.withAlpha(color, 0x90));
        int knob = trackH - 4;
        int knobX = on ? x + trackW - knob - 2 : x + 2;
        g.fill(knobX, trackY + 2, knobX + knob, trackY + trackH - 2, color);
        String text = on ? onText : offText;
        int textX = x + trackW + 6;
        g.drawString(font, text, textX, y + (h - font.lineHeight) / 2,
                on ? MenuTheme.TEXT_PRIMARY : MenuTheme.TEXT_SECONDARY, false);
    }
    public static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return MenuTheme.inBounds(mx, my, x, y, w, h);
    }
}
