package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.util.Mth;

/** Shared thumb-to-content conversion for manually rendered configuration forms. */
public final class ScrollbarDrag {
    private boolean dragging;
    private double startY, startOffset;

    public boolean begin(double mouseX, double mouseY, int button, int x, int y,
                         int width, int viewportHeight, int contentHeight, double offset) {
        if (button != 0 || contentHeight <= viewportHeight
                || !MenuTheme.inBounds(mouseX, mouseY, x, y, width, viewportHeight)) return false;
        dragging = true;
        startY = mouseY;
        startOffset = offset;
        return true;
    }

    public boolean isDragging() { return dragging; }

    public double offset(double mouseY, int viewportHeight, int contentHeight) {
        int maximum = Math.max(0, contentHeight - viewportHeight);
        int travel = Math.max(1, viewportHeight - MenuTheme.scrollbarThumbHeight(viewportHeight, maximum));
        return Mth.clamp(startOffset + (mouseY - startY) * maximum / travel, 0, maximum);
    }

    public boolean end() {
        boolean wasDragging = dragging;
        dragging = false;
        return wasDragging;
    }
}
