package com.habitrain.core.scene.client;

import com.habitrain.core.client.config.SceneClientPerformanceRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热度索引的契约：内存命中也要刷新热度、写盘节流、索引缺失/损坏时回退到文件 mtime。
 *
 * <p>这正是"反复使用的背景被当成旧文件淘汰"的修复点——只看 mtime 的话，
 * 只在内存里命中的资产永远不会变热。</p>
 */
class SceneCacheIndexTest {
    private static final String HASH = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static final class FakeClock {
        long now = 1_000_000L;

        long current() {
            return now;
        }

        void advanceMillis(long millis) {
            now += millis;
        }
    }

    private static SceneCacheIndex index(Path dir, FakeClock clock) {
        return new SceneCacheIndex(dir.resolve(SceneCacheIndex.FILE_NAME), clock::current);
    }

    @Test
    void touchedHeatSurvivesAReload(@TempDir Path dir) {
        FakeClock clock = new FakeClock();
        SceneCacheIndex first = index(dir, clock);
        first.touch(HASH, 4_242L);
        assertTrue(first.flush());

        SceneCacheIndex second = index(dir, clock);
        second.loadIfNeeded();

        assertEquals(4_242L, second.lastUsed(HASH, 0L));
    }

    @Test
    void missingHashFallsBackToTheCallerSuppliedValue(@TempDir Path dir) {
        SceneCacheIndex index = index(dir, new FakeClock());
        index.loadIfNeeded();

        assertEquals(777L, index.lastUsed(HASH, 777L), "索引里没有就回退到文件 mtime");
    }

    @Test
    void missingOrCorruptIndexDegradesToAnEmptyOne(@TempDir Path dir) throws IOException {
        FakeClock clock = new FakeClock();
        SceneCacheIndex absent = index(dir, clock);
        absent.loadIfNeeded();
        assertEquals(0, absent.size());

        Files.writeString(dir.resolve(SceneCacheIndex.FILE_NAME), "{ not json", StandardCharsets.UTF_8);
        SceneCacheIndex corrupt = index(dir, clock);
        corrupt.loadIfNeeded();
        assertEquals(0, corrupt.size(), "读不动就当空索引，绝不抛给调用方");
        assertEquals(5L, corrupt.lastUsed(HASH, 5L));
    }

    @Test
    void throttleSkipsWritesUntilTheIntervalElapses(@TempDir Path dir) {
        FakeClock clock = new FakeClock();
        SceneCacheIndex index = index(dir, clock);
        index.loadIfNeeded();
        index.touch(HASH, clock.current());

        assertFalse(index.flushThrottled(), "刚写过就再写会产生大量小写入");
        clock.advanceMillis(SceneClientPerformanceRules.CACHE_INDEX_FLUSH_INTERVAL_MS - 1L);
        assertFalse(index.flushThrottled());

        clock.advanceMillis(1L);
        assertTrue(index.flushThrottled());
        assertFalse(index.flushThrottled(), "没有新改动就不该再写");
    }

    @Test
    void flushWritesTheLiveIndexNotAStaleSnapshot(@TempDir Path dir) {
        FakeClock clock = new FakeClock();
        SceneCacheIndex index = index(dir, clock);
        index.touch(HASH, 10L);
        index.touch(HASH_B, 20L);
        assertTrue(index.flush());

        SceneCacheIndex reloaded = index(dir, clock);
        reloaded.loadIfNeeded();
        assertEquals(10L, reloaded.lastUsed(HASH, -1L));
        assertEquals(20L, reloaded.lastUsed(HASH_B, -1L));
    }

    @Test
    void pruneDropsHashesWhoseFilesAreGone(@TempDir Path dir) {
        FakeClock clock = new FakeClock();
        SceneCacheIndex index = index(dir, clock);
        index.touch(HASH, 1L);
        index.touch(HASH_B, 2L);

        index.prune(Set.of(HASH));

        assertEquals(1, index.size());
        assertEquals(1L, index.lastUsed(HASH, -1L));
        assertEquals(-1L, index.lastUsed(HASH_B, -1L));
    }
}
