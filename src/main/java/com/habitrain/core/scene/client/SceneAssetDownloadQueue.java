package com.habitrain.core.scene.client;

import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 客户端分片下载队列：按优先级调度，并以<b>有界窗口</b>允许同一时刻有多片在途。
 *
 * <p>此前客户端在收到一片后才请求下一片，且下一片请求必须等磁盘写入确认（每片一次客户端
 * 线程跳转），加载期吞吐被钉在约 3.8 MiB/s；在高延迟线路上更是退化成停等协议：
 * 100 ms RTT 下每 64 KiB 一个往返，上限只有 0.625 MiB/s。</p>
 *
 * <p>这里把「请求游标」与「已落盘前缀」彻底分开：请求偏移由 {@link Ticket#nextRequestOffset}
 * 推进（发出即推进），而磁盘侧的可信前缀由 {@code AssetEntry.confirmedBytes} 表达。两者的
 * 差值就是窗口允许的「已发出但尚未落盘」的深度，上限由 {@link #windowChunks} 给出。
 * 因此下一片请求不再依赖写盘完成，那一次客户端线程跳转也随之消失。</p>
 *
 * <p><b>失败一律按整窗回退</b>：超时、BUSY、CRC 失败、写盘失败都丢弃该资产的整窗在途记录，
 * 并把请求游标退回 {@code acceptedBytes}（已接收前缀）。因为 {@code requestId} 只增不减，
 * 服务端不会把回退重发的请求判成陈旧请求；重复到达的分片由写盘流水线按
 * {@code offset < acceptedOffset} 丢弃。</p>
 *
 * <p><b>加锁规则</b>：{@link #lock} 内只做 O(1) 的队列/映射操作（窗口上限为个位数，
 * 在途记录表最多 8 条，线性扫描是有意为之）。终态失败回调会一路走到等待者通知，
 * 所以内部方法只把失败累积到 sink 列表，公开方法在<b>释放锁之后</b>才触发。</p>
 *
 * <p><b>不依赖 Minecraft</b>：时钟、发包器、超时、重试次数与窗口大小全部由构造参数注入，
 * 因此窗口调度、超时回退与优先级可在单测里用假时钟完整覆盖。</p>
 */
public final class SceneAssetDownloadQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAssetDownloadQueue.class.getSimpleName());

    /** 越小越优先。同一泳道内严格 FIFO。 */
    public enum Priority {
        /** 当前主背景。 */
        PRIMARY_RUNTIME,
        /** 当前启用的附加背景。 */
        ADDITIONAL_RUNTIME,
        /** 预取（尚未启用但很可能用到）。 */
        PREFETCH,
        /** 编辑器预览与暂存检查。 */
        STAGING_EDITOR;

        public static final int COUNT = 4;
    }

    /** 发包通道；抽出来是为了让单测不依赖真实网络。 */
    public interface Sender {
        /** 对端是否支持该包（未安装模组 / 未到 play 阶段时为 false）。 */
        boolean canSend();

        void send(SceneAssetChunkRequestC2S request);
    }

    /** 尝试次数耗尽后的终态回调，保证在队列锁之外被调用。 */
    public interface FailureHandler {
        void onExhausted(AssetTransferRegistry.AssetEntry entry, String reason);
    }

    private static final class Ticket {
        final AssetTransferRegistry.AssetEntry entry;
        final String sha256;
        Priority priority;
        int lane;
        /** 早于该时刻不发送；0 表示立即可发。延迟重试时置为 now + backoff。 */
        long readyAtNanos;
        int requestId;
        /** 连续失败次数；有任何一片成功推进就清零。 */
        int attempts;
        long lastCommittedBytes;
        /** 是否仍停留在泳道里等待发送。分片全部发完后置 false，但票仍留着等回包。 */
        boolean queued;
        /** 下一个要请求的偏移；发出即推进。 */
        long nextRequestOffset;

        Ticket(AssetTransferRegistry.AssetEntry entry, Priority priority) {
            this.entry = entry;
            this.sha256 = entry.sha256();
            this.priority = priority;
        }
    }

    /** 已发出、尚未收到回包的一片。窗口上限是个位数，因此用一个线性表管理即可。 */
    private record Outstanding(Ticket ticket, long offset, long sentAtNanos) {}

    /** 一次同步块内产生的终态失败，需要在锁外触发。 */
    private record Exhausted(AssetTransferRegistry.AssetEntry entry, String reason) {}

    private final Object lock = new Object();
    private final ArrayDeque<Ticket>[] lanes;
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final List<Outstanding> outstanding = new ArrayList<>();
    private boolean probeParked;

    private final LongSupplier clockNanos;
    private final Sender sender;
    private final FailureHandler failureHandler;
    private final IntSupplier requestTimeoutMs;
    private final IntSupplier maxRetries;
    private final IntSupplier windowChunks;
    private final int backoffBaseMs;
    private final int backoffMaxMs;

    @SuppressWarnings("unchecked")
    public SceneAssetDownloadQueue(LongSupplier clockNanos, Sender sender, FailureHandler failureHandler,
                                   IntSupplier requestTimeoutMs, IntSupplier maxRetries,
                                   IntSupplier windowChunks, int backoffBaseMs, int backoffMaxMs) {
        this.clockNanos = clockNanos;
        this.sender = sender;
        this.failureHandler = failureHandler;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxRetries = maxRetries;
        this.windowChunks = windowChunks;
        this.backoffBaseMs = Math.max(1, backoffBaseMs);
        this.backoffMaxMs = Math.max(backoffBaseMs, backoffMaxMs);
        this.lanes = new ArrayDeque[Priority.COUNT];
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = new ArrayDeque<>();
        }
    }

    /** 登记一个待下载条目并尝试填满窗口。 */
    public void enqueue(AssetTransferRegistry.AssetEntry entry, Priority priority) {
        if (entry == null) return;
        List<Exhausted> failures = new ArrayList<>(2);
        synchronized (lock) {
            if (tickets.containsKey(entry.sha256())) return;
            Ticket ticket = new Ticket(entry, priority == null ? Priority.PREFETCH : priority);
            ticket.lane = ticket.priority.ordinal();
            ticket.queued = true;
            // 续传点在入队前已经写进 acceptedBytes/confirmedBytes，首片必须从那里开始。
            ticket.nextRequestOffset = clampCursor(entry, entry.acceptedBytes());
            ticket.lastCommittedBytes = entry.confirmedBytes();
            lanes[ticket.lane].addLast(ticket);
            tickets.put(ticket.sha256, ticket);
            pumpLocked(failures);
        }
        fire(failures);
    }

    /**
     * 提高某个待下载资产的优先级（例如预取中的资产变成当前主背景）。
     *
     * <p>只提升不降低：一个已经被运行路径需要的资产不会因为调用方改变主意而退回低优先级。</p>
     */
    public void hintPriority(String sha256, Priority priority) {
        if (sha256 == null || priority == null) return;
        List<Exhausted> failures = new ArrayList<>(2);
        synchronized (lock) {
            Ticket ticket = tickets.get(sha256);
            if (ticket == null || priority.ordinal() >= ticket.priority.ordinal()) return;
            boolean wasQueued = ticket.queued;
            if (wasQueued) lanes[ticket.lane].remove(ticket);
            ticket.priority = priority;
            ticket.lane = priority.ordinal();
            if (wasQueued) lanes[ticket.lane].addFirst(ticket);
            pumpLocked(failures);
        }
        fire(failures);
    }

    /**
     * 客户端线程：某一片已到达并被写盘流水线接收。释放一个窗口槽位并立刻补发下一片。
     *
     * <p>这取代了此前「等写盘确认再推进」的 {@code onBytesConfirmed}：请求游标只关心
     * 有没有收到，不关心有没有落盘，因此每片不再需要一次客户端线程跳转。</p>
     *
     * <p><b>重复分片同样要调用本方法。</b>整窗回退后重发的偏移往往已经被接收过，
     * 写盘流水线会判 {@code DUPLICATE}；若不把对应的在途记录摘掉，窗口会一直满着，
     * 整条链路只能靠超时脱困。</p>
     */
    public void onBytesReceived(AssetTransferRegistry.AssetEntry entry, long offset, long length) {
        if (entry == null) return;
        List<Exhausted> failures = new ArrayList<>(1);
        synchronized (lock) {
            Ticket ticket = tickets.get(entry.sha256());
            if (ticket == null || ticket.entry != entry) return;
            dropOutstandingLocked(ticket, offset);
            pumpLocked(failures);
        }
        fire(failures);
    }

    /**
     * 客户端线程：该资产的整文件校验与解码已结束，调度职责到此为止。
     *
     * <p><b>不能在「最后一片已接收」时就释放票</b>：分片的落盘与整文件校验都发生在写线程
     * 与 IO 线程上，最后一片到达之后仍可能因为 CRC/写盘/整文件哈希失败而需要重传。
     * 那时若票已经消失，{@code onRetryableFailure} 会找不到票而静默返回，下载永远停在
     * {@code VERIFYING} 之前的某个状态。</p>
     */
    public void onDownloadComplete(AssetTransferRegistry.AssetEntry entry) {
        if (entry == null) return;
        synchronized (lock) {
            Ticket ticket = tickets.get(entry.sha256());
            if (ticket == null || ticket.entry != entry) return;
            releaseLocked(ticket);
        }
    }

    /** Only verified disk progress resets retries; receiving corrupt bytes is not progress. */
    public void onBytesCommitted(AssetTransferRegistry.AssetEntry entry, long committedBytes) {
        synchronized (lock) {
            Ticket ticket = tickets.get(entry.sha256());
            if (ticket == null || ticket.entry != entry || committedBytes <= ticket.lastCommittedBytes) return;
            ticket.lastCommittedBytes = committedBytes;
            ticket.attempts = 0;
        }
    }

    /** 服务端返回 BUSY：整窗回退并按提示退避，不消耗重试次数。 */
    public void onBusy(AssetTransferRegistry.AssetEntry entry, int retryAfterMillis) {
        if (entry == null) return;
        List<Exhausted> failures = new ArrayList<>(2);
        synchronized (lock) {
            Ticket ticket = tickets.get(entry.sha256());
            if (ticket == null || ticket.entry != entry) return;
            long delayMs = Math.max(1L, Math.min(retryAfterMillis, backoffMaxMs));
            resetWindowLocked(ticket);
            requeueLocked(ticket, delayMs);
            pumpLocked(failures);
        }
        fire(failures);
    }

    /**
     * 可重试错误（超时、CRC 失败、分片缺口、写盘失败）。
     *
     * @return true 表示已重新排队；false 表示重试次数耗尽，条目已被判定失败。
     */
    public boolean onRetryableFailure(AssetTransferRegistry.AssetEntry entry, String reason) {
        if (entry == null) return false;
        List<Exhausted> failures = new ArrayList<>(2);
        boolean retrying;
        synchronized (lock) {
            Ticket ticket = tickets.get(entry.sha256());
            if (ticket == null || ticket.entry != entry) return false;
            Exhausted exhausted = consumeRetryLocked(ticket, reason);
            if (exhausted != null) {
                releaseLocked(ticket);
                failures.add(exhausted);
                retrying = false;
            } else {
                resetWindowLocked(ticket);
                requeueLocked(ticket, backoffMillis(ticket.attempts));
                retrying = true;
            }
            pumpLocked(failures);
        }
        fire(failures);
        return retrying;
    }

    /** 服务端明确拒绝：立即失败，不退避。 */
    public void onTerminalFailure(AssetTransferRegistry.AssetEntry entry, String reason) {
        if (entry == null) return;
        List<Exhausted> failures = new ArrayList<>(1);
        synchronized (lock) {
            Ticket ticket = tickets.get(entry.sha256());
            if (ticket == null || ticket.entry != entry) return;
            releaseLocked(ticket);
            failures.add(new Exhausted(entry, reason));
        }
        fire(failures);
    }

    /** 取消某个 hash 的下载（失败终态或用户关闭背景）。 */
    public void cancel(String sha256) {
        if (sha256 == null) return;
        List<Exhausted> failures = new ArrayList<>(1);
        synchronized (lock) {
            Ticket ticket = tickets.get(sha256);
            if (ticket == null) return;
            releaseLocked(ticket);
            pumpLocked(failures);
        }
        fire(failures);
    }

    /** 作废全部待下载与在途状态（重置会话时调用）。 */
    public void resetAll() {
        synchronized (lock) {
            for (ArrayDeque<Ticket> lane : lanes) lane.clear();
            tickets.clear();
            outstanding.clear();
            probeParked = false;
        }
    }

    /** 由客户端 tick 驱动：回退超时的整窗并推进队列。 */
    public void tick() {
        List<Exhausted> failures = new ArrayList<>(2);
        synchronized (lock) {
            long now = clockNanos.getAsLong();
            long timeoutNanos = Math.max(1, requestTimeoutMs.getAsInt()) * 1_000_000L;

            Set<Ticket> expired = null;
            for (Outstanding record : outstanding) {
                if (now - record.sentAtNanos() < timeoutNanos) continue;
                if (expired == null) expired = new HashSet<>(2);
                expired.add(record.ticket());
            }
            if (expired != null) {
                // 每个 ticket 一 tick 最多回退一次：整窗是原子回退，重复计数会凭空吃掉重试预算。
                for (Ticket ticket : expired) {
                    Exhausted exhausted = consumeRetryLocked(ticket, "请求超时");
                    if (exhausted != null) {
                        releaseLocked(ticket);
                        failures.add(exhausted);
                        continue;
                    }
                    resetWindowLocked(ticket);
                    requeueLocked(ticket, backoffMillis(ticket.attempts));
                }
            }
            pumpLocked(failures);
        }
        fire(failures);
    }

    /** 当前在途分片数（供诊断）。 */
    public int inFlightCount() {
        synchronized (lock) {
            return outstanding.size();
        }
    }

    /** 当前是否有在途请求（供诊断）。 */
    public boolean hasInFlight() {
        return inFlightCount() > 0;
    }

    /** 待发送的资产数量（供诊断）。 */
    public int pendingCount() {
        synchronized (lock) {
            return tickets.size();
        }
    }

    // ---- 内部：调用方必须已持有 lock ----

    private int windowSize() {
        int configured = windowChunks != null ? windowChunks.getAsInt() : 1;
        return Math.max(1, Math.min(SceneLimits.MAX_CHUNKS_IN_FLIGHT_PER_PLAYER, configured));
    }

    private static long clampCursor(AssetTransferRegistry.AssetEntry entry, long cursor) {
        return Math.max(0L, Math.min(cursor, entry.totalSize()));
    }

    /** 填窗口：只要还有空槽位，就按优先级把下一片发出去。 */
    private void pumpLocked(List<Exhausted> failures) {
        long now = clockNanos.getAsLong();
        int window = windowSize();
        while (outstanding.size() < window) {
            Ticket candidate = null;
            // 先挑选候选，再在迭代之外发送：sendLocked 会从泳道里摘掉该票，
            // 在 for-each 迭代中修改同一个 Deque 会抛 ConcurrentModificationException。
            search:
            for (ArrayDeque<Ticket> lane : lanes) {
                for (Ticket ticket : lane) {
                    if (ticket.readyAtNanos > now) continue;
                    candidate = ticket;
                    break search;
                }
            }
            if (candidate == null) {
                // 没有可发送的票：清掉探测标志，下一次 tick 允许重新探测通道。
                probeParked = false;
                return;
            }
            if (!sendLocked(candidate, failures, now)) return;
        }
    }

    /** @return false 表示本轮无法继续发送（通道不可用或发送失败），调用方应停止填窗口。 */
    private boolean sendLocked(Ticket ticket, List<Exhausted> failures, long now) {
        if (sender == null || !sender.canSend()) {
            // 对端没有本模组或尚未进入 play 阶段：不消耗重试次数，等下一次 tick 再试。
            if (!probeParked) {
                probeParked = true;
                LOGGER.debug("场景资产传输通道当前不可用，暂缓发送: hash={}", shortHash(ticket.sha256));
            }
            return false;
        }
        probeParked = false;

        // 先把游标向前对齐到已接收前缀：整窗回退后仍可能有上一窗的迟到分片被接收，
        // 不重新对齐就会把已经拿到的字节再请求一遍（虽然会被判重复，但白跑一趟往返）。
        ticket.nextRequestOffset = Math.max(ticket.nextRequestOffset, clampCursor(ticket.entry, ticket.entry.acceptedBytes()));

        long offset = clampCursor(ticket.entry, ticket.nextRequestOffset);
        long remaining = ticket.entry.totalSize() - offset;
        if (remaining <= 0L) {
            dequeueLocked(ticket);
            return true;
        }
        long length = Math.min((long) SceneAssetChunkRequestC2S.CHUNK_SIZE, remaining);
        ticket.requestId++;
        try {
            sender.send(new SceneAssetChunkRequestC2S(
                    ticket.sha256, ticket.entry.transferId(), ticket.requestId, offset, (int) length));
        } catch (Throwable t) {
            LOGGER.warn("发送场景分片请求失败: hash={}", shortHash(ticket.sha256), t);
            Exhausted exhausted = consumeRetryLocked(ticket, "发送失败");
            if (exhausted != null) {
                releaseLocked(ticket);
                failures.add(exhausted);
            } else {
                resetWindowLocked(ticket);
                requeueLocked(ticket, backoffMillis(ticket.attempts));
            }
            return false;
        }

        ticket.nextRequestOffset = offset + length;
        outstanding.add(new Outstanding(ticket, offset, now));
        if (ticket.nextRequestOffset >= ticket.entry.totalSize()) {
            // 分片已经全部发出，票留着等回包，但不再占用泳道。
            dequeueLocked(ticket);
        }
        return true;
    }

    /**
     * 整窗回退：丢弃该资产全部在途记录，并把请求游标退回已接收前缀。
     *
     * <p>回退点用 {@code acceptedBytes} 而不是 {@code confirmedBytes}：已被写线程接收（哪怕
     * 还没落盘）的字节不需要重传。写盘失败时调用方会先把 {@code acceptedBytes} 退回
     * {@code confirmedBytes}，因此这里天然覆盖了两种情况。</p>
     */
    private void resetWindowLocked(Ticket ticket) {
        outstanding.removeIf(record -> record.ticket() == ticket);
        dequeueLocked(ticket);
        ticket.nextRequestOffset = clampCursor(ticket.entry, ticket.entry.acceptedBytes());
    }

    private void dropOutstandingLocked(Ticket ticket, long offset) {
        outstanding.removeIf(record -> record.ticket() == ticket && record.offset() == offset);
    }

    private void dequeueLocked(Ticket ticket) {
        if (ticket.queued) {
            lanes[ticket.lane].remove(ticket);
            ticket.queued = false;
        }
    }

    private void requeueLocked(Ticket ticket, long delayMs) {
        if (!tickets.containsKey(ticket.sha256)) return;
        ticket.lane = ticket.priority.ordinal();
        ticket.readyAtNanos = clockNanos.getAsLong() + delayMs * 1_000_000L;
        if (!ticket.queued) {
            lanes[ticket.lane].addLast(ticket);
            ticket.queued = true;
        }
    }

    /**
     * 记一次尝试。
     *
     * @return null 表示仍可重试；非 null 表示次数耗尽，调用方须在锁外触发该失败。
     */
    private Exhausted consumeRetryLocked(Ticket ticket, String reason) {
        ticket.attempts++;
        int limit = Math.max(0, maxRetries.getAsInt());
        if (ticket.attempts > limit) {
            return new Exhausted(ticket.entry, reason + "（重试 " + limit + " 次后放弃）");
        }
        return null;
    }

    private void releaseLocked(Ticket ticket) {
        dequeueLocked(ticket);
        outstanding.removeIf(record -> record.ticket() == ticket);
        tickets.remove(ticket.sha256, ticket);
    }

    private long backoffMillis(int attempts) {
        int shift = Math.min(Math.max(0, attempts - 1), 16);
        long delay = (long) backoffBaseMs << shift;
        return Math.max(1L, Math.min(delay, backoffMaxMs));
    }

    /** 必须在释放 {@link #lock} 之后调用：失败回调会一路走到等待者通知。 */
    private void fire(List<Exhausted> failures) {
        if (failureHandler == null || failures.isEmpty()) return;
        for (Exhausted failure : failures) {
            failureHandler.onExhausted(failure.entry(), failure.reason());
        }
    }

    private static String shortHash(String sha256) {
        return sha256 == null || sha256.length() < 8 ? String.valueOf(sha256) : sha256.substring(0, 8);
    }
}
