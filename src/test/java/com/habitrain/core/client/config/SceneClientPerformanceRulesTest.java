package com.habitrain.core.client.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景客户端性能项的边界：配置中心与手工编辑的 JSON 都可能给出越界值，
 * 这些值最终会变成磁盘配额、请求偏移与内存分配，必须在入口夹住。
 */
class SceneClientPerformanceRulesTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void requestTimeoutAndRetriesStayInRange() {
        assertEquals(SceneClientPerformanceRules.MIN_REQUEST_TIMEOUT_MS,
                SceneClientPerformanceRules.clampRequestTimeoutMs(-1));
        assertEquals(SceneClientPerformanceRules.MAX_REQUEST_TIMEOUT_MS,
                SceneClientPerformanceRules.clampRequestTimeoutMs(Integer.MAX_VALUE));
        assertEquals(SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS,
                SceneClientPerformanceRules.clampRequestTimeoutMs(
                        SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS));

        assertEquals(0, SceneClientPerformanceRules.clampMaxChunkRetries(-5));
        assertEquals(SceneClientPerformanceRules.MAX_MAX_CHUNK_RETRIES,
                SceneClientPerformanceRules.clampMaxChunkRetries(99));
    }

    @Test
    void diskCacheQuotaIsClampedAndConvertedToBytes() {
        assertEquals(SceneClientPerformanceRules.MIN_DISK_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules.clampDiskCacheQuotaMiB(0));
        assertEquals(SceneClientPerformanceRules.MAX_DISK_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules.clampDiskCacheQuotaMiB(1_000_000));
        assertEquals(SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules.clampDiskCacheQuotaMiB(
                        SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB));
        assertEquals(512L * MIB, SceneClientPerformanceRules.quotaBytes(512));
    }

    @Test
    void partialQuotaAndExpiryAreClamped() {
        assertEquals(SceneClientPerformanceRules.MIN_PARTIAL_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules.clampPartialCacheQuotaMiB(-1));
        assertEquals(SceneClientPerformanceRules.MAX_PARTIAL_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules.clampPartialCacheQuotaMiB(Integer.MAX_VALUE));
        assertEquals(SceneClientPerformanceRules.MIN_PARTIAL_EXPIRY_HOURS,
                SceneClientPerformanceRules.clampPartialExpiryHours(0));
        assertEquals(SceneClientPerformanceRules.MAX_PARTIAL_EXPIRY_HOURS,
                SceneClientPerformanceRules.clampPartialExpiryHours(100_000));
        assertEquals(168L * 60L * 60L * 1000L, SceneClientPerformanceRules.expiryMillis(168));
    }

    @Test
    void resumableOffsetsAreAlignedToTheServerChunkGrid() {
        // 服务端只接受 offset % 65536 == 0，未对齐的续传点会被拒成 OUT_OF_RANGE
        assertEquals(0L, SceneClientPerformanceRules.alignResumable(0L, 1_000_000L));
        assertEquals(0L, SceneClientPerformanceRules.alignResumable(65_535L, 1_000_000L));
        assertEquals(65_536L, SceneClientPerformanceRules.alignResumable(65_536L, 1_000_000L));
        assertEquals(65_536L, SceneClientPerformanceRules.alignResumable(131_071L, 1_000_000L));
        assertEquals(131_072L, SceneClientPerformanceRules.alignResumable(131_072L, 1_000_000L));
    }

    @Test
    void aCompletedPartialKeepsItsExactLengthInsteadOfBeingAlignedDown() {
        // 收尾时 committed == totalSize（短尾不是对齐值），必须原样保留，否则会丢掉尾巴
        assertEquals(100L, SceneClientPerformanceRules.alignResumable(100L, 100L));
        assertEquals(70_000L, SceneClientPerformanceRules.alignResumable(70_000L, 70_000L));
        assertEquals(70_000L, SceneClientPerformanceRules.alignResumable(90_000L, 70_000L));
    }
}
