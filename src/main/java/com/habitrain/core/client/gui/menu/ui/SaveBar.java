package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

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
                       int accent, boolean pageValid, int mx, int my) {
        boolean saveEnabled = enabled && pageValid;
        int y = height - HEIGHT;
        g.fill(x, y, x + width, height, MenuTheme.BG_SIDEBAR);
        g.fill(x, y, x + width, y + 1, MenuTheme.BORDER);

        int btnX = x + width - BTN_W;
        int btnY = y + (HEIGHT - BTN_H) / 2;
        boolean hover = saveEnabled && MenuTheme.inBounds(mx, my, btnX, btnY, BTN_W, BTN_H);
        String buttonLabel = Component.translatable(!enabled
                ? "screen.habitrain_core.save_bar.read_only"
                : pageValid ? "screen.habitrain_core.save_bar.save"
                : "screen.habitrain_core.save_bar.fix_errors").getString();
        MenuTheme.button(g, font, buttonLabel,
                btnX, btnY, BTN_W, BTN_H, accent, saveEnabled, hover);

        String hint = Component.translatable(!enabled
                ? "screen.habitrain_core.save_bar.read_only_hint"
                : pageValid ? "screen.habitrain_core.save_bar.save_hint"
                : "screen.habitrain_core.save_bar.fix_errors_hint").getString();
        g.drawString(font, hint, x + 2, btnY + 7,
                saveEnabled ? MenuTheme.TEXT_SECONDARY : MenuTheme.DANGER, false);
    }

    public boolean mouseClicked(double mx, double my, int x, int width, int height,
                                boolean pageValid) {
        if (!enabled || !pageValid) return false;
        int btnX = x + width - BTN_W;
        int btnY = height - HEIGHT + (HEIGHT - BTN_H) / 2;
        return MenuTheme.inBounds(mx, my, btnX, btnY, BTN_W, BTN_H);
    }
}
