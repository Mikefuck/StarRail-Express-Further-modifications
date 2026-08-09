package com.habitrain.core.client.gui.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 配置中心统一视觉语言。
 *
 * <p>设计取意于夜间列车控制台：低对比雾黑底、冷灰层级、薄荷绿运行信号和
 * 琥珀色提示信号。所有页面只从这里取色和基础图元，避免各子页各自长出一套风格。
 */
public final class MenuTheme {
    private MenuTheme() {}

    public static final int BG_DARK = 0xFF090E11;
    public static final int BG_SIDEBAR = 0xFF0D1418;
    public static final int BG_PANEL = 0xFF111A1F;
    public static final int BG_ROW = 0xFF162126;
    public static final int BG_ROW_HOVER = 0xFF1D2B31;
    public static final int BG_ROW_SELECTED = 0xFF22343A;
    public static final int BG_ELEVATED = 0xFF1A272D;
    public static final int BG_EDIT = 0xFF1B292F;

    public static final int TEXT_PRIMARY = 0xFFF0F5F3;
    public static final int TEXT_SECONDARY = 0xFF8A9A96;
    public static final int TEXT_DIM = 0xFF5E706C;

    public static final int ACCENT_MINT = 0xFF62D6B0;
    public static final int ACCENT_CYAN = ACCENT_MINT;
    public static final int ACCENT_AMBER = 0xFFE6B86A;
    public static final int ACCENT_BROWN = ACCENT_AMBER;
    public static final int ACCENT_BLUE = 0xFF77A7E8;
    public static final int ACCENT_VIOLET = 0xFFAA8BE8;
    public static final int DANGER = 0xFFE06C75;

    public static final int BORDER = 0xFF26353A;
    public static final int BORDER_SOFT = 0xFF1C292E;
    public static final int SEPARATOR = BORDER;
    public static final int BG_ENABLED = 0xFF173B31;
    public static final int BG_DISABLED = 0xFF3A2225;

    private static final int[] BASE_COLORS_RGB = {
        0xFF0000, 0xFF7F00, 0xFFFF00, 0x00FF00,
        0x0000FF, 0x8B00FF, 0xFF00FF, 0x00FFFF,
        0xFFC0CB, 0xFFA500, 0xC0C0C0, 0xFFFFFF,
        0xFF6B6B, 0xFFD700, 0x7CFC00, 0x00FA9A,
        0x6020F0, 0xFF1493, 0x00CED1, 0xFF8C00
    };
    public static final String[] COLOR_NAMES = {
        "红色", "橙色", "黄色", "绿色", "蓝色", "紫色", "品红色", "青色",
        "粉色", "琥珀色", "银色", "白色", "珊瑚色", "金色", "草绿色", "碧绿色",
        "紫罗兰", "深粉色", "深蓝色", "亮橙色"
    };

    public static int getColor(int index, int alpha) {
        return (alpha << 24) | (BASE_COLORS_RGB[index] & 0xFFFFFF);
    }

    public static int getColorCount() {
        return BASE_COLORS_RGB.length;
    }

    /** 绘制无贴图的层叠背景，低分辨率下也保持清晰。 */
    public static void drawBackdrop(GuiGraphics g, int width, int height, int accent) {
        g.fill(0, 0, width, height, BG_DARK);
        g.fill(0, 0, width, Math.min(76, height), 0xFF0C1317);
        g.fill(0, Math.min(76, height), width, Math.min(77, height), BORDER_SOFT);
        g.fill(0, 0, width, 2, accent);
        // 两条极弱的“轨道”基线，让纯色背景有识别度但不抢内容。
        if (height > 120) {
            g.fill(0, height - 18, width, height - 17, 0x10FFFFFF);
            g.fill(0, height - 8, width, height - 7, 0x0CFFFFFF);
        }
    }

    public static void drawAccentStripe(GuiGraphics g, int x, int y, int h, int color) {
        g.fill(x, y, x + 2, y + h, color);
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BG_PANEL);
        outline(g, x, y, w, h, BORDER_SOFT);
    }

    public static void editorHeader(GuiGraphics g, Font font, int width,
                                    String title, String subtitle, int accent) {
        g.fill(0, 0, width, 42, BG_SIDEBAR);
        g.fill(0, 41, width, 42, BORDER);
        g.fill(82, 8, 84, 33, accent);
        g.drawString(font, title, 92, 8, TEXT_PRIMARY, false);
        g.drawString(font, subtitle, 92, 22, TEXT_SECONDARY, false);
    }

    public static void editorFooter(GuiGraphics g, int width, int height, int footerHeight) {
        int y = height - footerHeight;
        g.fill(0, y, width, height, BG_SIDEBAR);
        g.fill(0, y, width, y + 1, BORDER);
    }

    public static void row(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean selected) {
        g.fill(x, y, x + w, y + h,
                selected ? BG_ROW_SELECTED : hover ? BG_ROW_HOVER : BG_ROW);
        if (selected) g.fill(x, y, x + 2, y + h, ACCENT_MINT);
        outline(g, x, y, w, h, selected ? 0xFF315148 : BORDER_SOFT);
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void button(GuiGraphics g, Font font, String label, int x, int y, int w, int h,
                              int accent, boolean enabled, boolean hover) {
        int bg = !enabled ? 0xFF151C20 : hover ? BG_ROW_SELECTED : BG_ELEVATED;
        int border = !enabled ? BORDER_SOFT : hover ? accent : BORDER;
        g.fill(x, y, x + w, y + h, bg);
        outline(g, x, y, w, h, border);
        if (enabled) g.fill(x, y, x + 2, y + h, accent);
        int color = enabled ? TEXT_PRIMARY : TEXT_DIM;
        g.drawString(font, label, x + (w - font.width(label)) / 2,
                y + (h - font.lineHeight) / 2, color, false);
    }

    public static void chip(GuiGraphics g, Font font, String label, int x, int y, int w, int h,
                            int color, boolean active) {
        g.fill(x, y, x + w, y + h, active ? withAlpha(color, 0x32) : BG_ELEVATED);
        outline(g, x, y, w, h, active ? withAlpha(color, 0xA0) : BORDER);
        g.drawString(font, label, x + (w - font.width(label)) / 2,
                y + (h - font.lineHeight) / 2, active ? color : TEXT_SECONDARY, false);
    }

    public static void drawScrollbar(GuiGraphics g, int x, int y, int h,
                                     double scroll, double maxScroll, int trackW) {
        g.fill(x, y, x + trackW, y + h, BG_PANEL);
        if (maxScroll <= 0) return;
        double ratio = scroll / maxScroll;
        int thumbH = Math.max(20, (int) (h * (h / (h + maxScroll))));
        int thumbY = y + (int) ((h - thumbH) * ratio);
        g.fill(x, thumbY, x + trackW, thumbY + thumbH, ACCENT_MINT);
    }

    public static boolean inBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static int accentFor(String key) {
        int[] palette = {
            ACCENT_MINT, ACCENT_AMBER, ACCENT_BLUE, ACCENT_VIOLET,
            0xFFE27D8A, 0xFF80C98B, 0xFFC78BE8, 0xFFE09B62
        };
        int hash = key == null ? 0 : (key.hashCode() & Integer.MAX_VALUE);
        return palette[hash % palette.length];
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
