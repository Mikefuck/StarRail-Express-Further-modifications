package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.scene.model.SceneLoopDistanceMode;
import com.habitrain.core.scene.model.SceneMotionMath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Pixel-native straight-loop track preview used by the scene editor. */
public final class SceneRangePreview {
    public static final int HEIGHT = 50;

    private SceneRangePreview() {}

    public static void render(GuiGraphics graphics, Font font, int x, int y, int width,
                              SceneLoopDistanceMode mode,
                              SceneMotionMath.LoopDistances distances,
                              SceneMotionMath.SeamRelation seam,
                              double phase, boolean enabled) {
        int safeWidth = Math.max(80, width);
        int color = mode == SceneLoopDistanceMode.AUTO
                ? MenuTheme.ACCENT_BLUE : MenuTheme.ACCENT_VIOLET;
        int trackLeft = x + 30;
        int trackRight = x + safeWidth - 30;
        int trackY = y + 24;

        graphics.fill(x, y, x + safeWidth, y + HEIGHT, MenuTheme.BG_ELEVATED);
        MenuTheme.outline(graphics, x, y, safeWidth, HEIGHT, MenuTheme.BORDER_SOFT);
        graphics.drawString(font,
                Component.translatable("screen.habitrain_core.scene_motion.range_start"),
                x + 5, y + 5, MenuTheme.TEXT_SECONDARY, false);
        String end = Component.translatable("screen.habitrain_core.scene_motion.range_end",
                format(distances.effectiveDistance())).getString();
        graphics.drawString(font, end, x + safeWidth - font.width(end) - 5, y + 5,
                MenuTheme.TEXT_SECONDARY, false);

        graphics.fill(trackLeft, trackY, trackRight, trackY + 2,
                enabled ? MenuTheme.withAlpha(color, 0xC0) : MenuTheme.BORDER);
        graphics.fill(trackRight - 5, trackY - 3, trackRight, trackY + 5,
                enabled ? color : MenuTheme.TEXT_DIM);
        graphics.fill(trackLeft - 5, trackY - 6, trackLeft + 6, trackY + 8,
                MenuTheme.withAlpha(color, enabled ? 0x55 : 0x20));
        MenuTheme.outline(graphics, trackLeft - 5, trackY - 6, 11, 14,
                enabled ? color : MenuTheme.TEXT_DIM);
        graphics.fill(trackRight - 5, trackY - 6, trackRight + 6, trackY + 8,
                MenuTheme.withAlpha(color, enabled ? 0x55 : 0x20));
        MenuTheme.outline(graphics, trackRight - 5, trackY - 6, 11, 14,
                enabled ? color : MenuTheme.TEXT_DIM);

        double effective = Math.max(1.0, distances.effectiveDistance());
        double normalized = phase - Math.floor(phase / effective) * effective;
        double ratio = Math.max(0.0, Math.min(1.0, normalized / effective));
        int markerX = trackLeft + (int) Math.round((trackRight - trackLeft) * ratio);
        graphics.fill(markerX - 2, trackY - 5, markerX + 3, trackY + 7,
                enabled ? MenuTheme.ACCENT_MINT : MenuTheme.TEXT_DIM);

        String seamText = switch (seam) {
            case SEAMLESS -> Component.translatable(
                    "screen.habitrain_core.scene_motion.range_seamless").getString();
            case OVERLAP -> Component.translatable(
                    "screen.habitrain_core.scene_motion.range_overlap",
                    format(distances.recommendedDistance() - distances.effectiveDistance())).getString();
            case GAP -> Component.translatable(
                    "screen.habitrain_core.scene_motion.range_gap",
                    format(distances.effectiveDistance() - distances.recommendedDistance())).getString();
            case UNKNOWN -> Component.translatable(
                    "screen.habitrain_core.scene_motion.range_waiting_bounds").getString();
        };
        graphics.drawString(font, trim(font, seamText, safeWidth - 10), x + 5, y + 37,
                seam == SceneMotionMath.SeamRelation.SEAMLESS ? MenuTheme.ACCENT_MINT
                        : seam == SceneMotionMath.SeamRelation.UNKNOWN ? MenuTheme.TEXT_DIM
                        : MenuTheme.ACCENT_AMBER,
                false);
    }

    private static String trim(Font font, String value, int width) {
        if (font.width(value) <= width) return value;
        String ellipsis = "…";
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
