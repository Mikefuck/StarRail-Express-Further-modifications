package com.habitrain.core.client.gui;

public class SharedGuiConstants {

    private static final int[] BASE_COLORS_RGB = {
        0xFF0000, 0xFF7F00, 0xFFFF00, 0x00FF00,
        0x0000FF, 0x8B00FF, 0xFF00FF, 0x00FFFF,
        0xFFC0CB, 0xFFA500, 0xC0C0C0, 0xFFFFFF,
        0xFF6B6B, 0xFFD700, 0x7CFC00, 0x00FA9A,
        0x6020F0, 0xFF1493, 0x00CED1, 0xFF8C00
    };

    public static final String[] COLOR_NAMES = {
        "红色","橙色","黄色","绿色",
        "蓝色","紫色","品红色","青色",
        "粉色","琥珀色","银色","白色",
        "珊瑚色","金色","草绿色","碧绿色",
        "紫罗兰","深粉色","深蓝色","亮橙色"
    };

    public static int getColor(int index, int alpha) {
        return (alpha << 24) | (BASE_COLORS_RGB[index] & 0xFFFFFF);
    }

    public static int getColorCount() {
        return BASE_COLORS_RGB.length;
    }
}
