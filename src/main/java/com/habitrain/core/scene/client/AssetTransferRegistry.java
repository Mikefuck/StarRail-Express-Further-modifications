package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 场景资产的单一生命周期链：每个 hash 在任意时刻只有一条
 * {@code 排队 → 下载 → 校验/解码 → 可用/失败} 链路。
 *
 * <p>此前 {@code activeDownloads} 与 {@code diskLoadWaiters} 是两张互相不知道对方存在的表：
 * 同一个 hash 可以一边在下载、一边又在从磁盘加载；而且下载完成前就把条目从
 * {@code activeDownloads} 移除了，在「已收完最后一片」到「后台校验结束」之间再次请求
 * 会启动第二次下载。这里把两者合并成一张 entries 表，让「谁在负责推进这个 hash」
 * 只有一个答案。</p>
 *
 * <p><b>不依赖 Minecraft</b>：唯一的 IO 由调用方（{@link SceneAssetCache}）在 IO 线程上执行，
 * 本类只持有状态与等待者，因此可以在单测里完整覆盖状态机。</p>
 *
 * <p><b>加锁规则</b>：{@link #lock} 只保护 O(1) 的 map/字段操作，绝不包裹 IO、绝不在持锁时
 * 调用调用方传入的回调。等待者一律先在锁内复制、再在锁外触发。</p>
 */
public final class AssetTransferRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssetTransferRegistry.class.getSimpleName());

    public enum State {
        /** 已登记，等待 IO 线程开始（新建、磁盘加载或续传校验）。 */
        QUEUED,
        /** 正在接收分片。 */
        DOWNLOADING,
        /** 分片收齐，正在做整文件 SHA-256 校验。 */
        VERIFYING,
        /** 校验通过，正在解压/解码为 AssetData。 */
        DECODING,
        /** 已就绪（内存缓存中）。 */
        AVAILABLE,
        /** 失败终态；再次 acquire 会创建新条目以 QUEUED 重试。 */
        FAILED
    }

    /** 调用方在 acquire 之后应当执行的动作。 */
    public enum Action {
        /** 已有链路在推进，等待者已挂上，调用方什么都不用做。 */
        JOINED,
        /** 需要发起分片下载（从 {@code entry.confirmedBytes()} 开始）。 */
        START_DOWNLOAD,
        /** 需要从磁盘缓存加载。 */
        START_DISK_LOAD,
        /** 请求被拒绝（描述符非法等），等待者已用 null 结束。 */
        REJECTED
    }

    /** 一次 acquire 的结果。 */
    public record Acquire(AssetEntry entry, Action action) {}

    /**
     * 补丁链路的上下文：这条链路下的不是资产本身，而是"把 base 变成 target"的那枚补丁。
     *
     * <p>有它，链路两端才知道完成时该做什么——解码 base、应用补丁、把结果按 target 的 hash
     * 交给等待者；失败时把等待者重新挂到 target 的整文件下载上。</p>
     */
    public record PatchTarget(String baseSha256, SceneAssetDescriptor target) {}

    /**
     * 单个 hash 的传输状态。字段除 {@code waiters}/{@code attempts} 外均可跨线程读，
     * 写操作只在 {@link #lock} 内或由单条 IO 链完成。
     */
    public static final class AssetEntry {
        private final String sha256;
        private final long totalSize;
        private final List<Consumer<SceneAssetCodec.AssetData>> waiters = new ArrayList<>();
        /** 非 null 表示这条链路在下一枚增量补丁；null 表示下的是资产本身。 */
        private PatchTarget patchTarget;

        private State state = State.QUEUED;
        private long generation;
        private long transferId;
        /**
         * 已确认落在磁盘上的连续前缀。
         *
         * <p>写盘流水线在写线程上推进它，而 {@link SceneAssetDownloadQueue} 在无锁状态下
         * 读它来决定下一片的请求偏移（{@code sendLocked}），因此必须是 volatile：
         * 否则客户端线程可能拿着一个陈旧的偏移反复请求同一片。</p>
         */
        private volatile long confirmedBytes;
        /**
         * 已接收（已转交写线程）但未必已落盘的连续前缀。
         *
         * <p>有界窗口下同一时刻可能有多片在途，请求偏移不能再由 {@code confirmedBytes}
         * 推导——那会让后一片永远追着已经落盘的前缀跑。写盘流水线在客户端线程推进它
         * （{@code offer}），失败回退时退回 {@code confirmedBytes}；下载队列据此决定
         * 整窗回退的起点。</p>
         */
        private volatile long acceptedBytes;
        private int attempts;
        private long lastUsedAtMillis;

        AssetEntry(String sha256, long totalSize, long generation, long transferId) {
            this.sha256 = sha256;
            this.totalSize = totalSize;
            this.generation = generation;
            this.transferId = transferId;
        }

        public String sha256() { return sha256; }
        public long totalSize() { return totalSize; }
        public PatchTarget patchTarget() { return patchTarget; }
        public State state() { return state; }
        public long generation() { return generation; }
        public long transferId() { return transferId; }
        public long confirmedBytes() { return confirmedBytes; }
        public long acceptedBytes() { return acceptedBytes; }
        public int attempts() { return attempts; }
        public long lastUsedAtMillis() { return lastUsedAtMillis; }

        /** 距离完成还差多少字节，用于诊断与预算。 */
        public long remainingBytes() { return Math.max(0L, totalSize - confirmedBytes); }
    }

    private final Object lock = new Object();
    private final Map<String, AssetEntry> entries = new HashMap<>();
    private long generation;
    private long transferCounter;

    /** 每次 reset/JOIN/DISCONNECT 递增；旧的异步回调据此判断自己是否已经过期。 */
    public long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /** 作废当前所有链路，返回被作废的条目的快照供调用方清理资源。 */
    public List<AssetEntry> invalidateAll() {
        synchronized (lock) {
            generation++;
            List<AssetEntry> snapshot = new ArrayList<>(entries.values());
            entries.clear();
            return snapshot;
        }
    }

    /** 该回调是否仍然属于当前链路世代。 */
    public boolean isCurrent(long entryGeneration) {
        synchronized (lock) {
            return entryGeneration == generation;
        }
    }

    /**
     * 登记或加入一个 hash 的传输链路。
     *
     * <p>{@code diskCacheExists} 决定新链路的第一步：磁盘有成品就走 {@code START_DISK_LOAD}，
     * 否则走 {@code START_DOWNLOAD}。</p>
     */
    public Acquire acquire(String sha256, long totalSize, boolean diskCacheExists,
                           Consumer<SceneAssetCodec.AssetData> waiter) {
        return acquire(sha256, totalSize, diskCacheExists, null, waiter);
    }

    /**
     * 带补丁上下文的登记：{@code patchTarget} 非 null 表示这条链路在下一枚增量补丁，
     * 完成与失败的处理都与资产链路不同（见 {@link PatchTarget}）。
     */
    public Acquire acquire(String sha256, long totalSize, boolean diskCacheExists,
                           PatchTarget patchTarget, Consumer<SceneAssetCodec.AssetData> waiter) {
        if (sha256 == null || sha256.isBlank() || totalSize <= 0L) {
            if (waiter != null) invokeQuietly(waiter, null);
            return new Acquire(null, Action.REJECTED);
        }

        synchronized (lock) {
            AssetEntry entry = entries.get(sha256);
            if (entry != null && entry.state != State.FAILED) {
                // 已有链路在推进：只挂等待者。这就是「同一 hash 只有一条链」的落点。
                addWaiter(entry, waiter);
                return new Acquire(entry, Action.JOINED);
            }

            // A retry needs a new identity: queued IO callbacks still retain the old entry.
            entry = new AssetEntry(sha256, totalSize, generation, nextTransferId());
            entries.put(sha256, entry);
            // 每次登记都以本次的上下文为准。
            entry.patchTarget = patchTarget;
            addWaiter(entry, waiter);
            entry.lastUsedAtMillis = System.currentTimeMillis();
            return new Acquire(entry, diskCacheExists ? Action.START_DISK_LOAD : Action.START_DOWNLOAD);
        }
    }

    /** 把条目推进到新状态。仅在该条目的链路仍然有效时生效。 */
    public boolean transition(AssetEntry entry, State state) {
        if (entry == null) return false;
        synchronized (lock) {
            if (!isLiveUnderLock(entry)) return false;
            entry.state = state;
            return true;
        }
    }

    /** 记录已确认写入磁盘的字节数（连续前缀）。 */
    public boolean confirmBytes(AssetEntry entry, long confirmedBytes) {
        if (entry == null) return false;
        synchronized (lock) {
            if (!isLiveUnderLock(entry)) return false;
            entry.confirmedBytes = Math.max(entry.confirmedBytes, confirmedBytes);
            entry.lastUsedAtMillis = System.currentTimeMillis();
            return true;
        }
    }

    /**
     * 记录已接收（已转交写线程）的连续前缀。
     *
     * <p>与 {@link #confirmBytes} 不同，这里用<b>赋值</b>而不是取最大值：分片被写线程拒绝时
     * 调用方要把这个游标退回 {@code confirmedBytes}，取最大值会让回退失效。
     * 同一 hash 的所有 {@code offer}/{@code rollbackAccepted} 都发生在客户端线程上，
     * 因此「后写覆盖先写」不会丢失更高的值。</p>
     */
    public boolean confirmAccepted(AssetEntry entry, long acceptedBytes) {
        if (entry == null) return false;
        synchronized (lock) {
            if (!isLiveUnderLock(entry)) return false;
            entry.acceptedBytes = Math.max(entry.confirmedBytes, acceptedBytes);
            return true;
        }
    }

    /** 增加一次尝试计数（超时/CRC 失败重试）。返回新的尝试次数。 */
    public int recordAttempt(AssetEntry entry) {
        if (entry == null) return 0;
        synchronized (lock) {
            if (!isLiveUnderLock(entry)) return 0;
            return ++entry.attempts;
        }
    }

    /**
     * 成功结束链路：置 AVAILABLE、摘取全部等待者并移除条目。
     *
     * <p>返回的等待者列表由调用方在锁外触发，且只会被触发一次。</p>
     */
    public List<Consumer<SceneAssetCodec.AssetData>> completeSuccess(AssetEntry entry) {
        if (entry == null) return List.of();
        synchronized (lock) {
            if (!isLiveUnderLock(entry)) return List.of();
            entry.state = State.AVAILABLE;
            List<Consumer<SceneAssetCodec.AssetData>> waiters = detachWaitersUnderLock(entry);
            // 成功后由内存缓存接管「已就绪」这一事实，条目本身退场。
            entries.remove(entry.sha256, entry);
            return waiters;
        }
    }

    /**
     * 失败结束链路：置 FAILED、摘取全部等待者并保留条目。
     *
     * <p>保留条目是为了让诊断能看到「这个 hash 失败过」；下次 acquire 会以新条目替换它。
     * 条目数量以本次会话请求过的不同 hash 为上界，并在 resetSession 时清空。</p>
     */
    public List<Consumer<SceneAssetCodec.AssetData>> completeFailure(AssetEntry entry) {
        if (entry == null) return List.of();
        synchronized (lock) {
            if (!isLiveUnderLock(entry)) return List.of();
            entry.state = State.FAILED;
            return detachWaitersUnderLock(entry);
        }
    }

    /**
     * 身份校验的移除：只有当 map 里当前登记的仍是同一个对象时才移除。
     *
     * <p>此前 {@code activeDownloads.remove(sha256)} 不带身份校验，一次被取代的旧尝试
     * 可以把它之后新建的会话踢掉。</p>
     */
    public boolean remove(AssetEntry entry) {
        if (entry == null) return false;
        synchronized (lock) {
            return entries.remove(entry.sha256, entry);
        }
    }

    /** 摘取等待者但不改状态（用于调用方自行决定后续状态）。 */
    public List<Consumer<SceneAssetCodec.AssetData>> detachWaiters(AssetEntry entry) {
        if (entry == null) return List.of();
        synchronized (lock) {
            return detachWaitersUnderLock(entry);
        }
    }

    /** 当前全部条目的快照，供诊断与预算遍历。 */
    public List<AssetEntry> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(entries.values());
        }
    }

    /** 按 hash 查找条目；不存在返回 null。 */
    public AssetEntry find(String sha256) {
        if (sha256 == null) return null;
        synchronized (lock) {
            return entries.get(sha256);
        }
    }

    /** 处于活跃传输（尚未就绪也未失败）的条目数量。 */
    public int activeCount() {
        synchronized (lock) {
            int count = 0;
            for (AssetEntry entry : entries.values()) {
                if (entry.state != State.AVAILABLE && entry.state != State.FAILED) count++;
            }
            return count;
        }
    }

    /** 清空全部条目（不递增世代；供 shutdown 使用）。 */
    public List<AssetEntry> drain() {
        synchronized (lock) {
            List<AssetEntry> snapshot = new ArrayList<>(entries.values());
            entries.clear();
            return snapshot;
        }
    }

    /** 在调用方给定的时机触发等待者，异常只记日志不打断其余等待者。 */
    public static void fireWaiters(List<Consumer<SceneAssetCodec.AssetData>> waiters,
                                   SceneAssetCodec.AssetData data) {
        for (Consumer<SceneAssetCodec.AssetData> waiter : waiters) {
            invokeQuietly(waiter, data);
        }
    }

    private static void invokeQuietly(Consumer<SceneAssetCodec.AssetData> waiter,
                                      SceneAssetCodec.AssetData data) {
        try {
            waiter.accept(data);
        } catch (Throwable t) {
            LOGGER.warn("场景资产完成回调异常", t);
        }
    }

    /** 调用方必须已持有 {@link #lock}。 */
    private boolean isLiveUnderLock(AssetEntry entry) {
        return entries.get(entry.sha256) == entry && entry.generation == generation;
    }

    /** 调用方必须已持有 {@link #lock}。 */
    private static void addWaiter(AssetEntry entry, Consumer<SceneAssetCodec.AssetData> waiter) {
        if (waiter != null) entry.waiters.add(waiter);
    }

    /** 调用方必须已持有 {@link #lock}。 */
    private static List<Consumer<SceneAssetCodec.AssetData>> detachWaitersUnderLock(AssetEntry entry) {
        if (entry.waiters.isEmpty()) return List.of();
        List<Consumer<SceneAssetCodec.AssetData>> copy = List.copyOf(entry.waiters);
        entry.waiters.clear();
        return copy;
    }

    /** 调用方必须已持有 {@link #lock}。 */
    private long nextTransferId() {
        long id = ++transferCounter;
        return id == 0L ? ++transferCounter : id;
    }
}
