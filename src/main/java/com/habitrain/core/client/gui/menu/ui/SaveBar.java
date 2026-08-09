package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 固定在工作区底部的提交栏，不遮盖左侧导航。 */
public class SaveBar {
    public static final int HEIGHT = 42;
    private static final int BTN_W = 96;
    private static final int BTN_H = 22;
    private final boolean enabled;

    public SaveBar(boolean enabled) {
        this.enabled = enabled;
    }

    public void render(GuiGraphics g, Font font, int x, int width, int height,
                       int accent, int mx, int my) {
        int y = height - HEIGHT;
        g.fill(x, y, x + width, height, MenuTheme.BG_SIDEBAR);
        g.fill(x, y, x + width, y + 1, MenuTheme.BORDER);

        int btnX = x + width - BTN_W;
        int btnY = y + (HEIGHT - BTN_H) / 2;
        boolean hover = enabled && MenuTheme.inBounds(mx, my, btnX, btnY, BTN_W, BTN_H);
        MenuTheme.button(g, font, enabled ? "保存更改" : "只读模式",
                btnX, btnY, BTN_W, BTN_H, accent, enabled, hover);

        g.drawString(font, enabled ? "修改即时生效 · 保存后写入配置文件" : "联机服务器中仅 OP 可以修改",
                x + 2, btnY + 7, enabled ? MenuTheme.TEXT_SECONDARY : MenuTheme.DANGER, false);
    }

    public boolean mouseClicked(double mx, double my, int x, int width, int height) {
        int btnX = x + width - BTN_W;
        int btnY = height - HEIGHT + (HEIGHT - BTN_H) / 2;
        return MenuTheme.inBounds(mx, my, btnX, btnY, BTN_W, BTN_H);
    }
}
