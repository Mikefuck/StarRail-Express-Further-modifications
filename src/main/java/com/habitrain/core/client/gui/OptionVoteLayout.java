package com.habitrain.core.client.gui;

/**
 * 顶部投票面板的几何（GUI 缩放像素）：面板贴在屏幕上边缘水平居中，
 * 内含标题行、倒计时轨道、横向车票带与提示行。绘制与命中检测共用同一份布局。
 */
final class OptionVoteLayout {
    static final int MAX_PANEL_WIDTH = 560;
    static final int SIDE = 10;
    static final int GAP = 6;
    static final int ARROW = 14;
    static final int HEADER_Y = 5;
    static final int RAIL_Y = 27;
    static final int STRIP_Y = 35;

    private OptionVoteLayout() {}

    /**
     * @param x            面板左缘
     * @param width        面板宽度
     * @param height       面板高度（自屏幕上缘起）
     * @param stripX       车票带裁剪区左缘
     * @param stripW       车票带裁剪区宽度
     * @param cardW        单张车票宽度
     * @param cardH        单张车票高度
     * @param footerY      提示行 y
     * @param arrows       候选超出可视宽度时显示左右翻页
     */
    record Panel(int x, int width, int height, int stripX, int stripW, int cardW, int cardH,
                 int footerY, boolean arrows) {
        int right() {
            return x + width;
        }

        int centerX() {
            return x + width / 2;
        }

        int stripBottom() {
            return STRIP_Y + cardH;
        }

        /** 整排车票的总宽度。 */
        int contentWidth(int count) {
            return count <= 0 ? 0 : count * cardW + (count - 1) * GAP;
        }
    }

    static Panel panel(int screenWidth, int screenHeight, int count) {
        int width = Math.min(MAX_PANEL_WIDTH, Math.max(Math.min(screenWidth - 8, 260), screenWidth - 24));
        width = Math.max(120, Math.min(width, screenWidth));
        int x = (screenWidth - width) / 2;
        // 强制大 GUI 缩放（高度不足 240）时车票再压矮一些，保证面板仍只占上半屏
        int cardH = clamp(Math.round(screenHeight * 0.19f), screenHeight < 240 ? 38 : 46, 62);
        int cardW = Math.round(cardH * 2.0f);
        int available = width - SIDE * 2;
        cardW = Math.min(cardW, available);
        // 只差一点放不下时把车票略微收窄，整排完整显示，免得为一两个像素出现翻页
        int minCardW = Math.round(cardH * 1.75f);
        if (count > 0 && count * cardW + (count - 1) * GAP > available
                && count * minCardW + (count - 1) * GAP <= available) {
            cardW = (available - (count - 1) * GAP) / count;
        }
        boolean arrows = count > 0 && count * cardW + (count - 1) * GAP > available;
        int stripX = x + SIDE;
        int stripW = available;
        if (arrows) {
            stripX += ARROW + 4;
            stripW -= (ARROW + 4) * 2;
            cardW = Math.min(cardW, stripW);
        }
        int footerY = STRIP_Y + cardH + 7;
        int height = footerY + 13;
        return new Panel(x, width, height, stripX, stripW, cardW, cardH, footerY, arrows);
    }

    /**
     * 车票带的滚动偏移（像素）：放得下时整排居中（负偏移），放不下时让焦点车票尽量居中，
     * 并夹在首尾之间，保证任一焦点车票都完整可见。
     */
    static float scrollTarget(Panel panel, int focusedIndex, int count) {
        int content = panel.contentWidth(count);
        if (content <= panel.stripW()) {
            return -(panel.stripW() - content) / 2.0f;
        }
        float focusCenter = Math.max(0, focusedIndex) * (panel.cardW() + GAP) + panel.cardW() / 2.0f;
        float target = focusCenter - panel.stripW() / 2.0f;
        return Math.max(0.0f, Math.min(content - panel.stripW(), target));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
