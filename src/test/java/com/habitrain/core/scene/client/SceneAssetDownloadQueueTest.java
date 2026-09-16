package com.habitrain.core.scene.client;

import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下载队列的调度契约：严格单在途、优先级、超时重试、有界退避、通道不可用时不消耗重试。
 *
 * <p>这批行为正是「被拒请求永久等待」的修复点，因此用假时钟逐条固定下来。</p>
 */
class SceneAssetDownloadQueueTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

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

    /** 用真实注册表建条目，这样也能用 confirmBytes/confirmAccepted 推进两个游标。 */
    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final RecordingSender sender = new RecordingSender();
        final RecordingFailures failures = new RecordingFailures();
        final AssetTransferRegistry registry = new AssetTransferRegistry();
        final SceneAssetDownloadQueue queue;

        /** 窗口固定为 1：这批断言固定的是「单在途」时代的对外契约，必须逐条保持。 */
        Fixture(int timeoutMs, int maxRetries) {
            this(timeoutMs, maxRetries, 1);
        }

        Fixture(int timeoutMs, int maxRetries, int windowChunks) {
            queue = new SceneAssetDownloadQueue(clock::current, sender, failures,
                    () -> timeoutMs, () -> maxRetries, () -> windowChunks, 100, 1000);
        }

        AssetTransferRegistry.AssetEntry entry(String sha256, long totalSize) {
            return registry.acquire(sha256, totalSize, false, null).entry();
        }

        /** 分片送达：同时归还窗口槽位并推进已接收前缀。 */
        void accept(AssetTransferRegistry.AssetEntry entry, long offset, long length) {
            registry.confirmAccepted(entry, offset + length);
            queue.onBytesReceived(entry, offset, length);
        }

        void complete(AssetTransferRegistry.AssetEntry entry) {
            registry.confirmBytes(entry, entry.totalSize());
        }
    }

    @Test
    void onlyOneChunkRequestIsInFlightAcrossTheWholeConnection() {
        Fixture f = new Fixture(1000, 3);

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        f.queue.enqueue(f.entry(HASH_B, 4096), SceneAssetDownloadQueue.Priority.ADDITIONAL_RUNTIME);

        assertEquals(1, f.sender.sent.size(), "同一时刻只允许一个在途分片请求");
        assertEquals(HASH_A, f.sender.sent.get(0).sha256());
        assertTrue(f.queue.hasInFlight());
    }

    @Test
    void completingOneAssetLetsTheNextBeServed() {
        Fixture f = new Fixture(1000, 3);

        AssetTransferRegistry.AssetEntry first = f.entry(HASH_A, 4096);
        f.queue.enqueue(first, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        f.queue.enqueue(f.entry(HASH_B, 4096), SceneAssetDownloadQueue.Priority.ADDITIONAL_RUNTIME);
        assertEquals(1, f.sender.sent.size());

        f.complete(first);
        f.accept(first, 0L, 4096L);

        assertEquals(2, f.sender.sent.size());
        assertEquals(HASH_B, f.sender.sent.get(1).sha256(), "前一个资产完成后才服务下一个");
    }

    @Test
    void higherPriorityLaneIsServedFirst() {
        Fixture f = new Fixture(1000, 3);
        f.sender.canSend = false; // 先让两票都排队

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PREFETCH);
        f.queue.enqueue(f.entry(HASH_B, 4096), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        assertTrue(f.sender.sent.isEmpty(), "通道不可用时不应发送");

        f.sender.canSend = true;
        f.queue.tick();

        assertEquals(1, f.sender.sent.size());
        assertEquals(HASH_B, f.sender.sent.get(0).sha256(), "主背景必须先于预取被服务");
    }

    @Test
    void hintPriorityPromotesAQueuedAsset() {
        Fixture f = new Fixture(1000, 3);
        f.sender.canSend = false;

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PREFETCH);
        f.queue.enqueue(f.entry(HASH_B, 4096), SceneAssetDownloadQueue.Priority.STAGING_EDITOR);
        f.queue.hintPriority(HASH_A, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        f.sender.canSend = true;
        f.queue.tick();

        assertEquals(HASH_A, f.sender.sent.get(0).sha256(), "被提升的资产应插到最前");
    }

    @Test
    void unusableChannelDoesNotConsumeRetries() {
        Fixture f = new Fixture(1000, 1);
        f.sender.canSend = false;

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        for (int i = 0; i < 20; i++) {
            f.clock.advanceMillis(5000);
            f.queue.tick();
        }
        assertTrue(f.failures.reasons.isEmpty(), "通道不可用不是失败，不得消耗重试预算");

        f.sender.canSend = true;
        f.queue.tick();
        assertEquals(1, f.sender.sent.size(), "通道恢复后必须照常发出请求");
    }

    @Test
    void timeoutResendsWithANewRequestId() {
        Fixture f = new Fixture(1000, 3);

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        assertEquals(1, f.sender.sent.size());
        long firstRequestId = f.sender.sent.get(0).requestId();

        f.clock.advanceMillis(1500);
        f.queue.tick(); // 判定超时，排入退避窗口
        assertEquals(1, f.sender.sent.size(), "退避窗口内不立即重发");

        f.clock.advanceMillis(150); // 越过 100ms 基础退避
        f.queue.tick();

        assertEquals(2, f.sender.sent.size(), "超时后必须重试，而不是永久等待");
        assertTrue(f.sender.sent.get(1).requestId() > firstRequestId,
                "重试必须使用新的 requestId，服务端据此识别陈旧请求");
    }

    @Test
    void backoffGrowsWithConsecutiveTimeouts() {
        Fixture f = new Fixture(1000, 3);

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);

        // 第一次超时 -> 基础退避 100ms
        f.clock.advanceMillis(1500);
        f.queue.tick();
        f.clock.advanceMillis(150);
        f.queue.tick();
        assertEquals(2, f.sender.sent.size());

        // 第二次超时 -> 退避翻倍到 200ms
        f.clock.advanceMillis(1500);
        f.queue.tick();
        f.clock.advanceMillis(150);
        f.queue.tick();
        assertEquals(2, f.sender.sent.size(), "150ms 还在 200ms 退避窗口内，不得重发");

        f.clock.advanceMillis(100);
        f.queue.tick();
        assertEquals(3, f.sender.sent.size(), "越过翻倍后的退避窗口才重发");
    }

    @Test
    void retryExhaustionReportsFailureOnce() {
        Fixture f = new Fixture(1000, 1);

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        for (int i = 0; i < 10; i++) {
            f.clock.advanceMillis(5000);
            f.queue.tick();
        }

        assertEquals(1, f.failures.reasons.size(), "重试耗尽只能上报一次");
        assertTrue(f.failures.reasons.get(0).contains("请求超时"));
        assertFalse(f.queue.hasInFlight());
        assertEquals(0, f.queue.pendingCount());
    }

    @Test
    void busyReplyDoesNotConsumeRetries() {
        Fixture f = new Fixture(1000, 1);

        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 4096);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        f.queue.onBusy(asset, 50);

        assertTrue(f.failures.reasons.isEmpty(), "BUSY 是正常背压，不是失败");
        f.clock.advanceMillis(60);
        f.queue.tick();
        assertEquals(2, f.sender.sent.size(), "BUSY 之后应按提示退避并重试");
    }

    @Test
    void terminalFailureStopsTheAsset() {
        Fixture f = new Fixture(1000, 3);

        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 4096);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        f.queue.onTerminalFailure(asset, "服务端拒绝");

        assertEquals(1, f.failures.reasons.size());
        assertTrue(f.failures.reasons.get(0).contains("服务端拒绝"));
        assertEquals(0, f.queue.pendingCount());
    }

    @Test
    void resetAllDropsEverything() {
        Fixture f = new Fixture(1000, 3);
        f.sender.canSend = false;

        f.queue.enqueue(f.entry(HASH_A, 4096), SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        f.queue.enqueue(f.entry(HASH_B, 4096), SceneAssetDownloadQueue.Priority.PREFETCH);
        f.queue.resetAll();

        f.sender.canSend = true;
        f.queue.tick();
        assertTrue(f.sender.sent.isEmpty(), "重置后不得再发出任何陈旧请求");
        assertEquals(0, f.queue.pendingCount());
    }

    @Test
    void requestsCarryTheEntrysTransferIdAndFollowTheAcceptedPrefix() {
        Fixture f = new Fixture(1000, 3);

        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 3L * 65_536L);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        assertEquals(asset.transferId(), f.sender.sent.get(0).transferId());
        assertEquals(0L, f.sender.sent.get(0).chunkOffset());

        // 续传/已接收前缀推进后，下一片必须从那里继续，而不是重头再来。
        f.registry.confirmBytes(asset, 65_536L);
        f.accept(asset, 0L, 65_536L);

        assertEquals(65_536L, f.sender.sent.get(1).chunkOffset(), "续传必须从已接收前缀继续");
    }

    @Test
    void aTicketIsKeptUntilTheWriterFinishesSoLateFailuresStillFindIt() {
        Fixture f = new Fixture(1000, 3);

        AssetTransferRegistry.AssetEntry asset = f.entry(HASH_A, 4096L);
        f.queue.enqueue(asset, SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME);
        // 全部字节已接收，但写线程/整文件校验还没结束。
        f.accept(asset, 0L, 4096L);

        // 最后一片之后才暴露的写盘或整文件校验失败，仍必须能找到这条票。
        assertTrue(f.queue.onRetryableFailure(asset, "整文件校验失败"),
                "最后一片之后到达的失败必须仍能重新排队，否则下载会静默卡住");
        f.queue.onDownloadComplete(asset);
        assertEquals(0, f.queue.pendingCount());
    }
}
