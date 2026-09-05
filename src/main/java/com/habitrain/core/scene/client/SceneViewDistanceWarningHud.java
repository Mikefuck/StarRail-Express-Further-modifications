package com.habitrain.core.scene.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Independent center-screen warning that does not consume Minecraft's title queue. */
@Environment(EnvType.CLIENT)
public final class SceneViewDistanceWarningHud {
    private static final int SHOW_TICKS = 5 * 20;
    private static final int FADE_TICKS = 10;
    private static Component message;
    private static int remainingTicks;

    private SceneViewDistanceWarningHud() {}

    public static void show(Component text) {
        if (text == null) return;
        message = text;
        remainingTicks = SHOW_TICKS;
    }

    public static void tick() {
        if (remainingTicks > 0 && --remainingTicks == 0) message = null;
    }

    public static void reset() {
        message = null;
        remainingTicks = 0;
    }

    public static boolean isVisible() {
        return message != null && remainingTicks > 0;
    }

    public static void render(GuiGraphics graphics) {
        if (!isVisible()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.options.hideGui) return;

        Font font = minecraft.font;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int panelWidth = Math.max(140, Math.min(360, screenWidth - 24));
        int textWidth = panelWidth - 48;
        List<FormattedCharSequence> lines = font.split(message, Math.max(80, textWidth));
        int contentHeight = Math.max(16, lines.size() * (font.lineHeight + 2) - 2);
        int panelHeight = contentHeight + 20;
        int x = (screenWidth - panelWidth) / 2;
        int y = (screenHeight - panelHeight) / 2;

        int elapsed = SHOW_TICKS - remainingTicks;
        float fadeIn = Math.min(1.0f, elapsed / (float) FADE_TICKS);
        float fadeOut = Math.min(1.0f, remainingTicks / (float) FADE_TICKS);
        int alpha = Math.max(0, Math.min(255, Math.round(255.0f * Math.min(fadeIn, fadeOut))));
        if (alpha <= 0) return;

        int background = withAlpha(0x171A20, Math.round(alpha * 0.86f));
        int border = withAlpha(0xD89B2B, alpha);
        int iconColor = withAlpha(0xE3A72F, alpha);
        int textColor = withAlpha(0xF4F1EA, alpha);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, background);
        graphics.fill(x, y, x + panelWidth, y + 1, border);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, border);
        graphics.fill(x, y, x + 1, y + panelHeight, border);
        graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, border);

        int iconX = x + 11;
        int iconY = y + (panelHeight - 14) / 2;
        graphics.fill(iconX, iconY, iconX + 14, iconY + 14, iconColor);
        graphics.drawCenteredString(font, "!", iconX + 7, iconY + 3, withAlpha(0x2A1C08, alpha));

        int textX = x + 36;
        int textY = y + (panelHeight - contentHeight) / 2;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, textX, textY, textColor, false);
            textY += font.lineHeight + 2;
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }
}
