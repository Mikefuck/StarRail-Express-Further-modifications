package com.habitrain.core.scene;

/**
 * 移动场景容量边界的单一事实来源。
 *
 * <p>单轴 32 个区块 = 512 格。非空 Section 上限按 32x32x8 设计，仍由
 * 未压缩大小、非空气方块数和客户端 VBO 预算继续提供纵深保护。</p>
 */
public final class SceneLimits {
    public static final int MAX_AXIS_CHUNKS = 32;
    public static final int MAX_AXIS_LENGTH = MAX_AXIS_CHUNKS * 16;
    public static final int MAX_NON_EMPTY_SECTIONS = 8_192;
    public static final int MAX_DECODE_SECTIONS = 8_192;
    public static final int MAX_MESH_SECTIONS = 8_192;
    public static final long MAX_NON_AIR_BLOCKS = 2_000_000L;
    public static final long MAX_TRANSFER_BYTES = 64L * 1024L * 1024L;

    public static final int MAX_BLOCK_PAYLOAD_BYTES = 256 * 1024; // 256 KiB
    public static final long MAX_TOTAL_PAYLOAD_BYTES = 32L * 1024L * 1024L; // 32 MiB
    public static final int MAX_VERTICES_PER_BLOCK = 4096;
    public static final int MAX_MATERIALS_PER_BLOCK = 64;
    public static final int MAX_PAYLOAD_STRING_LENGTH = 1024;
    public static final int MAX_PAYLOAD_NBT_DEPTH = 3;
    public static final int MAX_PAYLOAD_ARRAY_LENGTH = 1024;

    private SceneLimits() {}
}
