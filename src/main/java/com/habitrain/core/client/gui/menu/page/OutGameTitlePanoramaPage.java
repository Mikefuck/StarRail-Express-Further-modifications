package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** 游戏外 · 主菜单：客户端本地全景背景偏好。 */
public final class OutGameTitlePanoramaPage implements ConfigPage {
    private static final int PAD = 14;
    private static final ResourceLocation PREVIEW = HabiTrainCore.id(
            "textures/gui/title/panorama/panorama_1.png");

    private final Font font;
    private boolean enabled;
    private boolean saveFailed;
    private Hit toggleHit = Hit.EMPTY;

    private record Hit(int x, int y, int w, int h) {
        private static final Hit EMPTY = new Hit(0, 0, 0, 0);
    }

    public OutGameTitlePanoramaPage(Font font) {
        this.font = font;
        this.enabled = ClientVisualPreferences.isCustomTitlePanoramaEnabled();
    }

    /** This client-local option saves immediately and does not use the server save bar. */
    @Override public boolean canSave() { return false; }
    @Override public void save() {}
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int cardX = x + PAD;
        int cardY = y + PAD;
        int cardW = Math.max(1, w - PAD * 2);
        int cardH = Math.max(118, Math.min(282, h - PAD * 2));
        MenuTheme.panel(g, cardX, cardY, cardW, cardH);

        int accent = enabled ? MenuTheme.ACCENT_MINT : MenuTheme.ACCENT_BLUE;
        g.fill(cardX, cardY, cardX + 2, cardY + cardH, accent);

        Component title = Component.translatable("screen.habitrain_core.title_panorama.title");
        Component description = Component.translatable("screen.habitrain_core.title_panorama.description");
        g.drawString(font, title, cardX + 12, cardY + 11, MenuTheme.TEXT_PRIMARY, false);

        int textW = Math.max(20, cardW - 24);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(description, textW);
        int descriptionY = cardY + 27;
        int maxDescriptionLines = cardH < 180 ? 1 : 2;
        for (int i = 0; i < Math.min(maxDescriptionLines, lines.size()); i++) {
            g.drawString(font, lines.get(i), cardX + 12, descriptionY + i * 11,
                    MenuTheme.TEXT_SECONDARY, false);
        }

        int toggleH = 20;
        int toggleY = cardY + cardH - toggleH - 12;
        int previewY = descriptionY + maxDescriptionLines * 11 + 6;
        int previewH = Math.max(0, toggleY - previewY - 8);
        if (previewH >= 28 && cardW >= 80) {
            int previewX = cardX + 12;
            int previewW = cardW - 24;
            // Use a centered 16:9 crop from the square cube face so the card previews the
            // title-screen composition without stretching the source texture.
            g.blit(PREVIEW, previewX, previewY, previewW, previewH,
                    0, 224, 1024, 576, 1024, 1024);
            g.fill(previewX, previewY + previewH - 17, previewX + previewW, previewY + previewH,
                    0x85000000);
            g.drawString(font,
                    Component.translatable("screen.habitrain_core.title_panorama.preview"),
                    previewX + 6, previewY + previewH - 13, 0xFFF2F4F7, false);
        }

        int toggleW = Math.max(1, Math.min(210, cardW - 24));
        int toggleX = cardX + 12;
        PillToggle.render(g, font, toggleX, toggleY, toggleW, toggleH, enabled,
                Component.translatable("screen.habitrain_core.title_panorama.enabled").getString(),
                Component.translatable("screen.habitrain_core.title_panorama.disabled").getString());
        toggleHit = new Hit(toggleX, toggleY, toggleW, toggleH);

        if (saveFailed) {
            String error = Component.translatable("screen.habitrain_core.title_panorama.save_failed").getString();
            int errorX = Math.min(cardX + cardW - font.width(error) - 12, toggleX + toggleW + 10);
            if (errorX > toggleX + toggleW) {
                g.drawString(font, error, errorX, toggleY + 6, MenuTheme.DANGER, false);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (btn == 0 && MenuTheme.inBounds(mx, my,
                toggleHit.x(), toggleHit.y(), toggleHit.w(), toggleHit.h())) {
            toggle();
            return true;
        }
        return false;
    }

    private void toggle() {
        boolean next = !enabled;
        saveFailed = !ClientVisualPreferences.setCustomTitlePanoramaEnabled(next);
        if (!saveFailed) enabled = next;
        MenuSounds.playClick();
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy,
                                           int x, int y, int w, int h) { return false; }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return false; }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy,
                                            int x, int y, int w, int h) { return false; }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (key == 257 || key == 335 || key == 32) {
            toggle();
            return true;
        }
        return false;
    }

    @Override public boolean charTyped(char ch, int mod) { return false; }
}
