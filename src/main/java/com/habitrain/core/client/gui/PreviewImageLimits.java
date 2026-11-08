package com.habitrain.core.client.gui;

import java.nio.ByteBuffer;

/** Inspect PNG dimensions before STB allocates native pixel memory. */
final class PreviewImageLimits {
    static final int MAX_EDGE = 1024;
    static final long MAX_PIXELS = 1024L * 1024L;

    private PreviewImageLimits() {}

    static boolean accepts(byte[] png) {
        if (png == null || png.length < 33 || png.length > 128 * 1024) return false;
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getLong() != 0x89504e470d0a1a0aL || header.getInt() != 13
                || header.getInt() != 0x49484452) return false;
        int width = header.getInt();
        int height = header.getInt();
        return width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE
                && (long) width * height <= MAX_PIXELS;
    }
}
