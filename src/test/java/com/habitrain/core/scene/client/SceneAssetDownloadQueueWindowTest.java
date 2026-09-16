package com.habitrain.core.scene.client;

import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 有界窗口的调度契约。
 *
 * <p>这批断言固定的是报告 §3.3 的修复点：请求推进不再依赖写盘确认（因此每片少一次
 * 客户端线程跳转），以及整窗回退在超时/BUSY/写失败下都不会把链路卡死。</p>
 */
class SceneAssetDownloadQueueWindowTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final long CHUNK = 65_536L;

    private static final class FakeClock {
        long nowNanos;

        long current() {
            return nowNanos;
        }

        void advanceMillis(long millis) {
            nowNanos += millis * 1_000_000L;
        }
    }

    private static final class RecordingSender implements SceneAssetDownloadQueue.Sender {
        final List<SceneAssetChunkRequestC2S> sent = new ArrayList<>();
        boolean canSend = true;

        @Override
        public boolean canSend() {
            return canSend;
        }

        @Override
        public void send(SceneAssetChunkRequestC2S request) {
            sent.add(request);
        }
    }

    private static final class RecordingFailures implements SceneAssetDownloadQueue.FailureHandler {
        final List<String> reasons = new ArrayList<>();

        @Override
        public void onExhausted(AssetTransferRegistry.AssetEntry entry, String reason) {
            reasons.add(entry.sha256() + ":" + reason);
        }
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final RecordingSender sender = new RecordingSender();
        final RecordingFailures failures = new RecordingFailures();
        final AssetTransferRegistry registry = new AssetTransferRegistry();
        final SceneAssetDownloadQueue queue;
        final int window;

        Fixture(int windowChunks) {
            this.window = windowChunks;
            queue = new SceneAssetDownloadQueue(clock::current, sender, failures,
                    () -> 1000, () -> 3, () -> windowChunks, 100, 1000);
        }

        AssetTransferRegistry.AssetEntry entry(String sha256, long totalSize) {
            return registry.acquire(sha256, totalSize, false, null).entry();
        }

        void accept(AssetTransferRegistry.AssetEntry entry, long offset, long length) {
            registry.confirmAccepted(entry, offset + length);
            queue.onBytesReceived(entry, offset, length);
        }

        long chunkLength(long offset, long totalSize) {
            return Math.min(CHUNK, totalSize - offset);
        }
    }

    private static List<Long> offsets(RecordingSender sender) {
        List<Long> result = new ArrayList<>();
        for (SceneAssetChunkRequestC2S request : sender.sent) {
            result.add(request.chunkOffset());
        }
        return result;
    }

    @Test
    void corruptResponsesCannotResetRetryBudgetForever() {
        Fixture f = new Fixture(1);
        var entry = f.entry(HASH_A, CHUNK);
        f.queue.enqueue(entry, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        for (int i = 0; i < 4; i++) {
            f.accept(entry, 0, CHUNK);
            f.registry.confirmAccepted(entry, 0); // writer rejects CRC
            assertEquals(i < 3, f.queue.onRetryableFailure(entry, "CRC"));
            f.clock.advanceMillis(1000);
            f.queue.tick();
        }
        assertEquals(1, f.failures.reasons.size());
        assertEquals(0, f.queue.pendingCount());
    }

    @Test
    void oldCompletionCannotRemoveReplacementTransfer() {
        Fixture f = new Fixture(1);
        var old = f.entry(HASH_A, CHUNK);
        f.queue.enqueue(old, SceneAssetDownloadQueue.Priority.PREFETCH);
        f.queue.cancel(HASH_A);
        f.registry.completeFailure(old);
        var replacement = f.entry(HASH_A, CHUNK);
        f.queue.enqueue(replacement, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        f.queue.onDownloadComplete(old);
        f.queue.onBusy(old, 250);
        f.queue.onBytesReceived(old, 0, CHUNK);
        assertEquals(1, f.queue.pendingCount());
        assertEquals(1, f.queue.inFlightCount());
        assertFalse(f.queue.onRetryableFailure(old, "late failure"));
    }

    @Test
    void verifiedProgressRestoresRetryAllowance() {
        Fixture f = new Fixture(1);
        var entry = f.entry(HASH_A, 2 * CHUNK);
        f.queue.enqueue(entry, SceneAssetDownloadQueue.Priority.PREFETCH);
        for (int i = 0; i < 3; i++) assertTrue(f.queue.onRetryableFailure(entry, "timeout"));
        f.registry.confirmBytes(entry, CHUNK);
        f.queue.onBytesCommitted(entry, CHUNK);
        assertTrue(f.queue.onRetryableFailure(entry, "timeout after progress"));
        assertTrue(f.failures.reasons.isEmpty());
    }

    @Test
    void oneTicketFillsTheWholeWindowWithContiguousOffsets() {
        Fixture f = new Fixture(4);
        f.queue.enqueue(f.entry(HASH_A, 8 * CHUNK), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        assertEquals(List.of(0L, CHUNK, 2 * CHUNK, 3 * CHUNK), offsets(f.sender));
        assertEquals(4, f.queue.inFlightCount());
    }

    @Test
    void windowDoesNotGrowWhileNothingIsReceived() {
        Fixture f = new Fixture(4);
        f.queue.enqueue(f.entry(HASH_A, 8 * CHUNK), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        for (int i = 0; i < 5; i++) {
            f.clock.advanceMillis(10);
            f.queue.tick();
        }
        assertEquals(4, f.sender.sent.size(), "没有回包时窗口不得继续膨胀");
    }

    /**
     * 报告 §3.3 的接缝回归：下一片只在「接收」时推进，与写盘确认无关。
     *
     * <p>这里刻意<b>不</b>调用 {@code registry.confirmBytes}——如果实现退回到「等落盘
     * 确认再发下一片」，这条断言会立刻失败。</p>
     */
    @Test
    void receivingAChunkRefillsTheWindowWithoutWaitingForDiskCommit() {
        Fixture f = new Fixture(4);
        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 8 * CHUNK);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        f.accept(asset, 0L, CHUNK);

        assertEquals(0L, asset.confirmedBytes(), "本用例的前提就是写盘尚未确认");
        assertEquals(5, f.sender.sent.size());
        assertEquals(4 * CHUNK, f.sender.sent.get(4).chunkOffset());
    }

    @Test
    void inFlightNeverExceedsTheConfiguredWindow() {
        Fixture f = new Fixture(4);
        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 100 * CHUNK);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        int peak = 0;
        for (long offset = 0; offset < 100 * CHUNK; offset += CHUNK) {
            f.accept(asset, offset, CHUNK);
            peak = Math.max(peak, f.queue.inFlightCount());
        }
        assertTrue(peak <= 4, "窗口上限被突破：" + peak);
    }

    @Test
    void finalShortChunkStopsExactlyAtTotalSize() {
        Fixture f = new Fixture(4);
        long totalSize = 3 * CHUNK + 100L;
        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, totalSize);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        assertEquals(List.of(0L, CHUNK, 2 * CHUNK, 3 * CHUNK), offsets(f.sender));
        assertEquals(100, f.sender.sent.get(3).chunkSize(), "末片必须是短尾，不能按 64 KiB 请求");
        f.accept(asset, 3 * CHUNK, 100L);

        assertEquals(4, f.sender.sent.size(), "越过 totalSize 不得再发出任何请求");
    }

    @Test
    void timeoutRewindsTheWholeWindowToTheAcceptedPrefix() {
        Fixture f = new Fixture(4);
        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 8 * CHUNK);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        f.accept(asset, 0L, CHUNK);
        f.accept(asset, CHUNK, CHUNK);
        int before = f.sender.sent.size();
        long lastRequestId = f.sender.sent.get(before - 1).requestId();

        f.clock.advanceMillis(1500);
        f.queue.tick();
        f.clock.advanceMillis(150);
        f.queue.tick();

        assertEquals(before + 4, f.sender.sent.size(), "整窗回退后必须重新填满窗口");
        assertEquals(2 * CHUNK, f.sender.sent.get(before).chunkOffset(),
                "回退点必须是已接收前缀，而不是从头再来");
        assertTrue(f.sender.sent.get(before).requestId() > lastRequestId,
                "回退重发必须使用更大的 requestId，否则会被服务端判成陈旧请求");
        assertTrue(f.failures.reasons.isEmpty());
    }

    @Test
    void lateDuplicateAfterAResetStillReleasesItsWindowSlot() {
        Fixture f = new Fixture(2);
        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 8 * CHUNK);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        // 上一窗的两片迟到到达：写盘流水线会判 DUPLICATE，但窗口槽位同样要归还。
        f.accept(asset, 0L, CHUNK);
        f.accept(asset, CHUNK, CHUNK);

        assertEquals(2, f.queue.inFlightCount());
        assertTrue(f.sender.sent.size() >= 4, "归还槽位后应继续补片");
    }

    @Test
    void busyRewindsTheWindowWithoutConsumingRetries() {
        Fixture f = new Fixture(4);
        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 8 * CHUNK);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        assertEquals(4, f.queue.inFlightCount());

        f.queue.onBusy(asset, 50);
        assertEquals(0, f.queue.inFlightCount(), "BUSY 必须整窗回退，否则槽位永远占着");

        f.clock.advanceMillis(60);
        f.queue.tick();

        assertTrue(f.failures.reasons.isEmpty(), "BUSY 是正常背压，不是失败");
        assertEquals(4, f.queue.inFlightCount());
        assertEquals(0L, f.sender.sent.get(4).chunkOffset());
    }

    @Test
    void higherPriorityTicketTakesTheWindowSlotsFirst() {
        Fixture f = new Fixture(4);
        f.sender.canSend = false;
        f.queue.enqueue(f.entry(HASH_A, 8 * CHUNK), SceneAssetDownloadQueue.Priority.PREFETCH);
        f.queue.enqueue(f.entry(HASH_B, 8 * CHUNK), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        f.sender.canSend = true;
        f.queue.tick();

        assertEquals(4, f.sender.sent.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(HASH_B, f.sender.sent.get(i).sha256(),
                    "窗口槽位应被最高优先级的票先占满");
        }
    }

    @Test
    void resetAllDropsEveryOutstandingRequest() {
        Fixture f = new Fixture(4);
        f.queue.enqueue(f.entry(HASH_A, 8 * CHUNK), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        assertEquals(4, f.queue.inFlightCount());

        f.queue.resetAll();
        assertEquals(0, f.queue.inFlightCount());
        assertEquals(0, f.queue.pendingCount());
        f.queue.tick();
        assertEquals(4, f.sender.sent.size(), "重置后不得再发出任何请求");
    }
}
