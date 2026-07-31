package com.habitrain.core.client.gui.menu.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

/** 文本框定位渲染助手。 */
public final class EditRow {
    private EditRow() {}

    public static void render(GuiGraphics g, int mx, int my, float delta, EditBox box, int x, int y, int w) {
        box.setX(x); box.setY(y); box.setWidth(w);
        box.render(g, mx, my, delta);
    }
}
