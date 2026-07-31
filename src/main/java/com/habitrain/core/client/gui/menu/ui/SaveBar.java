package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 固定底部保存栏。点击由 ConfigMenuScreen 分发到当前页 save()。 */
public class SaveBar {
    public static final int HEIGHT = 40;
    private static final int BTN_W = 90;
    private static final int BTN_X = 16;
    private final boolean enabled;

    public SaveBar(boolean enabled) { this.enabled = enabled; }

    public void render(GuiGraphics g, Font font, int width, int height, int mx, int my) {
        int y = height - HEIGHT;
        g.fill(0, y, width, height, 0xFF10141A);
        g.fill(0, y, width, y + 1, 0x30FFFFFF);
        int btnY = y + (HEIGHT - 20) / 2;
        boolean hover = enabled && MenuTheme.inBounds(mx, my, BTN_X, btnY, BTN_W, 20);
        g.fill(BTN_X, btnY, BTN_X + BTN_W, btnY + 20,
                enabled ? (hover ? 0xFF2A6B4A : 0xFF1B4A32) : 0xFF2A2A2A);
        g.drawString(font, "§a保存", BTN_X + (BTN_W - font.width("保存")) / 2, btnY + 6,
                enabled ? 0xFFFFFFFF : 0xFF666666, false);
        g.drawString(font, "§7修改即时生效；点击保存写入配置文件", BTN_X + BTN_W + 12, btnY + 6,
                MenuTheme.TEXT_SECONDARY, false);
        if (!enabled) {
            String ro = "§c只读模式：联机服务器中仅 OP 可修改";
            g.drawString(font, ro, width - font.width(ro) - 12, btnY + 6, 0xFF5555, false);
        }
    }

    public boolean mouseClicked(double mx, double my, int width, int height) {
        int y = height - HEIGHT;
        return enabled && MenuTheme.inBounds(mx, my, BTN_X, y + (HEIGHT - 20) / 2, BTN_W, 20);
    }
}
