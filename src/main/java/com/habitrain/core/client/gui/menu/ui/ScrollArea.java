package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** 可滚动内容区：负责滚动偏移、拖拽、滚轮与滚动条。 */
public class ScrollArea {
    private final int x, y, w, h;
    private double scroll;
    private boolean dragging;
    private double dragStartY, dragStartScroll;
    private int contentHeight;

    public ScrollArea(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    public void setContentHeight(int contentHeight) {
        this.contentHeight = contentHeight;
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - h));
    }
    public int getContentY() { return y - (int) scroll; }
    public void reset() { scroll = 0; }
    public boolean isInside(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    public int maxScroll() { return Math.max(0, contentHeight - h); }

    public void render(GuiGraphics g) {
        MenuTheme.drawScrollbar(g, x + w - 4, y, h, scroll, maxScroll(), 3);
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (!isInside(mx, my)) return false;
        dragging = true;
        dragStartY = my;
        dragStartScroll = scroll;
        return true;
    }
    public boolean mouseDragged(double my) {
        if (!dragging) return false;
        scroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, maxScroll());
        return true;
    }
    public boolean mouseReleased() {
        if (!dragging) return false;
        dragging = false;
        return true;
    }
    public boolean mouseScrolled(double sy) {
        scroll = Mth.clamp(scroll - sy * 18, 0, maxScroll());
        return true;
    }
}
