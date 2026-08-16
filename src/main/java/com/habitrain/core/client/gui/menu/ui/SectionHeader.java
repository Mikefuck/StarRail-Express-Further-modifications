package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 带信号色短线的节标题，返回下一行 y。 */
public final class SectionHeader {
    private SectionHeader() {}

    public static int render(GuiGraphics g, Font font, int x, int y, int w, String title, int accent) {
        g.fill(x, y + 4, x + 14, y + 6, accent);
        g.drawString(font, Component.literal(title), x + 20, y, MenuTheme.TEXT_PRIMARY, false);
        int lineX = x + 26 + font.width(title);
        if (lineX < x + w) g.fill(lineX, y + 4, x + w, y + 5, MenuTheme.BORDER);
        return y + 17;
    }
}
