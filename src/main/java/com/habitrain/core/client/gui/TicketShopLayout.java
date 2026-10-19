package com.habitrain.core.client.gui;

import java.util.Optional;

/** One readable shop page, the real hotbar and the role selector share a single vertical budget. */
public record TicketShopLayout(int left, int top, int width, int height, int cardWidth, int cardHeight,
                               int gap, int padding, int header, int footer, int columns, int rows,
                               int count, int hotbarY) {
    public static final int MAX_ROWS = 3;
    public static final int HOTBAR_HEIGHT = 32;
    // Upstream: hint at +40, heads at +80, navigation at +120 with 20px buttons.
    public static final int ROLE_SELECTION_BOTTOM = 140;
    private static final int TOP_MARGIN = 20;
    private static final int BOTTOM_MARGIN = 8;
    private static final int SHOP_HOTBAR_GAP = 6;

    /** Empty means that the entire shop must use the upstream pagination and coordinates. */
    public static Optional<TicketShopLayout> fit(int screenWidth, int screenHeight, int count,
                                                boolean roleSelection) {
        if (count <= 0 || screenWidth < (roleSelection ? 304 : 192)) return Optional.empty();
        int cardWidth = 44;
        int cardHeight = 46;
        int gap = 6;
        int padding = 6;
        int header = 20;
        int footer = 16;
        int availableWidth = Math.min(screenWidth - 16, (int) (screenWidth * 0.6));
        int columns = Math.min(count, (availableWidth - 2 * padding + gap) / (cardWidth + gap));
        if (columns < 1) return Optional.empty();
        int rows = 1 + (count - 1) / columns;
        if (rows > MAX_ROWS) return Optional.empty();
        int width = 2 * padding + columns * (cardWidth + gap) - gap;
        int height = header + rows * (cardHeight + gap) - gap + footer + padding;
        int lowerHeight = roleSelection ? ROLE_SELECTION_BOTTOM : HOTBAR_HEIGHT;
        int groupHeight = height + SHOP_HOTBAR_GAP + lowerHeight;
        int availableHeight = screenHeight - TOP_MARGIN - BOTTOM_MARGIN;
        if (groupHeight > availableHeight) return Optional.empty();
        int top = TOP_MARGIN + (availableHeight - groupHeight) / 2;
        return Optional.of(new TicketShopLayout((screenWidth - width) / 2, top, width, height,
                cardWidth, cardHeight, gap, padding, header, footer, columns, rows, count,
                top + height + SHOP_HOTBAR_GAP));
    }

    public float scale() { return 1.0f; }
    public int page() { return 0; }
    public int pages() { return 1; }
    public int start() { return 0; }
    public int end() { return count; }

    public int cardX(int index) {
        int rowStart = index / columns * columns;
        int rowCount = Math.min(columns, count - rowStart);
        int rowWidth = rowCount * (cardWidth + gap) - gap;
        return left + (width - rowWidth) / 2 + index % columns * (cardWidth + gap);
    }

    public int cardY(int index) {
        return top + header + index / columns * (cardHeight + gap);
    }

    public int navY() { return top + height - footer - padding / 2; }
    public int navSize() { return footer - 2; }

    public boolean contains(double x, double y) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }
}
