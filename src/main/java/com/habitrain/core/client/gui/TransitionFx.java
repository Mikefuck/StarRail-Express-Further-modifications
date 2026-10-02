package com.habitrain.core.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 开局 / 结算 / 投票三处转场共用的动效与排版工具。
 *
 * <p>只含缓动、颜色与双语排版（宽字距副标题）等无状态工具，所有动画量都由调用方给出的
 * 0..1 进度驱动，因此可在车票平移、旋转、缩放等任意 pose 下复用。</p>
 */
final class TransitionFx {
    /** 字体在 alpha &lt; 4 时会被强制改成不透明，低于此值直接跳过绘制，避免闪白。 */
    private static final int MIN_TEXT_ALPHA = 6;
    /** 同排双语标签的字距与间隔。 */
    private static final float INLINE_TRACKING = 0.8f;
    private static final float INLINE_GAP = 4.0f;

    private TransitionFx() {
    }

    // ==================== 缓动 ====================

    static float clamp01(float value) {
        return Mth.clamp(value, 0.0f, 1.0f);
    }

    static float easeOutCubic(float value) {
        float inverse = 1.0f - clamp01(value);
        return 1.0f - inverse * inverse * inverse;
    }

    static float easeInCubic(float value) {
        float t = clamp01(value);
        return t * t * t;
    }

    static float easeInOutCubic(float value) {
        float t = clamp01(value);
        return t < 0.5f ? 4.0f * t * t * t
                : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 3.0) / 2.0f;
    }

    // ==================== 颜色 ====================

    static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    /** 保留颜色原有 alpha 并再乘以 factor。 */
    static int fade(int color, float factor) {
        return withAlpha(color, Math.round(((color >>> 24) & 0xFF) * clamp01(factor)));
    }

    static int mixRgb(int from, int to, float amount) {
        float t = clamp01(amount);
        int r = Math.round(Mth.lerp(t, (from >>> 16) & 0xFF, (to >>> 16) & 0xFF));
        int g = Math.round(Mth.lerp(t, (from >>> 8) & 0xFF, (to >>> 8) & 0xFF));
        int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ==================== 文字 ====================

    /** 宽字距文本在给定字距下的未缩放宽度。 */
    static float trackedWidth(Font font, String text, float tracking) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        float total = 0.0f;
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            total += font.width(new String(Character.toChars(codePoint)));
            count++;
            i += Character.charCount(codePoint);
        }
        return total + tracking * Math.max(0, count - 1);
    }

    /**
     * 宽字距单行（双语副标题用）：字符等距排开、整体居中，入场方式与 {@link #drawReveal} 相同。
     */
    static void drawTracked(GuiGraphics g, Font font, String text, float centerX, float top, float scale,
                            float tracking, int color, float progress, float rise) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float t = easeOutCubic(progress);
        int alpha = Math.round(((color >>> 24) & 0xFF) * t);
        if (alpha < MIN_TEXT_ALPHA) {
            return;
        }
        int drawColor = withAlpha(color, alpha);
        g.pose().pushPose();
        g.pose().translate(centerX, top + (1.0f - t) * rise, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        float cursor = -trackedWidth(font, text, tracking) / 2.0f;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            g.pose().pushPose();
            g.pose().translate(cursor, 0.0f, 0.0f);
            g.drawString(font, glyph, 0, 0, drawColor, false);
            g.pose().popPose();
            cursor += font.width(glyph) + tracking;
            i += Character.charCount(codePoint);
        }
        g.pose().popPose();
    }

    /** 同排双语标签（主文字 + 小号宽字距副文字）的整体宽度；副文字为空时只算主文字。 */
    static float inlinePairWidth(Font font, Component label, String secondary, float secondaryScale) {
        float width = font.width(label);
        if (secondary == null || secondary.isBlank()) {
            return width;
        }
        return width + INLINE_GAP + trackedWidth(font, secondary, INLINE_TRACKING) * secondaryScale;
    }

    /**
     * 同排双语标签：主文字在左、小号宽字距副文字在右，副文字与主文字字形底部对齐，整体以 centerX 居中。
     * 用于信息栏标签、铭牌等单行空间。
     */
    static void drawInlinePair(GuiGraphics g, Font font, Component label, String secondary, float centerX, int y,
                               float secondaryScale, int color, int secondaryColor, boolean shadow) {
        float labelW = font.width(label);
        float total = inlinePairWidth(font, label, secondary, secondaryScale);
        float startX = centerX - total / 2.0f;
        if (((color >>> 24) & 0xFF) >= MIN_TEXT_ALPHA) {
            g.drawString(font, label, Math.round(startX), y, color, shadow);
        }
        if (secondary == null || secondary.isBlank()) {
            return;
        }
        float secondaryW = total - labelW - INLINE_GAP;
        drawTracked(g, font, secondary, startX + labelW + INLINE_GAP + secondaryW / 2.0f,
                y + 7.0f - 7.0f * secondaryScale, secondaryScale, INLINE_TRACKING, secondaryColor, 1.0f, 0.0f);
    }
}
