package com.habitrain.core.client.gui;

/** Shop tickets fit above the real hotbar; drawing and button hit boxes share these bounds. */
public record TicketShopLayout(int left, int top, int width, int height, int cardWidth, int cardHeight,
                               int gap, int padding, int header, int footer, int columns, int rows,
                               int page, int pages, int count, float scale) {
    public static TicketShopLayout fit(int screenWidth, int hotbarY, int count, int requestedPage) {
        int bottom = Math.max(2, hotbarY - 6);
        int availableHeight = Math.max(1, bottom - Math.min(20, bottom / 6));
        int availableWidth = Math.max(1, Math.round(screenWidth * 0.6f));
        float scale = Math.min(1.0f, Math.min(availableHeight / 88.0f, availableWidth / 58.0f));
        int cardWidth = Math.max(1, Math.round(44 * scale));
        int cardHeight = Math.max(1, Math.round(46 * scale));
        int gap = Math.max(1, Math.round(6 * scale));
        int padding = Math.max(1, Math.round(6 * scale));
        int header = Math.max(1, Math.round(20 * scale));
        int footer = Math.max(1, Math.round(16 * scale));
        int columns = Math.max(1, Math.min(Math.max(1, count),
                (availableWidth - 2 * padding + gap) / (cardWidth + gap)));
        int rows = Math.max(1, Math.min(4,
                (availableHeight - header - footer - padding + gap) / (cardHeight + gap)));
        rows = Math.min(rows, Math.max(1, (count + columns - 1) / columns));
        int pages = TicketUiGeometry.pages(count, columns * rows);
        int page = Math.max(0, Math.min(pages - 1, requestedPage));
        int width = 2 * padding + columns * (cardWidth + gap) - gap;
        int height = header + rows * (cardHeight + gap) - gap + footer + padding;
        return new TicketShopLayout((screenWidth - width) / 2, bottom - height, width, height,
                cardWidth, cardHeight, gap, padding, header, footer, columns, rows, page, pages, count, scale);
    }

    public int capacity() { return columns * rows; }
    public int start() { return page * capacity(); }
    public int end() { return Math.min(count, start() + capacity()); }

    public int cardX(int index) {
        int local = index - start();
        int rowStart = start() + local / columns * columns;
        int rowCount = Math.min(columns, count - rowStart);
        int rowWidth = rowCount * (cardWidth + gap) - gap;
        return left + (width - rowWidth) / 2 + local % columns * (cardWidth + gap);
    }

    public int cardY(int index) {
        return top + header + (index - start()) / columns * (cardHeight + gap);
    }

    public int navY() { return top + height - footer - padding / 2; }
    public int navSize() { return Math.max(1, footer - 2); }
}
