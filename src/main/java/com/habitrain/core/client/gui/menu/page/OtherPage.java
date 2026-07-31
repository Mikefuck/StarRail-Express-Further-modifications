package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 其他·空态页：无可修改选项，不显示保存栏。 */
public class OtherPage implements ConfigPage {
    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    public OtherPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root; this.font = font; this.editable = editable;
    }

    @Override public boolean canSave() { return false; }
    @Override public void save() {}
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        String msg = "§7暂无其他设置";
        g.drawString(font, Component.literal(msg), x + (w - font.width(msg)) / 2, y + 24, MenuTheme.TEXT_SECONDARY, false);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) { return false; }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return false; }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return false; }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return false; }
    @Override public boolean keyPressed(int key, int scan, int mod) { return false; }
    @Override public boolean charTyped(char ch, int mod) { return false; }
}
