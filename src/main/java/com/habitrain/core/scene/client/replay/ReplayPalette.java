package com.habitrain.core.scene.client.replay;

/**
 * "Star-night express" Art Deco palette, mirroring {@code com.habitrain.core.client.gui.RailArt}
 * (package-private there) so the world-space board matches the in-game GUI exactly.
 */
final class ReplayPalette {
    private ReplayPalette() {}

    static final int NIGHT_0 = 0xFF04060D;
    static final int NIGHT_1 = 0xFF0A1022;
    static final int NIGHT_2 = 0xFF111A33;
    static final int NIGHT_3 = 0xFF1A2748;
    static final int BRASS_DIM = 0xFF8A6A35;
    static final int BRASS = 0xFFD0A658;
    static final int BRASS_LIGHT = 0xFFF4D894;
    static final int CHAMPAGNE = 0xFFFFF4DA;
    static final int LAMP = 0xFFFFB55E;
    static final int RUBY = 0xFFFF5A64;
    static final int TEXT = 0xFFEEE8DA;
    static final int TEXT_MUTED = 0xFF9EA7BE;
    static final int TEXT_FAINT = 0xFF5E6884;
    static final int EMERGENCY = 0xFFB01E28;
    static final int PSYCHO = 0xFFC0182A;
    static final int DISGUISE = 0xFFB46CFF;
    static final int POISON = 0xFF7BE36A;
    static final int SHIELD = 0xFF6FC8FF;
    static final int EXPLOSION = 0xFFFF8A2E;
    static final int GHOST = 0xFFB9C3D9;
    static final int PASSENGER = 0xFF7FD1FF;
    static final int KILLER = 0xFFFF4A52;
    static final int NEUTRAL = 0xFFC9A0FF;

    /** Replaces the alpha channel. */
    static int alpha(int color, double alpha01) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha01)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Multiplies the existing alpha. */
    static int fade(int color, double factor) {
        double a = ((color >>> 24) & 0xFF) / 255.0;
        return alpha(color, a * factor);
    }

    /** 24-bit role / CCA colours arrive without alpha; 0 means "no colour". */
    static int opaque(int color, int fallback) {
        if ((color & 0x00FFFFFF) == 0 && (color >>> 24) == 0) return fallback;
        return (color >>> 24) == 0 ? color | 0xFF000000 : color;
    }

    static int lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) Math.round(aa + (ba - aa) * t) << 24) | ((int) Math.round(ar + (br - ar) * t) << 16)
                | ((int) Math.round(ag + (bg - ag) * t) << 8) | (int) Math.round(ab + (bb - ab) * t);
    }

    static int scale(int color, double brightness) {
        int r = (int) Math.min(255, ((color >> 16) & 0xFF) * brightness);
        int g = (int) Math.min(255, ((color >> 8) & 0xFF) * brightness);
        int b = (int) Math.min(255, (color & 0xFF) * brightness);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
