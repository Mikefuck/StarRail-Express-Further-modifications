package com.habitrain.core.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 开局 / 结算 / 投票三处转场共用的动效工具。
 *
 * <p>全部基于 {@link GuiGraphics#fill} 与字体绘制，不依赖着色器；所有动画量都由调用方给出的
 * 0..1 进度驱动，本类不持有状态，因此可在面板平移、缩放等任意 pose 下复用。</p>
 */
final class TransitionFx {
    /** 字体在 alpha &lt; 4 时会被强制改成不透明，低于此值直接跳过绘制，避免闪白。 */
    private static final int MIN_TEXT_ALPHA = 6;

    private TransitionFx() {
    }

    // ==================== 缓动 ====================

    static float clamp01(float value) {
        return Mth.clamp(value, 0.0f, 1.0f);
    }

    /** 把 value 映射到 [start, start+length] 区间内的局部进度。 */
    static float segment(float value, float start, float length) {
        return length <= 0.0f ? (value >= start ? 1.0f : 0.0f) : clamp01((value - start) / length);
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

    static float easeInOutQuart(float value) {
        float t = clamp01(value);
        return t < 0.5f ? 8.0f * t * t * t * t
                : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 4.0) / 2.0f;
    }

    static float easeOutExpo(float value) {
        float t = clamp01(value);
        return t >= 1.0f ? 1.0f : 1.0f - (float) Math.pow(2.0, -10.0 * t);
    }

    /** 带回弹的缓出；overshoot 越大回弹越明显（标准值 1.70158）。 */
    static float easeOutBack(float value, float overshoot) {
        float t = clamp01(value) - 1.0f;
        return 1.0f + (overshoot + 1.0f) * t * t * t + overshoot * t * t;
    }

    // ==================== 颜色 ====================

    static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    static int mixRgb(int from, int to, float amount) {
        float t = clamp01(amount);
        int r = Math.round(Mth.lerp(t, (from >>> 16) & 0xFF, (to >>> 16) & 0xFF));
        int g = Math.round(Mth.lerp(t, (from >>> 8) & 0xFF, (to >>> 8) & 0xFF));
        int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ==================== 逐字级联文字 ====================

    /** 逐字入场的表现方式。 */
    enum Cascade {
        /** 自上方落下，轻微过冲后落定。 */
        DROP,
        /** 由大到小"盖章"砸入。 */
        STAMP,
        /** 自下方升起，柔和淡入。 */
        RISE
    }

    private record Glyph(Component text, int width) {
    }

    private static List<Glyph> glyphs(Font font, Component text) {
        List<Glyph> out = new ArrayList<>();
        text.getVisualOrderText().accept((index, style, codePoint) -> {
            Component glyph = Component.literal(new String(Character.toChars(codePoint))).withStyle(style);
            out.add(new Glyph(glyph, font.width(glyph)));
            return true;
        });
        return out;
    }

    /** 逐字级联文本在给定字距下的未缩放宽度。 */
    static float cascadeWidth(Font font, Component text, float tracking) {
        List<Glyph> list = glyphs(font, text);
        float total = 0.0f;
        for (Glyph glyph : list) {
            total += glyph.width();
        }
        return total + tracking * Math.max(0, list.size() - 1);
    }

    /**
     * 以 (centerX, top) 为顶部中心逐字绘制文本。
     *
     * @param enter    0..1 入场进度；字符按顺序错峰出现
     * @param exit     0..1 退场进度；字符按顺序错峰上飘淡出
     * @param tracking 额外字距（未缩放像素），可随时间收拢制造"聚焦"感
     */
    static void drawCascade(GuiGraphics g, Font font, Component text, float centerX, float top,
                            float scale, int color, float enter, float exit, float tracking,
                            Cascade mode) {
        if (enter <= 0.0f || exit >= 1.0f) {
            return;
        }
        List<Glyph> list = glyphs(font, text);
        int count = list.size();
        if (count == 0) {
            return;
        }
        float total = 0.0f;
        for (Glyph glyph : list) {
            total += glyph.width();
        }
        total += tracking * (count - 1);
        int baseAlpha = (color >>> 24) & 0xFF;
        // 字数越多，单字窗口越短，总时长保持一致
        float spread = count <= 1 ? 0.0f : Math.min(0.62f, 0.14f * (count - 1));

        g.pose().pushPose();
        g.pose().translate(centerX, top, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        float cursor = -total / 2.0f;
        for (int i = 0; i < count; i++) {
            Glyph glyph = list.get(i);
            float order = count <= 1 ? 0.0f : i / (float) (count - 1);
            float charIn = clamp01((enter - spread * order) / (1.0f - spread));
            float charOut = clamp01((exit - spread * order) / (1.0f - spread));
            float charCenter = cursor + glyph.width() / 2.0f;
            cursor += glyph.width() + tracking;
            if (charIn <= 0.0f || charOut >= 1.0f) {
                continue;
            }

            float fadeIn = easeOutCubic(Math.min(1.0f, charIn * 1.6f));
            float fadeOut = 1.0f - easeInCubic(charOut);
            int alpha = Math.round(baseAlpha * fadeIn * fadeOut);
            if (alpha < MIN_TEXT_ALPHA) {
                continue;
            }

            float dy;
            float charScale;
            switch (mode) {
                case STAMP -> {
                    dy = 0.0f;
                    charScale = Mth.lerp(easeOutBack(charIn, 1.2f), 2.1f, 1.0f);
                }
                case RISE -> {
                    dy = (1.0f - easeOutCubic(charIn)) * 7.0f;
                    charScale = 1.0f;
                }
                default -> {
                    dy = -(1.0f - easeOutBack(charIn, 2.2f)) * 9.0f;
                    charScale = Mth.lerp(easeOutCubic(charIn), 1.25f, 1.0f);
                }
            }
            dy -= easeInCubic(charOut) * 9.0f;
            charScale *= 1.0f + 0.18f * easeInCubic(charOut);

            g.pose().pushPose();
            g.pose().translate(charCenter, dy + 4.0f, 0.0f);
            g.pose().scale(charScale, charScale, 1.0f);
            g.pose().translate(-glyph.width() / 2.0f, -4.0f, 0.0f);
            g.drawString(font, glyph.text(), 0, 0, withAlpha(color, alpha), true);
            g.pose().popPose();
        }
        g.pose().popPose();
    }

    // ==================== 光效 ====================

    /** 自中心向两端渐隐的横向耀光线（带上下柔边）。 */
    static void drawFlare(GuiGraphics g, float centerX, int y, float halfWidth, int color, int alpha) {
        if (alpha <= 0 || halfWidth < 1.0f) {
            return;
        }
        int steps = 18;
        float stepW = halfWidth / steps;
        for (int i = 0; i < steps; i++) {
            float falloff = 1.0f - i / (float) steps;
            float strength = falloff * falloff;
            int x0 = Math.round(centerX + i * stepW);
            int x1 = Math.round(centerX + (i + 1) * stepW);
            int mx0 = Math.round(centerX - (i + 1) * stepW);
            int mx1 = Math.round(centerX - i * stepW);
            int core = withAlpha(color, Math.round(alpha * strength));
            int soft = withAlpha(color, Math.round(alpha * 0.28f * strength));
            g.fill(x0, y, x1, y + 1, core);
            g.fill(mx0, y, mx1, y + 1, core);
            g.fill(x0, y - 1, x1, y, soft);
            g.fill(mx0, y - 1, mx1, y, soft);
            g.fill(x0, y + 1, x1, y + 2, soft);
            g.fill(mx0, y + 1, mx1, y + 2, soft);
        }
    }

    /** 椭圆柔光：多层同心椭圆叠加，中心最亮。 */
    static void drawGlow(GuiGraphics g, float centerX, float centerY, float radiusX, float radiusY,
                         int color, int alpha) {
        if (alpha <= 0 || radiusX < 2.0f || radiusY < 2.0f) {
            return;
        }
        int layers = 5;
        int layerAlpha = Math.max(1, alpha / layers);
        int cx = Math.round(centerX);
        for (int layer = 1; layer <= layers; layer++) {
            float rx = radiusX * layer / layers;
            float ry = radiusY * layer / layers;
            int rows = Math.max(1, Math.round(ry));
            for (int dy = -rows; dy < rows; dy += 2) {
                float norm = (dy + 1.0f) / ry;
                float half = rx * (float) Math.sqrt(Math.max(0.0f, 1.0f - norm * norm));
                if (half < 1.0f) {
                    continue;
                }
                int y = Math.round(centerY + dy);
                g.fill(cx - Math.round(half), y, cx + Math.round(half), y + 2,
                        withAlpha(color, layerAlpha));
            }
        }
    }

    /** 冲击波环：由点阵组成的圆环，半径与透明度由调用方驱动。 */
    static void drawRing(GuiGraphics g, float centerX, float centerY, float radius, int color, int alpha) {
        if (alpha <= 0 || radius < 1.0f) {
            return;
        }
        int dots = Mth.clamp(Math.round(radius * 2.2f), 12, 320);
        int argb = withAlpha(color, alpha);
        for (int i = 0; i < dots; i++) {
            double a = i * Math.PI * 2.0 / dots;
            int x = Math.round(centerX + (float) Math.cos(a) * radius);
            int y = Math.round(centerY + (float) Math.sin(a) * radius * 0.62f);
            g.fill(x, y, x + 1, y + 1, argb);
        }
    }

    /**
     * 自中心放射的火花：确定性随机（按 seed），progress 驱动飞出距离与淡出。
     */
    static void drawSparks(GuiGraphics g, float centerX, float centerY, float progress, int count,
                           int seed, float reach, int color, int alpha) {
        if (progress <= 0.0f || progress >= 1.0f || alpha <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int hash = (i * 73 + seed * 151) * 0x9E3779B1;
            float angle = ((hash >>> 8) & 0xFFFF) / 65535.0f * Mth.TWO_PI;
            float speed = 0.45f + ((hash >>> 4) & 0xFF) / 255.0f * 0.55f;
            float local = clamp01(progress * (1.1f + speed * 0.3f));
            float dist = reach * speed * easeOutExpo(local);
            float dirX = Mth.cos(angle);
            float dirY = Mth.sin(angle) * 0.55f;
            float x = centerX + dirX * dist;
            float y = centerY + dirY * dist + local * local * 10.0f;
            int a = Math.round(alpha * (1.0f - local));
            if (a <= 2) {
                continue;
            }
            int size = (i % 4 == 0) ? 2 : 1;
            g.fill(Math.round(x), Math.round(y), Math.round(x) + size, Math.round(y) + size,
                    withAlpha(color, a));
            // 光尾：沿反方向的一个暗点
            float tail = Math.min(dist, 6.0f);
            g.fill(Math.round(x - dirX * tail), Math.round(y - dirY * tail),
                    Math.round(x - dirX * tail) + 1, Math.round(y - dirY * tail) + 1,
                    withAlpha(color, a / 2));
        }
    }

    /** 实心菱形。 */
    static void drawDiamond(GuiGraphics g, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int halfWidth = radius - Math.abs(dy);
            g.fill(centerX - halfWidth, centerY + dy,
                    centerX + halfWidth + 1, centerY + dy + 1, color);
        }
    }

    /** 绕中心旋转的点环 + 两颗亮点，用于徽章外圈。 */
    static void drawOrbit(GuiGraphics g, int centerX, int centerY, int radius, float angle,
                          int dimColor, int brightColor, int alpha) {
        if (alpha <= 0) {
            return;
        }
        int dots = 40;
        for (int i = 0; i < dots; i++) {
            float a = i * Mth.TWO_PI / dots;
            // 头部之后的一段"彗尾"弧最亮，形成旋转感
            float behind = (angle - a) / Mth.TWO_PI;
            float lit = 1.0f - (behind - (float) Math.floor(behind));
            int x = centerX + (int) Math.round(Math.cos(a) * radius);
            int y = centerY + (int) Math.round(Math.sin(a) * radius);
            g.fill(x, y, x + 1, y + 1, withAlpha(lit > 0.75f ? brightColor : dimColor,
                    Math.round(alpha * (0.35f + 0.65f * lit * lit))));
        }
    }
}
