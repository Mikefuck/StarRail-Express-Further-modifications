package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 紧凑型二级导航：用描边和信号色表达状态，避免整块高饱和底色。 */
public class SubTabBar {
    private final String[] labels;
    private final int[] accents;
    public static final int H = 24;

    public SubTabBar(String[] labels, int[] accents) { this.labels = labels; this.accents = accents; }
    public SubTabBar(String[] labels, int accent) {
        this.labels = labels;
        this.accents = new int[labels.length];
        java.util.Arrays.fill(this.accents, accent);
    }

    public int getHeight() { return H; }
    public int count() { return labels.length; }

    /** 返回悬停下标（-1 表示无），调用方据此处理点击切换。 */
    public int render(GuiGraphics g, Font font, int x, int y, int w, int selected, int mx, int my) {
        if (labels.length == 0 || w <= 0) return -1;
        int gap = w < labels.length * 70 ? 2 : 5;
        int available = Math.max(labels.length, w - (labels.length - 1) * gap);
        int tabW = Math.max(1, Math.min(132, available / labels.length));
        int hit = -1;
        for (int i = 0; i < labels.length; i++) {
            int tx = x + i * (tabW + gap);
            boolean sel = i == selected;
            boolean hover = MenuTheme.inBounds(mx, my, tx, y, tabW, H);
            if (hover) hit = i;
            String visibleLabel = font.plainSubstrByWidth(labels[i], Math.max(1, tabW - 8));
            MenuTheme.chip(g, font, visibleLabel, tx, y, tabW, H, accents[i], sel);
            if (hover && !sel) MenuTheme.outline(g, tx, y, tabW, H, MenuTheme.TEXT_DIM);
        }
        return hit;
    }
}
