package com.habitrain.core.api.scene;

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

    /**
     * 同一条连接允许在途的场景资产分片数上限。
     *
     * <p>客户端下载队列用「有界窗口」一次发出多片请求，服务端据此放行；客户端把窗口上限
     * 夹取到这个值，因此这一条同时是协议能力边界与客户端的可配置上界。取 4 的倍数而不是
     * 与默认窗口相等：客户端超时回退时会丢弃整窗重发，服务端瞬时可见的在途数可达窗口的两倍。</p>
     */
    public static final int MAX_CHUNKS_IN_FLIGHT_PER_PLAYER = 8;

    /**
     * v3 资产级全局调色板的条目上限。
     *
     * <p>取值远高于任何真实场景（16 个线上资产合计只有几百个不同的方块串），但必须存在：
     * 解码端要在读任何字符串之前按这个数分配表，没有上限就等于让一个伪造的 int 决定客户端
     * 先分配多大。1 Mi 条 × 每条最多 1024 字符仍受整流的 128 MiB 上限约束，所以这里只防
     * "先分配"这一下。</p>
     */
    public static final int MAX_GLOBAL_PALETTE_ENTRIES = 1 << 20;

    public static final int MAX_BLOCK_PAYLOAD_BYTES = 256 * 1024; // 256 KiB
    public static final long MAX_TOTAL_PAYLOAD_BYTES = 32L * 1024L * 1024L; // 32 MiB
    public static final int MAX_VERTICES_PER_BLOCK = 4096;
    public static final int MAX_MATERIALS_PER_BLOCK = 64;
    public static final int MAX_PAYLOAD_STRING_LENGTH = 1024;
    public static final int MAX_PAYLOAD_NBT_DEPTH = 3;
    public static final int MAX_PAYLOAD_ARRAY_LENGTH = 1024;

    private SceneLimits() {}
}
