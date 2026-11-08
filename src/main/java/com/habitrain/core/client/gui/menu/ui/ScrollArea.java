package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** 可滚动内容区：负责滚动偏移、拖拽、滚轮与滚动条。 */
public class ScrollArea {
    private int x, y, w, h;
    private double scroll;
    private final ScrollbarDrag scrollbar = new ScrollbarDrag();
    private int contentHeight;

    public ScrollArea(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    /** 页面尺寸变化时只更新视口，不清空该页面自己的滚动位置。 */
    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

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
        return scrollbar.begin(mx, my, btn, x + w - 7, y, 7, h, contentHeight, scroll);
    }
    public boolean mouseDragged(double my) {
        if (!scrollbar.isDragging()) return false;
        scroll = scrollbar.offset(my, h, contentHeight);
        return true;
    }
    public boolean mouseReleased() { return scrollbar.end(); }
    public boolean mouseScrolled(double sy) {
        scroll = Mth.clamp(scroll - sy * 18, 0, maxScroll());
        return true;
    }
}
