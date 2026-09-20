package com.habitrain.core.client.config;

import com.habitrain.core.api.scene.SceneLimits;

/**
 * 客户端场景性能与传输的取值边界与默认值。
 *
 * <p>这里只放「需要在配置中心暴露、但取值必须被夹取」的项，集中默认值与夹取函数，
 * 便于像 {@code BlackoutGlobalCooldownRules} 那样单独写边界测试。纯粹的实现细节常量
 * （退避基数、写队列容量等）也放这里，避免散落在各个客户端类里。</p>
 *
 * <p><b>为什么这些值留在客户端本地而不参与服务端同步</b>：它们约束的是本机磁盘、堆、
 * 显存与帧时间，以及本机对任意服务器的重试节奏。服务器不该有权要求客户端占用多少堆，
 * 客户端也必须能对没有安装本模组配置的服务器正常工作。</p>
 */
public final class SceneClientPerformanceRules {
    // ---- 分片请求超时与重试 ----

    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 5000;
    public static final int MIN_REQUEST_TIMEOUT_MS = 500;
    public static final int MAX_REQUEST_TIMEOUT_MS = 30000;

    public static final int DEFAULT_MAX_CHUNK_RETRIES = 4;
    public static final int MIN_MAX_CHUNK_RETRIES = 0;
    public static final int MAX_MAX_CHUNK_RETRIES = 10;

    /** 指数退避基数：第 n 次重试等待 base << (n-1)。 */
    public static final int RETRY_BACKOFF_BASE_MS = 250;
    /** 退避上限，避免高次重试等待过久。 */
    public static final int RETRY_BACKOFF_MAX_MS = 10_000;

    // ---- 兼容性诊断报告 ----

    /**
     * 运行/预取路径默认不逐条保留正常方块条目。
     * 逐条保留会让大型场景的报告本身成为主要内存与 CPU 占用。
     */
    public static final boolean REPORT_DETAILED_DIAGNOSTICS = false;

    // ---- 磁盘缓存配额与热度 ----

    /** 已完成资产（.hscene）的磁盘总配额默认值（MiB）。 */
    public static final int DEFAULT_DISK_CACHE_QUOTA_MIB = 512;
    public static final int MIN_DISK_CACHE_QUOTA_MIB = 128;
    public static final int MAX_DISK_CACHE_QUOTA_MIB = 4096;

    /** 热度索引落盘节流：距上次写盘不足该间隔时跳过，避免频繁小写。 */
    public static final long CACHE_INDEX_FLUSH_INTERVAL_MS = 60_000L;

    // ---- 断点续传 ----

    public static final boolean DEFAULT_PARTIAL_RESUME_ENABLED = true;

    /** 部分下载文件（.part）的总配额默认值（MiB），与 .hscene 配额相互独立。 */
    public static final int DEFAULT_PARTIAL_CACHE_QUOTA_MIB = 128;
    public static final int MIN_PARTIAL_CACHE_QUOTA_MIB = 32;
    public static final int MAX_PARTIAL_CACHE_QUOTA_MIB = 1024;

    /** 部分下载文件的过期时间（小时）：超过该时长未更新的 .part 会被清理。 */
    public static final int DEFAULT_PARTIAL_EXPIRY_HOURS = 168;
    public static final int MIN_PARTIAL_EXPIRY_HOURS = 1;
    public static final int MAX_PARTIAL_EXPIRY_HOURS = 720;

    /**
     * 续传偏移必须对齐到服务端分片大小。
     * {@code SceneTransferService} 只接受 {@code offset % 65536 == 0} 或 0。
     */
    public static final int CHUNK_ALIGNMENT = 65536;

    /** 检查点元数据写盘节流：两个阈值任一满足即写一次。 */
    public static final long PARTIAL_METADATA_INTERVAL_MS = 2_000L;
    public static final long PARTIAL_METADATA_INTERVAL_BYTES = 4L * 1024L * 1024L;

    // ---- 分片下载窗口 ----

    /**
     * 允许同时在途的分片请求数。
     *
     * <p>1 就是此前的停等式传输：每 64 KiB 一个「请求 → 接收 → 落盘 → 再请求」往返，
     * 100 ms RTT 下吞吐上限只有 0.625 MiB/s，与 5 MiB/s 的限速无关。开到 4 之后上限变成
     * 4 × 64 KiB / RTT。上限由 {@link SceneLimits#MAX_CHUNKS_IN_FLIGHT_PER_PLAYER} 给出，
     * 服务端按同一常量放行，因此调到上限也不会被拒。</p>
     *
     * <p>注意：退避重试时会丢弃整窗并在更高的 requestId 上重发，服务端瞬时可见的在途数
     * 可达窗口的两倍，因此服务端的放行上限是该值上限（8）而不是默认值。</p>
     */
    public static final int DEFAULT_TRANSFER_WINDOW_CHUNKS = 4;
    public static final int MIN_TRANSFER_WINDOW_CHUNKS = 1;
    public static final int MAX_TRANSFER_WINDOW_CHUNKS = SceneLimits.MAX_CHUNKS_IN_FLIGHT_PER_PLAYER;

    // ---- 客户端网格构建预算 ----

    /**
     * 每帧用于推进场景网格构建的时间预算（毫秒）。
     *
     * <p>此前主背景、最多 4 个附加背景、预取与编辑器预览<b>各自</b>按 4 ms 推进，
     * 多个构建同时进行时彼此叠加；而且一次上传整层材质是不受预算约束的尾部尖峰。
     * 这个值现在由 {@code SceneBuildBudget} 在所有构建之间共享。</p>
     */
    public static final int DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME = 4;
    public static final int MIN_MESH_BUILD_BUDGET_MS_PER_FRAME = 1;
    public static final int MAX_MESH_BUILD_BUDGET_MS_PER_FRAME = 16;
    /** 令牌桶的补充速率按 60 fps 折算；burst 上限就是一帧预算本身。 */
    public static final int MESH_BUILD_BUDGET_ASSUMED_FPS = 60;

    /**
     * 每多少个 Section 才把网格多切一刀（空间分批）。
     *
     * <p>分批不是白拿的：每多一批就多几次 draw call 与渲染状态切换。真实资产最大 64 个
     * Section，那种量级下切了只会更慢，所以默认阈值取在一个明显更大的规模上——
     * 小于它的场景保持与历史完全一致的单份网格。0 表示关闭分批。</p>
     */
    public static final int DEFAULT_MESH_BATCH_MIN_SECTIONS = 512;
    public static final int MIN_MESH_BATCH_MIN_SECTIONS = 0;
    public static final int MAX_MESH_BATCH_MIN_SECTIONS = SceneLimits.MAX_NON_EMPTY_SECTIONS;

    // ---- 内存与显存配额 ----

    /** 已解码场景资产驻留内存的配额（MiB）。此前这张表完全没有上限。 */
    public static final int DEFAULT_MEMORY_CACHE_QUOTA_MIB = 256;
    public static final int MIN_MEMORY_CACHE_QUOTA_MIB = 64;
    public static final int MAX_MEMORY_CACHE_QUOTA_MIB = 2048;

    /** 场景网格顶点缓冲合计的估算配额（MiB）。此前只有单网格 256 MiB 上限。 */
    public static final int DEFAULT_MESH_CACHE_QUOTA_MIB = 512;
    public static final int MIN_MESH_CACHE_QUOTA_MIB = 128;
    public static final int MAX_MESH_CACHE_QUOTA_MIB = 2048;

    private SceneClientPerformanceRules() {}

    public static int clampMeshBatchMinSections(int value) {
        if (value <= MIN_MESH_BATCH_MIN_SECTIONS) return MIN_MESH_BATCH_MIN_SECTIONS;
        return Math.min(MAX_MESH_BATCH_MIN_SECTIONS, value);
    }

    public static int clampRequestTimeoutMs(int value) {
        return Math.max(MIN_REQUEST_TIMEOUT_MS, Math.min(MAX_REQUEST_TIMEOUT_MS, value));
    }

    public static int clampMaxChunkRetries(int value) {
        return Math.max(MIN_MAX_CHUNK_RETRIES, Math.min(MAX_MAX_CHUNK_RETRIES, value));
    }

    public static int clampDiskCacheQuotaMiB(int value) {
        return Math.max(MIN_DISK_CACHE_QUOTA_MIB, Math.min(MAX_DISK_CACHE_QUOTA_MIB, value));
    }

    public static int clampPartialCacheQuotaMiB(int value) {
        return Math.max(MIN_PARTIAL_CACHE_QUOTA_MIB, Math.min(MAX_PARTIAL_CACHE_QUOTA_MIB, value));
    }

    public static int clampPartialExpiryHours(int value) {
        return Math.max(MIN_PARTIAL_EXPIRY_HOURS, Math.min(MAX_PARTIAL_EXPIRY_HOURS, value));
    }

    public static int clampTransferWindowChunks(int value) {
        return Math.max(MIN_TRANSFER_WINDOW_CHUNKS, Math.min(MAX_TRANSFER_WINDOW_CHUNKS, value));
    }

    public static int clampMeshBuildBudgetMsPerFrame(int value) {
        return Math.max(MIN_MESH_BUILD_BUDGET_MS_PER_FRAME,
                Math.min(MAX_MESH_BUILD_BUDGET_MS_PER_FRAME, value));
    }

    public static int clampMemoryCacheQuotaMiB(int value) {
        return Math.max(MIN_MEMORY_CACHE_QUOTA_MIB, Math.min(MAX_MEMORY_CACHE_QUOTA_MIB, value));
    }

    public static int clampMeshCacheQuotaMiB(int value) {
        return Math.max(MIN_MESH_CACHE_QUOTA_MIB, Math.min(MAX_MESH_CACHE_QUOTA_MIB, value));
    }

    /** 每帧构建预算 → 令牌桶的每秒补充量。 */
    public static long meshBuildBudgetNanosPerSecond(int budgetMsPerFrame) {
        return (long) clampMeshBuildBudgetMsPerFrame(budgetMsPerFrame) * 1_000_000L * MESH_BUILD_BUDGET_ASSUMED_FPS;
    }

    /** 每帧构建预算 → 令牌桶的 burst 上限（即一帧最多消耗多少纳秒）。 */
    public static long meshBuildBudgetBurstNanos(int budgetMsPerFrame) {
        return (long) clampMeshBuildBudgetMsPerFrame(budgetMsPerFrame) * 1_000_000L;
    }

    /** MiB → 字节，并夹取到合法区间，避免配置里的负数/荒谬值直接参与比较。 */
    public static long quotaBytes(int quotaMiB) {
        return (long) quotaMiB * 1024L * 1024L;
    }

    public static long expiryMillis(int expiryHours) {
        return (long) expiryHours * 60L * 60L * 1000L;
    }

    /**
     * 把任意偏移向下对齐到分片边界；若已是 {@code totalSize}（短尾收尾）则原样返回。
     * 服务端不接受未对齐偏移，续传点必须是这个值。
     */
    public static long alignResumable(long offset, long totalSize) {
        if (offset <= 0L) return 0L;
        if (offset >= totalSize) return totalSize;
        return offset - (offset % CHUNK_ALIGNMENT);
    }
}
