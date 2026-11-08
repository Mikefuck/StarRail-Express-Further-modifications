package com.habitrain.core.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Label fitting shared by the inventory stamps and the secondary menu tray. */
final class StampLabelLayout {
    private static final float[] SCALES = {1.0f, 0.75f, 1.0f, 0.75f, 0.5f, 0.5f};
    private static final int[] LINES = {1, 1, 2, 2, 1, 2};

    record Layout(List<FormattedCharSequence> lines, float scale, boolean truncated) {}

    private StampLabelLayout() {}

    static Layout fit(Font font, Component label, float availableWidth, float pixelScale, float maximumHeight) {
        String plain = label.getString();
        Component display = Component.literal(plain);
        for (int i = 0; i < SCALES.length; i++) {
            float scale = StampArt.crispDu(plain, SCALES[i], pixelScale);
            List<FormattedCharSequence> split = font.split(display, Math.max(1, Math.round(availableWidth / scale)));
            if (split.size() <= LINES[i]
                    && (LINES[i] == 1 || (8.0f * scale + 1.0f) * split.size() <= maximumHeight)) {
                return new Layout(split, scale, false);
            }
        }
        float scale = StampArt.crispDu(plain, 0.5f, pixelScale);
        List<FormattedCharSequence> split = font.split(display, Math.max(1, Math.round(availableWidth / scale)));
        return new Layout(List.copyOf(split.subList(0, Math.min(2, split.size()))), scale, split.size() > 2);
    }
}
