package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单一生命周期链的契约。
 *
 * <p>这里覆盖的是重构直接针对的那几个缺陷：同一个 hash 不能同时存在下载与磁盘加载两条链路；
 * 下载完成后再次请求不能启动第二次下载；重置后的陈旧回调不能改状态也不能通知任何人；
 * 等待者必须恰好被触发一次。</p>
 */
class AssetTransferRegistryTest {
    private static final String HASH = "b".repeat(64);

    private static final class Recorder implements Consumer<SceneAssetCodec.AssetData> {
        final List<SceneAssetCodec.AssetData> calls = new ArrayList<>();

        @Override
        public void accept(SceneAssetCodec.AssetData data) {
            calls.add(data);
        }
    }

    @Test
    void firstAcquireStartsDownloadWhenNoDiskCacheExists() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        AssetTransferRegistry.Acquire acquire =
                registry.acquire(HASH, 1024L, false, new Recorder());

        assertEquals(AssetTransferRegistry.Action.START_DOWNLOAD, acquire.action());
        assertNotNull(acquire.entry());
        assertEquals(HASH, acquire.entry().sha256());
        assertEquals(1024L, acquire.entry().totalSize());
    }

    @Test
    void firstAcquirePrefersDiskLoadWhenCacheExists() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        assertEquals(AssetTransferRegistry.Action.START_DISK_LOAD,
                registry.acquire(HASH, 1024L, true, new Recorder()).action());
    }

    @Test
    void secondAcquireJoinsTheExistingChainInsteadOfStartingAnother() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        Recorder first = new Recorder();
        Recorder second = new Recorder();

        AssetTransferRegistry.Acquire a = registry.acquire(HASH, 1024L, false, first);
        AssetTransferRegistry.Acquire b = registry.acquire(HASH, 1024L, false, second);

        assertEquals(AssetTransferRegistry.Action.START_DOWNLOAD, a.action());
        assertEquals(AssetTransferRegistry.Action.JOINED, b.action(),
                "同一个 hash 第二次请求必须加入既有链路，不能另起一条");
        assertSame(a.entry(), b.entry());
        assertEquals(1, registry.activeCount());
    }

    @Test
    void invalidDescriptorNotifiesImmediatelyAndRegistersNothing() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        Recorder recorder = new Recorder();

        AssetTransferRegistry.Acquire acquire = registry.acquire("", 1024L, false, recorder);

        assertEquals(AssetTransferRegistry.Action.REJECTED, acquire.action());
        assertNull(acquire.entry());
        assertEquals(1, recorder.calls.size());
        assertNull(recorder.calls.get(0));
        assertEquals(0, registry.activeCount());
    }

    @Test
    void successDetachesWaitersExactlyOnceAndRemovesTheEntry() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        Recorder waiter = new Recorder();
        AssetTransferRegistry.AssetEntry entry =
                registry.acquire(HASH, 1024L, false, waiter).entry();

        List<Consumer<SceneAssetCodec.AssetData>> detached = registry.completeSuccess(entry);
        assertEquals(1, detached.size(), "等待者必须被摘取");
        assertNull(registry.find(HASH), "成功后条目退场，由内存缓存接管");

        // 再次摘取不得重复返回同一批等待者——否则等待者会被触发两次。
        assertTrue(registry.detachWaiters(entry).isEmpty());

        // 成功之后的再次请求是一条全新链路。
        assertEquals(AssetTransferRegistry.Action.START_DOWNLOAD,
                registry.acquire(HASH, 1024L, false, new Recorder()).action());
    }

    @Test
    void failureKeepsTheEntryAndAllowsRetryToResetIt() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        AssetTransferRegistry.AssetEntry entry =
                registry.acquire(HASH, 1024L, false, new Recorder()).entry();
        registry.confirmBytes(entry, 512L);
        registry.recordAttempt(entry);

        List<Consumer<SceneAssetCodec.AssetData>> detached = registry.completeFailure(entry);
        assertEquals(1, detached.size());
        assertSame(entry, registry.find(HASH), "失败条目保留下来以便诊断");
        assertEquals(AssetTransferRegistry.State.FAILED, entry.state());

        // 重试必须使用新身份，防止旧写线程回调操作新下载。
        AssetTransferRegistry.Acquire retry = registry.acquire(HASH, 1024L, false, new Recorder());
        assertEquals(AssetTransferRegistry.Action.START_DOWNLOAD, retry.action());
        assertNotSame(entry, retry.entry());
        assertEquals(0L, retry.entry().confirmedBytes());
        assertEquals(0, retry.entry().attempts());
        assertEquals(0L, retry.entry().acceptedBytes());
        assertFalse(registry.confirmBytes(entry, 900));
        assertFalse(registry.transition(entry, AssetTransferRegistry.State.VERIFYING));
        assertTrue(registry.completeFailure(entry).isEmpty());
        assertSame(retry.entry(), registry.find(HASH));
    }

    @Test
    void staleCallbackAfterResetCannotChangeStateOrNotify() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        Recorder waiter = new Recorder();
        AssetTransferRegistry.AssetEntry entry =
                registry.acquire(HASH, 1024L, false, waiter).entry();
        long entryGeneration = entry.generation();

        registry.invalidateAll();

        assertFalse(registry.isCurrent(entryGeneration), "刷新后旧世代即失效");
        assertFalse(registry.transition(entry, AssetTransferRegistry.State.VERIFYING));
        assertFalse(registry.confirmBytes(entry, 512L));
        assertTrue(registry.completeSuccess(entry).isEmpty(),
                "过期回调不得摘取等待者，否则会把新链路的等待者一起通知掉");
        assertTrue(registry.completeFailure(entry).isEmpty());
        assertEquals(0L, entry.confirmedBytes(), "过期回调不得改动进度");
        assertEquals(0, waiter.calls.size(), "过期回调不得通知任何人");
    }

    @Test
    void resetHandsBackWaitersSoTheyCanBeNotified() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        Recorder waiter = new Recorder();
        registry.acquire(HASH, 1024L, false, waiter);

        List<AssetTransferRegistry.AssetEntry> invalidated = registry.invalidateAll();
        assertEquals(1, invalidated.size());

        // 重置路径必须把等待者交还给调用方去通知 null：prepareMesh 的 future 依赖这个回调，
        // 静默丢弃会让加载页永远等下去。
        List<Consumer<SceneAssetCodec.AssetData>> orphaned =
                registry.detachWaiters(invalidated.get(0));
        assertEquals(1, orphaned.size());
        AssetTransferRegistry.fireWaiters(orphaned, null);
        assertEquals(1, waiter.calls.size());
        assertNull(waiter.calls.get(0));
    }

    @Test
    void removeIsIdentityChecked() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        AssetTransferRegistry.AssetEntry entry =
                registry.acquire(HASH, 1024L, false, new Recorder()).entry();

        assertTrue(registry.remove(entry));
        assertFalse(registry.remove(entry), "同一对象第二次移除必须失败，不能影响后来者");
        assertNull(registry.find(HASH));
    }

    @Test
    void confirmedBytesOnlyMoveForward() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        AssetTransferRegistry.AssetEntry entry =
                registry.acquire(HASH, 4096L, false, new Recorder()).entry();

        assertTrue(registry.confirmBytes(entry, 2048L));
        // 回退值仍被接受（链路有效），但已确认前缀只增不减。
        assertTrue(registry.confirmBytes(entry, 1024L));
        assertEquals(2048L, entry.confirmedBytes(), "已确认前缀不得回退");
        assertEquals(2048L, entry.remainingBytes());
    }

    @Test
    void waiterExceptionsDoNotStopOtherWaiters() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        registry.acquire(HASH, 1024L, false, data -> {
            throw new IllegalStateException("boom");
        });
        Recorder survivor = new Recorder();
        registry.acquire(HASH, 1024L, false, survivor);
        AssetTransferRegistry.AssetEntry entry = registry.find(HASH);

        List<Consumer<SceneAssetCodec.AssetData>> detached = registry.completeSuccess(entry);
        assertEquals(2, detached.size());
        assertDoesNotThrow(() -> AssetTransferRegistry.fireWaiters(detached, null));
        assertEquals(1, survivor.calls.size(), "一个等待者抛异常不能影响其余等待者");
    }
}
