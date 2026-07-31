package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 二级子 Tab 条：等宽绘制，选中用 accent 底色；render 返回悬停下标或 -1。 */
public class SubTabBar {
    private final String[] labels;
    private final int[] accents;
    public static final int H = 24;

    public SubTabBar(String[] labels, int[] accents) { this.labels = labels; this.accents = accents; }

    public int getHeight() { return H; }
    public int count() { return labels.length; }

    /** 返回悬停下标（-1 表示无），调用方据此处理点击切换。 */
    public int render(GuiGraphics g, Font font, int x, int y, int w, int selected, int mx, int my) {
        int tabW = Math.max(60, (w - (labels.length - 1) * 2) / labels.length);
        int hit = -1;
        for (int i = 0; i < labels.length; i++) {
            int tx = x + i * (tabW + 2);
            boolean sel = i == selected;
            boolean hover = MenuTheme.inBounds(mx, my, tx, y, tabW, H);
            if (hover) hit = i;
            int bg = sel ? accents[i] : (hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
            g.fill(tx, y, tx + tabW, y + H, bg);
            int textW = font.width(labels[i]);
            g.drawString(font, labels[i], tx + (tabW - textW) / 2, y + 6,
                    sel ? 0xFF101410 : MenuTheme.TEXT_PRIMARY, false);
        }
        return hit;
    }
}
