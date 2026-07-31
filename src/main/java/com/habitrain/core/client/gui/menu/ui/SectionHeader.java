package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 节标题 + 分隔线，返回下一行 y。 */
public final class SectionHeader {
    private SectionHeader() {}

    public static int render(GuiGraphics g, Font font, int x, int y, int w, String title, int accent) {
        g.fill(x - 2, y - 2, x + w + 2, y - 1, MenuTheme.SEPARATOR);
        y += 4;
        g.drawString(font, Component.literal(title), x, y, accent, false);
        return y + 16;
    }
}
