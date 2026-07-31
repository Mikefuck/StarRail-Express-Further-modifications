package com.habitrain.core.client.gui.menu;

import net.minecraft.client.gui.GuiGraphics;

/** 配置中心主题常量与绘制工具（替代旧 SharedGuiKit + SharedGuiConstants）。 */
public final class MenuTheme {
    private MenuTheme() {}

    public static final int BG_DARK = 0xFF12161D;
    public static final int BG_PANEL = 0xFF151A20;
    public static final int BG_ROW = 0xFF1B222B;
    public static final int BG_ROW_HOVER = 0xFF222B36;
    public static final int BG_ROW_SELECTED = 0xFF2A3440;
    public static final int TEXT_PRIMARY = 0xFFE8E8E8;
    public static final int TEXT_SECONDARY = 0xFF8A92A0;
    public static final int ACCENT_CYAN = 0xFF57C6D6;
    public static final int ACCENT_BROWN = 0xFF8B6B47;
    public static final int SEPARATOR = 0x30FFFFFF;
    public static final int BG_ENABLED = 0xFF1B3A2A;
    public static final int BG_DISABLED = 0xFF3A1B1B;
    public static final int BG_EDIT = 0xFF222B36;

    private static final int[] BASE_COLORS_RGB = {
        0xFF0000, 0xFF7F00, 0xFFFF00, 0x00FF00,
        0x0000FF, 0x8B00FF, 0xFF00FF, 0x00FFFF,
        0xFFC0CB, 0xFFA500, 0xC0C0C0, 0xFFFFFF,
        0xFF6B6B, 0xFFD700, 0x7CFC00, 0x00FA9A,
        0x6020F0, 0xFF1493, 0x00CED1, 0xFF8C00
    };
    public static final String[] COLOR_NAMES = {
        "红色","橙色","黄色","绿色","蓝色","紫色","品红色","青色",
        "粉色","琥珀色","银色","白色","珊瑚色","金色","草绿色","碧绿色",
        "紫罗兰","深粉色","深蓝色","亮橙色"
    };

    public static int getColor(int index, int alpha) { return (alpha << 24) | (BASE_COLORS_RGB[index] & 0xFFFFFF); }
    public static int getColorCount() { return BASE_COLORS_RGB.length; }

    public static void drawBackdrop(GuiGraphics g, int width, int height, int accent) {
        g.fill(0, 0, width, height, BG_DARK);
        g.fill(0, 0, width, 3, accent);
        g.fill(0, 3, width, 4, 0x408B6B47);
    }

    public static void drawAccentStripe(GuiGraphics g, int x, int y, int h, int color) {
        g.fill(x, y, x + 3, y + h, color);
    }

    public static void drawScrollbar(GuiGraphics g, int x, int y, int h, double scroll, double maxScroll, int trackW) {
        g.fill(x, y, x + trackW, y + h, 0x20FFFFFF);
        if (maxScroll <= 0) return;
        double ratio = scroll / maxScroll;
        int thumbH = Math.max(20, (int) (h * (h / (h + maxScroll))));
        int thumbY = y + (int) ((h - thumbH) * ratio);
        g.fill(x, thumbY, x + trackW, thumbY + thumbH, 0x60FFFFFF);
    }

    public static boolean inBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static int accentFor(String key) {
        int[] palette = {
            0xFF57C6D6, 0xFF8B6B47, 0xFFD4A55A, 0xFF6B8BD4,
            0xFFD46B6B, 0xFF6BD48B, 0xFFB06BD4, 0xFFD4B06B
        };
        int hash = key == null ? 0 : Math.abs(key.hashCode());
        return palette[hash % palette.length];
    }
}
