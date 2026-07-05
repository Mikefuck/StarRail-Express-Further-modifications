package com.habitrain.core.client.gui.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 共享 GUI 绘制工具 — 供配置页面各 Tab 复用。
 */
public final class SharedGuiKit {
    private SharedGuiKit() {}

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

    /** 绘制深色全屏背景 + 顶部装饰条 */
    public static void drawBackdrop(GuiGraphics g, int width, int height, int accent) {
        g.fill(0, 0, width, height, BG_DARK);
        g.fill(0, 0, width, 3, accent);
        g.fill(0, 3, width, 4, 0x408B6B47);
    }

    /** 绘制圆角面板背景（近似 — 实为实心矩形 + 细边框） */
    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h, int bg, int border) {
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
    }

    /** 左侧色条（任务/小游戏行标识） */
    public static void drawAccentStripe(GuiGraphics g, int x, int y, int h, int color) {
        g.fill(x, y, x + 3, y + h, color);
    }

    /** 状态标签（"已启用"/"已停用"药丸） */
    public static void drawStatusPill(GuiGraphics g, int x, int y, boolean enabled, int fontWidth) {
        int w = 48;
        int bg = enabled ? 0xFF1B3A2A : 0xFF3A1B1B;
        int fg = enabled ? 0xFF55CC55 : 0xFFCC5555;
        g.fill(x, y, x + w, y + 16, bg);
        g.fill(x, y, x + w, y + 1, 0x20FFFFFF);
        String text = enabled ? "已启用" : "已停用";
        // 绘制文字需在外部完成（GuiGraphics 不持有 Font）
    }

    /** 自定义滚动条 */
    public static void drawScrollbar(GuiGraphics g, int x, int y, int h, double scroll, double maxScroll, int trackW) {
        g.fill(x, y, x + trackW, y + h, 0x20FFFFFF);
        if (maxScroll <= 0) return;
        double ratio = scroll / maxScroll;
        int thumbH = Math.max(20, (int)(h * (h / (h + maxScroll))));
        int thumbY = y + (int)((h - thumbH) * ratio);
        g.fill(x, thumbY, x + trackW, thumbY + thumbH, 0x60FFFFFF);
    }

    /** 检查鼠标是否在矩形内 */
    public static boolean inBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 8色循环调色板（按 key hash） */
    public static int accentFor(String key) {
        int[] palette = {
                0xFF57C6D6, 0xFF8B6B47, 0xFFD4A55A, 0xFF6B8BD4,
                0xFFD46B6B, 0xFF6BD48B, 0xFFB06BD4, 0xFFD4B06B
        };
        int hash = key == null ? 0 : Math.abs(key.hashCode());
        return palette[hash % palette.length];
    }
}