package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 绿/红开关药丸：静态渲染 + 命中判断（页面自行收集命中矩形）。 */
public final class PillToggle {
    private PillToggle() {}

    public static void render(GuiGraphics g, Font font, int x, int y, int w, int h, boolean on, String onText, String offText) {
        g.fill(x, y, x + w, y + h, on ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        String text = on ? onText : offText;
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - font.lineHeight) / 2, 0xFFFFFFFF, false);
    }
    public static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return MenuTheme.inBounds(mx, my, x, y, w, h);
    }
}
