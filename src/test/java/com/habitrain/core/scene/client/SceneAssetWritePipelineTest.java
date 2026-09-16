package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写盘流水线的契约：分片校验与落盘离开客户端线程、"写入确认后才推进"、
 * 可信前缀不变式（写失败不推进、重发不双写、收尾先 close 再交付、挂起保留可续传前缀）。
 *
 * <p>假文件仍然写真实磁盘，只是额外记录写入次数并可注入失败——这样 {@link ScenePartialStore}
 * 的续传校验也能在同一套用例里被真正跑到。</p>
 */
class SceneAssetWritePipelineTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final int CHUNK = 65536;

    /** 可以手动驱动的执行器：用来精确复现"上一次写入尚未落盘时重发同一片"。 */
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean autoRun = true;

        @Override
        public void execute(Runnable command) {
            if (autoRun) {
                command.run();
                return;
            }
            tasks.addLast(command);
        }

        int runAll() {
            int ran = 0;
            while (!tasks.isEmpty()) {
                tasks.pollFirst().run();
                ran++;
            }
            return ran;
        }
    }

    /** 句柄重叠、写入次数与失败注入的记账。 */
    private static final class FakeDisk {
        final Map<Path, Integer> liveHandles = new HashMap<>();
        final List<Path> truncations = new ArrayList<>();
        int writes;
        int opens;
        boolean overlap;
        boolean failWrites;
    }

    private static final class FakePartFile implements ScenePartFile {
        private final FakeDisk disk;
        private final Path path;
        private final RandomAccessFile raf;
        private boolean closed;

        FakePartFile(FakeDisk disk, Path path) throws IOException {
            this.disk = disk;
            this.path = path;
            this.raf = new RandomAccessFile(path.toFile(), "rw");
        }

        @Override
        public void writeAt(long offset, byte[] data, int length) throws IOException {
            if (disk.failWrites) throw new IOException("injected write failure");
            disk.writes++;
            raf.seek(offset);
            raf.write(data, 0, length);
        }

        @Override
        public long size() throws IOException {
            return raf.length();
        }

        @Override
        public void truncate(long length) throws IOException {
            disk.truncations.add(path);
            raf.setLength(length);
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            raf.close();
            disk.liveHandles.merge(path, -1, Integer::sum);
        }
    }

    private static final class Recorder implements SceneAssetWritePipeline.Events {
        final List<Long> committed = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();

        @Override
        public void onCommitted(AssetTransferRegistry.AssetEntry entry, long committedBytes) {
            committed.add(committedBytes);
        }

        @Override
        public void onComplete(AssetTransferRegistry.AssetEntry entry, Path dataFile) {
            completed.add(entry.sha256());
        }

        @Override
        public void onChunkRejected(AssetTransferRegistry.AssetEntry entry, String reason) {
            rejected.add(reason);
        }
    }

    private static final class Fixture {
        final ManualExecutor executor = new ManualExecutor();
        final FakeDisk disk = new FakeDisk();
        final Recorder events = new Recorder();
        final AssetTransferRegistry registry = new AssetTransferRegistry();
        final ScenePartialStore store;
        final SceneAssetWritePipeline pipeline;
        final Map<String, String> failures = new LinkedHashMap<>();
        int readyCalls;

        Fixture(Path cacheDir) {
            store = new ScenePartialStore(cacheDir);
            pipeline = new SceneAssetWritePipeline(executor, this::open, store, registry, events,
                    System::currentTimeMillis);
        }

        private ScenePartFile open(Path target) throws IOException {
            disk.opens++;
            disk.liveHandles.merge(target, 1, Integer::sum);
            if (disk.liveHandles.get(target) > 1) disk.overlap = true;
            return new FakePartFile(disk, target);
        }

        AssetTransferRegistry.AssetEntry entry(String sha256, long totalSize) {
            return registry.acquire(sha256, totalSize, false, null).entry();
        }

        /** 与生产接线一致：续传时句柄就绪后先把续传点写进 registry，队列才会按该偏移发首片。 */
        void begin(AssetTransferRegistry.AssetEntry entry, Path dataFile, long startOffset) {
            begin(entry, dataFile, startOffset, ScenePrefixHash.newSha256());
        }

        void begin(AssetTransferRegistry.AssetEntry entry, Path dataFile, long startOffset, MessageDigest seed) {
            pipeline.begin(entry, dataFile, startOffset, "fp", seed,
                    () -> {
                        readyCalls++;
                        // 与生产接线一致：两个游标在续传点重合，否则首片会从 0 开始。
                        registry.confirmBytes(entry, startOffset);
                        registry.confirmAccepted(entry, startOffset);
                    },
                    reason -> failures.put(entry.sha256(), reason));
        }
    }

    private Fixture current;

    /** 句柄不关掉的话，Windows 上连临时目录都删不掉（@TempDir 会在清理阶段报错）。 */
    @AfterEach
    void closeOpenHandles() {
        if (current == null) return;
        current.pipeline.closeAll();
        current.executor.runAll();
        current = null;
    }

    private Fixture fixture(Path dir) {
        current = new Fixture(dir);
        return current;
    }

    private static byte[] chunk(int seed, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) (seed + i);
        return data;
    }

    private static long crc(byte[] data) {
        return SceneAssetCodec.calculateCrc32(data, 0, data.length);
    }

    private static byte[] readAll(Path path) throws IOException {
        return Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
    }

    @Test
    void acceptedChunkIsWrittenAtItsOffsetAndAdvancesCommitted(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 2L * CHUNK);
        f.begin(entry, part, 0L);

        byte[] data = chunk(7, CHUNK);
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED, f.pipeline.offer(entry, 0L, data, crc(data)));

        assertArrayEquals(data, readAll(part));
        assertEquals(1, f.disk.writes);
        assertEquals(List.of((long) CHUNK), f.events.committed, "只应上报一次、且是新确认的字节数");
        assertEquals(CHUNK, entry.confirmedBytes());
        assertTrue(f.events.rejected.isEmpty());
    }

    @Test
    void duplicateChunkArrivingWhileWriteIsPendingIsDroppedWithoutRewriting(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.executor.autoRun = false;
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 2L * CHUNK);
        f.begin(entry, part, 0L);
        f.executor.runAll();

        byte[] data = chunk(7, CHUNK);
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED, f.pipeline.offer(entry, 0L, data, crc(data)));
        // 写入还挂在队列里时客户端请求超时、服务端重发了同一片
        assertEquals(SceneAssetWritePipeline.Offer.DUPLICATE, f.pipeline.offer(entry, 0L, data, crc(data)));

        f.executor.runAll();
        assertEquals(1, f.disk.writes, "同一偏移只允许写一次，否则会损坏文件");
        assertEquals(List.of((long) CHUNK), f.events.committed);
        assertArrayEquals(data, readAll(part));
    }

    @Test
    void badCrcIsRejectedWithoutTouchingTheFile(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 2L * CHUNK);
        f.begin(entry, part, 0L);

        byte[] data = chunk(7, CHUNK);
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED, f.pipeline.offer(entry, 0L, data, crc(data) + 1));

        assertEquals(0, f.disk.writes);
        assertEquals(0L, Files.size(part));
        assertEquals(0L, entry.confirmedBytes());
        assertEquals(List.of("分片 CRC 校验失败"), f.events.rejected);
        assertTrue(f.events.committed.isEmpty());
    }

    @Test
    void crcFailureKeepsTheOffsetReusableForTheRetry(@TempDir Path dir) {
        Fixture f = fixture(dir);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 2L * CHUNK);
        f.begin(entry, f.store.dataFile(HASH_A), 0L);

        byte[] data = chunk(7, CHUNK);
        f.pipeline.offer(entry, 0L, data, crc(data) + 1);
        assertEquals(1, f.events.rejected.size());

        // 窗口化之后，「已接收前缀」在 offer 时就推进了，因此重发同一偏移会被判重复——
        // 这正是调用方在收到 onChunkRejected 后必须先 rollbackAccepted 的原因。
        assertEquals(SceneAssetWritePipeline.Offer.DUPLICATE, f.pipeline.offer(entry, 0L, data, crc(data)));

        f.pipeline.rollbackAccepted(entry);
        assertEquals(0L, entry.confirmedBytes(), "坏片从未落盘，可信前缀必须还是 0");
        assertEquals(0L, f.pipeline.acceptedOffset(entry), "回退必须把已接收前缀退回可信前缀");
        assertEquals(0L, entry.acceptedBytes());

        // 回退之后重发必须被接受：否则一次 CRC 抖动就会把这个资产卡死。
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED, f.pipeline.offer(entry, 0L, data, crc(data)));
        assertEquals(1L * CHUNK, entry.confirmedBytes());
    }

    @Test
    void writeFailureDoesNotAdvanceCommittedAndRetryOverwritesTheSameOffset(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 2L * CHUNK);
        f.begin(entry, part, 0L);

        f.disk.failWrites = true;
        byte[] data = chunk(7, CHUNK);
        f.pipeline.offer(entry, 0L, data, crc(data));
        assertEquals(0L, entry.confirmedBytes(), "写失败绝不能推进已确认前缀");
        assertEquals(List.of("写入分片失败"), f.events.rejected);

        f.disk.failWrites = false;
        f.pipeline.rollbackAccepted(entry);
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED, f.pipeline.offer(entry, 0L, data, crc(data)));
        assertEquals(1L * CHUNK, entry.confirmedBytes());
        assertArrayEquals(data, readAll(part));
    }

    /** 窗口的核心：接收即推进"已接收前缀"，落盘前缀独立推进。 */
    @Test
    void acceptedPrefixAdvancesOnOfferWhileCommittedFollowsTheWriteThread(@TempDir Path dir) {
        Fixture f = fixture(dir);
        f.executor.autoRun = false; // 停住写线程，制造"已接收但未落盘"的窗口
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 3L * CHUNK);
        Path part = f.store.dataFile(HASH_A);
        f.begin(entry, part, 0L);

        byte[] data = chunk(7, CHUNK);
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED, f.pipeline.offer(entry, 0L, data, crc(data)));
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED, f.pipeline.offer(entry, CHUNK, data, crc(data)));
        assertEquals(SceneAssetWritePipeline.Offer.ACCEPTED,
                f.pipeline.offer(entry, 2L * CHUNK, data, crc(data)));

        assertEquals(0L, entry.confirmedBytes(), "写线程还没跑，落盘前缀必须还是 0");
        assertEquals(3L * CHUNK, entry.acceptedBytes(), "已接收前缀应随 offer 推进");
        assertEquals(3L * CHUNK, f.pipeline.acceptedOffset(entry));

        f.executor.runAll();
        assertEquals(3L * CHUNK, entry.confirmedBytes());
        assertEquals(List.of(HASH_A), f.events.completed);
    }

    /** 前一片失败后，仍在队列里的后续片必须被顺序守卫丢弃，绝不能越位写盘。 */
    @Test
    void chunksQueuedBehindAFailureAreDroppedInsteadOfWrittenOutOfOrder(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.executor.autoRun = false;
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 3L * CHUNK);
        Path part = f.store.dataFile(HASH_A);
        f.begin(entry, part, 0L);

        byte[] first = chunk(7, CHUNK);
        byte[] second = chunk(8, CHUNK);
        f.pipeline.offer(entry, 0L, first, crc(first) + 1); // 首位故意坏 CRC
        f.pipeline.offer(entry, CHUNK, second, crc(second));

        f.executor.runAll();

        assertEquals(List.of("分片 CRC 校验失败"), f.events.rejected);
        assertEquals(0L, entry.confirmedBytes(), "前一片没落盘，后面的片不得越位");
        assertEquals(0, f.disk.writes, "坏片之后的排片必须被丢弃，不能写进去");
        assertEquals(0L, Files.size(part));
    }

    @Test
    void outOfOrderAndOversizedOffsetsAreRejectedWithoutWriting(@TempDir Path dir) {
        Fixture f = fixture(dir);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 2L * CHUNK);
        f.begin(entry, f.store.dataFile(HASH_A), 0L);

        byte[] data = chunk(7, CHUNK);
        assertEquals(SceneAssetWritePipeline.Offer.GAP, f.pipeline.offer(entry, CHUNK, data, crc(data)));
        byte[] oversized = chunk(7, CHUNK * 3);
        assertEquals(SceneAssetWritePipeline.Offer.CONFLICT,
                f.pipeline.offer(entry, 0L, oversized, crc(oversized)));
        assertEquals(0, f.disk.writes);
        assertTrue(f.events.rejected.isEmpty(), "缺口/越界由调用方走既有重试路径，不是写线程的失败");
    }

    @Test
    void finalChunkClosesTheHandleBeforeDelivering(@TempDir Path dir) {
        Fixture f = fixture(dir);
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, (long) CHUNK);
        f.begin(entry, part, 0L);

        byte[] data = chunk(7, CHUNK);
        f.pipeline.offer(entry, 0L, data, crc(data));

        assertEquals(List.of(HASH_A), f.events.completed);
        assertEquals(0, f.disk.liveHandles.getOrDefault(part, 0),
                "Windows 上打开的文件无法移动：交付前必须先 close");
        assertEquals(AssetTransferRegistry.State.VERIFYING, entry.state());
    }

    @Test
    void beginTwiceForTheSameHashNeverLeavesTwoOpenHandles(@TempDir Path dir) {
        Fixture f = fixture(dir);
        f.executor.autoRun = false;
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry first = f.entry(HASH_A, 2L * CHUNK);
        f.begin(first, part, 0L);

        // 旧链路还没退役就来了同 hash 的新链路（reset 之后立刻重新下载）
        AssetTransferRegistry.AssetEntry second = f.entry(HASH_A, 2L * CHUNK);
        f.begin(second, part, 0L);
        f.executor.runAll();

        assertFalse(f.disk.overlap, "同一个 .part 上永远只能有一个写者");
        assertEquals(1, f.disk.liveHandles.getOrDefault(part, 0));
        assertEquals("槽位已被取代或作废", f.failures.get(HASH_A),
                "被取代的槽位必须回调失败，否则调用方的等待者永远不会结束");
        assertEquals(1, f.disk.opens, "被取代的槽位不该真的去开文件");
    }

    @Test
    void suspendKeepsAResumablePartialAndStopsFurtherCallbacks(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 3L * CHUNK);
        f.begin(entry, part, 0L);

        byte[] data = chunk(7, CHUNK);
        f.pipeline.offer(entry, 0L, data, crc(data));
        f.pipeline.suspend(entry);

        assertEquals(0, f.disk.liveHandles.getOrDefault(part, 0), "挂起必须关闭句柄");
        assertEquals(CHUNK, Files.size(part), "挂起必须保留部分文件，供下次续传");

        ScenePartialStore.PendingPart resumed = f.store.load(HASH_A).orElseThrow();
        assertEquals(CHUNK, resumed.resumableBytes());
        assertArrayEquals(data, readAll(part));

        // 挂起之后迟到的分片必须被拒绝，且不能重新打开文件
        byte[] late = chunk(9, CHUNK);
        assertEquals(SceneAssetWritePipeline.Offer.INACTIVE,
                f.pipeline.offer(entry, CHUNK, late, crc(late)));
        assertEquals(1, f.disk.opens, "退役的槽位不允许重开文件");
    }

    @Test
    void suspendRecordsTheInFlightWriteThatLandedAfterTheRequest(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.executor.autoRun = false;
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 3L * CHUNK);
        f.begin(entry, part, 0L);
        f.executor.runAll();

        byte[] data = chunk(7, CHUNK);
        f.pipeline.offer(entry, 0L, data, crc(data));
        // 写入还在队列里时就挂起：这一片仍然要落盘并计入检查点
        f.pipeline.suspend(entry);
        f.executor.runAll();

        assertEquals(CHUNK, Files.size(part), "在途写入必须先跑完再收尾");
        assertEquals(CHUNK, f.store.load(HASH_A).orElseThrow().resumableBytes(),
                "挂起时读到的 committed 必须包含在途那一片，不能漏记");
    }

    @Test
    void abandonDropsTheInFlightChunkAndClosesTheHandle(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.executor.autoRun = false;
        Path part = f.store.dataFile(HASH_A);
        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 3L * CHUNK);
        f.begin(entry, part, 0L);
        f.executor.runAll();

        byte[] data = chunk(7, CHUNK);
        f.pipeline.offer(entry, 0L, data, crc(data));
        f.pipeline.abandon(entry);
        f.executor.runAll();

        assertEquals(0, f.disk.liveHandles.getOrDefault(part, 0), "丢弃必须关闭句柄");
        assertTrue(f.events.committed.isEmpty(), "已丢弃的链路不允许再推进下载");
        assertEquals(SceneAssetWritePipeline.Offer.INACTIVE,
                f.pipeline.offer(entry, 0L, data, crc(data)));
    }

    @Test
    void resumeTruncatesToTheVerifiedPrefixAndContinuesFromThere(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Path part = f.store.dataFile(HASH_A);
        // 盘上有一个比可信前缀更长的文件（上次写了一半就崩了）
        Files.write(part, Arrays.copyOf(chunk(1, CHUNK), CHUNK * 2));

        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 3L * CHUNK);
        MessageDigest seed = ScenePrefixHash.newSha256();
        seed.update(chunk(1, CHUNK));
        f.begin(entry, part, CHUNK, seed);

        assertEquals(1, f.readyCalls);
        assertEquals(CHUNK, Files.size(part), "续传前必须把没确认的尾巴截掉");
        assertEquals(CHUNK, entry.confirmedBytes());

        byte[] next = chunk(9, CHUNK);
        f.pipeline.offer(entry, CHUNK, next, crc(next));
        assertEquals((long) CHUNK * 2, entry.confirmedBytes());
        assertEquals(1, f.disk.truncations.size(), "截断只应发生在首次请求之前的续传校验处");
    }

    @Test
    void resumeIsRefusedWhenThePartialFileIsShorterThanTheRecordedPrefix(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        Path part = f.store.dataFile(HASH_A);
        Files.write(part, chunk(1, CHUNK / 2));

        AssetTransferRegistry.AssetEntry entry = f.entry(HASH_A, 3L * CHUNK);
        MessageDigest seed = ScenePrefixHash.newSha256();
        seed.update(chunk(1, CHUNK));
        f.begin(entry, part, CHUNK, seed);

        assertEquals(0, f.readyCalls);
        assertEquals("部分文件短于续传点", f.failures.get(HASH_A));
    }

    @Test
    void completingOneHashLeavesTheOtherSlotRunning(@TempDir Path dir) {
        Fixture f = fixture(dir);
        AssetTransferRegistry.AssetEntry a = f.entry(HASH_A, 2L * CHUNK);
        AssetTransferRegistry.AssetEntry b = f.entry(HASH_B, (long) CHUNK);
        f.begin(a, f.store.dataFile(HASH_A), 0L);
        f.begin(b, f.store.dataFile(HASH_B), 0L);

        byte[] data = chunk(3, CHUNK);
        f.pipeline.offer(b, 0L, data, crc(data));

        assertEquals(List.of(HASH_B), f.events.completed);
        assertEquals(1, f.pipeline.activeSlots(), "收尾的槽位必须摘掉，否则每个 hash 都会留一个死条目");

        f.pipeline.offer(a, 0L, data, crc(data));
        assertEquals(CHUNK, a.confirmedBytes());
    }
}
