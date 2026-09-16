package com.habitrain.core.scene.client;

import com.habitrain.core.client.config.SceneClientPerformanceRules;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.asset.SceneAssetCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 分片校验与落盘的写线程流水线。
 *
 * <p>此前 {@code acceptChunk} 在客户端线程上做 CRC32、{@code fos.write} 和收官时的
 * {@code flush/close}——机械盘、杀毒扫描或磁盘繁忙都会直接变成帧时间抖动。这里把"校验 + 写盘"
 * 整段搬到单独一条写线程上，客户端线程只做 O(1) 的去重/缺口判定并转交。</p>
 *
 * <p><b>核心不变式：{@code [0, committed)} 是唯一可信区间。</b> 该区间之外的字节在重写之前
 * 一律视为垃圾——写了一半、进程崩溃、检查点回退因此全部是良性情况。据此：</p>
 * <ul>
 *   <li>写失败/短写**不推进** {@code committed}，残留落在可信区间外，重试按同一偏移覆盖即可；</li>
 *   <li>续传只截断到记录里的 {@code resumableBytes}，绝不把没验证过的字节当成数据；</li>
 *   <li>收尾顺序固定为 写满 → 检查点 → {@code close()} → 转 VERIFYING → 派发整文件校验，
 *       {@code close()} 先于派发是 Windows 上能成功 move 的前提。</li>
 * </ul>
 *
 * <p><b>线程约定</b>：{@link #begin} / {@link #offer} / {@link #suspend} / {@link #abandon}
 * 在客户端线程调用且只碰 volatile 字段与 O(1) 容器操作；所有文件句柄的打开与关闭**只发生在
 * 写线程上**（见 {@link #openSlot} / {@link #finalizeSlot}），因此同一个 {@code .part} 永远
 * 不会同时存在两个写者。回调一律在写线程触发，调用方自行决定要不要跳回客户端线程。</p>
 *
 * <p><b>有界窗口下的乱序容忍</b>：下载队列一次可以发出多片请求，因此同一 hash 可能同时有
 * 数片在写。去重基准因此从 {@code committed} 换成 {@link Slot#acceptedOffset}（接收即推进）
 * ——写线程仍严格按提交顺序（= 到达顺序）落盘，落盘时再做一次 {@code offset == committed}
 * 的顺序守卫，所以「前一片失败 → 后面已入队的片自动作废」是良性情况，
 * {@code [0, committed)} 始终是可信前缀。窗口上限与 {@link #MAX_PENDING_WRITES} 一起把
 * 未落盘分片数限制在结构上有界的范围内。</p>
 *
 * <p>不依赖 Minecraft，时钟、执行器与文件实现全部注入，可在单测里完整覆盖。</p>
 */
public final class SceneAssetWritePipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAssetWritePipeline.class.getSimpleName());

    /** 单槽位允许的未落盘分片数上限；等于同一条连接的窗口上限，正常路径不可达（纯防御）。 */
    private static final int MAX_PENDING_WRITES = SceneLimits.MAX_CHUNKS_IN_FLIGHT_PER_PLAYER;

    /** 一次 {@link #offer} 的判定结果。 */
    public enum Offer {
        /** 已转交写线程。 */
        ACCEPTED,
        /** 重复分片（同偏移重发，或落在已确认前缀内）：丢弃，不推进也不中止。 */
        DUPLICATE,
        /** 偏移跳过了缺口：调用方应重新请求缺失偏移。 */
        GAP,
        /** 越界或状态不一致：调用方按可重试错误处理。 */
        CONFLICT,
        /** 该 hash 没有活跃槽位（已退役、已作废或身份不符）。 */
        INACTIVE
    }

    /** 写线程回调；实现方负责在需要时跳回客户端线程。 */
    public interface Events {
        /** 非收尾分片写入成功，可以请求下一片了。 */
        void onCommitted(AssetTransferRegistry.AssetEntry entry, long committedBytes);

        /** 最后一片写完且句柄已关闭，可以交付整文件校验了。 */
        void onComplete(AssetTransferRegistry.AssetEntry entry, Path dataFile);

        /** 本片没有被写入（CRC 失败、写失败、句柄不可用），调用方应按可重试错误处理。 */
        void onChunkRejected(AssetTransferRegistry.AssetEntry entry, String reason);
    }

    /** 一个 hash 的写入槽位。除标注外，字段只在写线程上访问。 */
    private static final class Slot {
        final Object lock = new Object();
        final AssetTransferRegistry.AssetEntry entry;
        final String sha256;
        final Path dataFile;
        final long totalSize;
        final String fingerprint;

        /** 已确认落在磁盘上的连续前缀长度。写线程写、客户端线程无锁读 → volatile。 */
        volatile long committed;
        /**
         * 已接收、已转交写线程的连续前缀末端（无论是否落盘）。客户端线程在 {@link #offer}
         * 里推进，失败回退时由 {@link #rollbackAccepted} 退回 {@link #committed}。
         * 它是有界窗口下的去重基准：重复分片判 {@code offset < acceptedOffset}。
         */
        volatile long acceptedOffset;
        /** 已提交但尚未落盘的分片数；受 {@link #lock} 保护，与窗口上限一起构成背压。 */
        int pendingWrites;
        /** 已被同一 hash 的新槽位取代：不要再碰旧句柄。 */
        volatile boolean superseded;
        /** 已从 registry 链路上摘除（挂起或丢弃）：不再推进、不再回调。 */
        volatile boolean detached;

        private ScenePartFile file;
        private MessageDigest digest;
        private long checkpointBytes;
        private long lastCheckpointMillis;

        Slot(AssetTransferRegistry.AssetEntry entry, Path dataFile, long startOffset, String fingerprint) {
            this.entry = entry;
            this.sha256 = entry.sha256();
            this.dataFile = dataFile;
            this.totalSize = entry.totalSize();
            this.fingerprint = fingerprint;
            this.committed = startOffset;
            this.acceptedOffset = startOffset;
        }
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final Executor executor;
    private final ScenePartFile.Factory fileFactory;
    private final ScenePartialStore partialStore;
    private final AssetTransferRegistry registry;
    private final Events events;
    private final LongSupplier clockMillis;

    public SceneAssetWritePipeline(Executor executor, ScenePartFile.Factory fileFactory,
                                   ScenePartialStore partialStore, AssetTransferRegistry registry,
                                   Events events, LongSupplier clockMillis) {
        this.executor = executor;
        this.fileFactory = fileFactory != null ? fileFactory : RandomAccessPartFile::new;
        this.partialStore = partialStore;
        this.registry = registry;
        this.events = events;
        this.clockMillis = clockMillis;
    }

    /**
     * 打开（或续传）一个 hash 的部分文件，就绪后回调 {@code onReady}。
     *
     * <p>{@code startOffset} 同时是"可信前缀"与截断目标：新建时为 0（顺带清掉上次的残留），
     * 续传时为已校验过的 {@code resumableBytes}（把没确认的尾巴截掉）。</p>
     *
     * @param seedDigest 已喂入前 {@code startOffset} 个字节的 SHA-256，用于继续写检查点；
     *                   为 null 表示不做检查点（例如当前 JVM 不支持克隆摘要）
     * @param onReady    句柄已就绪、可以发起首个请求了；在写线程上调用
     * @param onFailed   打开失败的原因；在写线程上调用
     */
    public void begin(AssetTransferRegistry.AssetEntry entry, Path dataFile, long startOffset,
                      String fingerprint, MessageDigest seedDigest,
                      Runnable onReady, Consumer<String> onFailed) {
        if (entry == null || dataFile == null) return;
        if (startOffset < 0L || startOffset > entry.totalSize()) {
            if (onFailed != null) onFailed.accept("续传偏移越界");
            return;
        }
        Slot slot = new Slot(entry, dataFile, startOffset, fingerprint);
        Slot previous = slots.put(slot.sha256, slot);
        if (previous != null) {
            // 同一 hash 的旧槽位：它的句柄由写线程在 openSlot 里关闭，保证不会有两个写者。
            previous.superseded = true;
        }
        executor.execute(() -> openSlot(slot, previous, seedDigest, onReady, onFailed));
    }

    /** 客户端线程：判定并转交一个分片。只做 O(1) 操作，不碰磁盘。 */
    public Offer offer(AssetTransferRegistry.AssetEntry entry, long offset, byte[] data, long crc32) {
        if (entry == null || data == null || data.length == 0) return Offer.INACTIVE;
        Slot slot = slots.get(entry.sha256());
        if (slot == null || slot.entry != entry || slot.superseded || slot.detached) return Offer.INACTIVE;

        long acceptedThrough;
        synchronized (slot.lock) {
            // 去重基准是"已接收前缀"而不是"已落盘前缀"：窗口下后一片完全可能在前一片落盘前到达，
            // 判据必须只看有没有接收过，否则每一片都会被自己前面的在途片判成缺口。
            if (offset < slot.acceptedOffset) return Offer.DUPLICATE;
            if (offset > slot.acceptedOffset) return Offer.GAP;
            if (offset + data.length > slot.totalSize) return Offer.CONFLICT;
            if (slot.pendingWrites >= MAX_PENDING_WRITES) return Offer.CONFLICT;
            slot.acceptedOffset = offset + data.length;
            slot.pendingWrites++;
            acceptedThrough = slot.acceptedOffset;
        }
        registry.confirmAccepted(entry, acceptedThrough);
        executor.execute(() -> writeChunk(slot, offset, data, crc32));
        return Offer.ACCEPTED;
    }

    /**
     * 客户端线程：把"已接收前缀"退回最后一次真正落盘的偏移。
     *
     * <p>某片被写线程拒绝（CRC 失败、写失败、句柄不可用）时由调用方在通知下载队列重试
     * <b>之前</b>调用。此后仍在写线程队列里的后续分片会被顺序守卫丢弃，
     * 调用方按 {@link Slot#committed}（= registry 的 confirmedBytes）整窗重发。</p>
     */
    public void rollbackAccepted(AssetTransferRegistry.AssetEntry entry) {
        if (entry == null) return;
        Slot slot = slots.get(entry.sha256());
        if (slot == null || slot.entry != entry) return;
        long committed;
        synchronized (slot.lock) {
            slot.acceptedOffset = slot.committed;
            committed = slot.committed;
        }
        registry.confirmAccepted(entry, committed);
    }

    /** 供诊断：某个 hash 已接收但尚未落盘的前缀末端。 */
    public long acceptedOffset(AssetTransferRegistry.AssetEntry entry) {
        if (entry == null) return 0L;
        Slot slot = slots.get(entry.sha256());
        return slot == null || slot.entry != entry ? 0L : slot.acceptedOffset;
    }

    /**
     * 挂起一个 hash：摘除槽位、继续写完在途的那一片，然后落检查点并关闭句柄。
     * 部分文件**保留**，供下次续传。
     */
    public void suspend(AssetTransferRegistry.AssetEntry entry) {
        if (entry == null) return;
        Slot slot = slots.get(entry.sha256());
        if (slot == null || slot.entry != entry) return;
        slots.remove(entry.sha256(), slot);
        slot.detached = true;
        executor.execute(() -> finalizeSlot(slot, false));
    }

    /** 丢弃一个 hash：摘除槽位并删除部分文件与元数据。 */
    public void abandon(AssetTransferRegistry.AssetEntry entry) {
        if (entry == null) return;
        Slot slot = slots.get(entry.sha256());
        if (slot == null || slot.entry != entry) return;
        slots.remove(entry.sha256(), slot);
        slot.detached = true;
        executor.execute(() -> finalizeSlot(slot, true));
    }

    /** 挂起当前全部槽位（会话重置时调用）。 */
    public void suspendAll() {
        for (Slot slot : new ArrayList<>(slots.values())) {
            suspend(slot.entry);
        }
    }

    /** 关闭全部句柄并保留可续传前缀（客户端退出时调用）。 */
    public void closeAll() {
        for (Slot slot : new ArrayList<>(slots.values())) {
            slots.remove(slot.sha256, slot);
            slot.detached = true;
            executor.execute(() -> finalizeSlot(slot, false));
        }
    }

    /** 是否存在活跃槽位（诊断用）。 */
    public int activeSlots() {
        return slots.size();
    }

    /** 等待写线程把已排队的任务跑完（测试用）。执行器不是 {@link ExecutorService} 时直接返回 true。 */
    public boolean awaitIdle(long millis) {
        if (!(executor instanceof ExecutorService service)) return true;
        try {
            service.submit(() -> { }).get(Math.max(1L, millis), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 以下全部在写线程执行 ----

    private void openSlot(Slot slot, Slot previous, MessageDigest seedDigest,
                          Runnable onReady, Consumer<String> onFailed) {
        // 先关旧句柄再开新的：同一个 .part 上永远只有一个写者。
        if (previous != null) closeQuietly(previous);
        if (slot.superseded || slot.detached) {
            // 已被取代/作废：绝不能静默返回——调用方的等待者要靠这个回调结束，
            // 否则加载页会一直等下去（旧 activeDownloads 的同一个坑）。
            if (onFailed != null) onFailed.accept("槽位已被取代或作废");
            return;
        }

        try {
            ScenePartFile file = fileFactory.open(slot.dataFile);
            if (file.size() < slot.committed) {
                // 盘上比声明的续传点还短：说明前缀根本不在，直接作废。
                closeFile(file, slot.dataFile);
                failSlot(slot, "部分文件短于续传点", onFailed);
                return;
            }
            file.truncate(slot.committed);
            slot.file = file;
            slot.digest = seedDigest;
            slot.checkpointBytes = slot.committed;
            slot.lastCheckpointMillis = clockMillis.getAsLong();
            if (onReady != null) onReady.run();
        } catch (IOException e) {
            LOGGER.warn("打开场景部分文件失败: hash={}", shortHash(slot.sha256), e);
            failSlot(slot, "无法打开部分文件", onFailed);
        }
    }

    private void failSlot(Slot slot, String reason, Consumer<String> onFailed) {
        slot.detached = true;
        slots.remove(slot.sha256, slot);
        if (partialStore != null) partialStore.discard(slot.sha256);
        if (onFailed != null) onFailed.accept(reason);
    }

    private void writeChunk(Slot slot, long offset, byte[] data, long crc32) {
        try {
            if (slot.superseded) return;
            if (slot.file == null) {
                events.onChunkRejected(slot.entry, "部分文件句柄不可用");
                return;
            }
            if (offset != slot.committed) {
                // 顺序守卫：前一片失败后仍留在队列里的残余，或（理论上不存在的）乱序到达。
                // 丢弃即可——调用方已按整窗回退重新请求，磁盘前缀始终可信，绝不写第二遍。
                LOGGER.debug("丢弃非连续的场景分片: hash={}, offset={}, committed={}",
                        shortHash(slot.sha256), offset, slot.committed);
                return;
            }
            long actualCrc = SceneAssetCodec.calculateCrc32(data, 0, data.length);
            if (actualCrc != crc32) {
                events.onChunkRejected(slot.entry, "分片 CRC 校验失败");
                return;
            }

            slot.file.writeAt(offset, data, data.length);
            synchronized (slot.lock) {
                slot.committed = offset + data.length;
            }
            if (slot.digest != null) {
                // 只有通过顺序守卫的字节才喂摘要，因此摘要与磁盘前缀始终一致。
                slot.digest.update(data);
            }
            checkpoint(slot, false);
            // registry 的确认是线程安全的；确认失败只说明链路已被 reset，字节仍然落在盘上。
            registry.confirmBytes(slot.entry, slot.committed);

            if (slot.detached) return;
            if (slot.committed >= slot.totalSize) {
                completeSlot(slot);
            } else {
                events.onCommitted(slot.entry, slot.committed);
            }
        } catch (IOException e) {
            LOGGER.warn("场景分片写盘失败: hash={}, offset={}", shortHash(slot.sha256), offset, e);
            events.onChunkRejected(slot.entry, "写入分片失败");
        } catch (RuntimeException e) {
            LOGGER.error("场景分片写盘异常: hash=" + shortHash(slot.sha256) + ", offset=" + offset, e);
            events.onChunkRejected(slot.entry, "写盘流水线异常");
        } finally {
            synchronized (slot.lock) {
                if (slot.pendingWrites > 0) slot.pendingWrites--;
            }
        }
    }

    private void completeSlot(Slot slot) {
        checkpoint(slot, true);
        closeQuietly(slot);
        // 槽位用完即摘：否则每个下完的 hash 都会在 slots 里留一个死条目。
        slot.detached = true;
        slots.remove(slot.sha256, slot);
        if (!registry.transition(slot.entry, AssetTransferRegistry.State.VERIFYING)) {
            // 链路已作废：完整且带合法元数据的 .part 留在盘上，下次会直接进入校验阶段。
            LOGGER.debug("场景资产已收全但链路已作废，保留部分文件待下次校验: hash={}", shortHash(slot.sha256));
            return;
        }
        events.onComplete(slot.entry, slot.dataFile);
    }

    private void finalizeSlot(Slot slot, boolean delete) {
        if (delete) {
            closeQuietly(slot);
            if (partialStore != null) {
                partialStore.discard(slot.sha256);
            } else {
                try {
                    Files.deleteIfExists(slot.dataFile);
                } catch (IOException e) {
                    LOGGER.debug("删除场景部分文件失败: {}", slot.dataFile, e);
                }
            }
            return;
        }
        // 挂起：此处读到的 committed 已经包含在途那一片（同一个写线程、FIFO），不会漏记。
        checkpoint(slot, true);
        closeQuietly(slot);
    }

    private void checkpoint(Slot slot, boolean force) {
        ScenePartialStore store = partialStore;
        if (store == null || slot.digest == null) return;

        long now = clockMillis.getAsLong();
        if (!force
                && now - slot.lastCheckpointMillis < SceneClientPerformanceRules.PARTIAL_METADATA_INTERVAL_MS
                && slot.committed - slot.checkpointBytes < SceneClientPerformanceRules.PARTIAL_METADATA_INTERVAL_BYTES) {
            return;
        }

        long resumable = SceneClientPerformanceRules.alignResumable(slot.committed, slot.totalSize);
        if (resumable != slot.committed || resumable <= 0L) {
            // 分片天然对齐，这里只是为了绝不写出一个服务端会拒绝的偏移。
            LOGGER.warn("场景部分下载前缀不可用，跳过检查点: hash={}, committed={}",
                    shortHash(slot.sha256), slot.committed);
            return;
        }
        try {
            // 只有确实落到盘上的字节才能进元数据。
            if (slot.file == null || slot.file.size() < slot.committed) {
                LOGGER.debug("部分文件长度落后于已确认字节，跳过检查点: hash={}", shortHash(slot.sha256));
                return;
            }
        } catch (IOException e) {
            return;
        }

        String prefixHash = ScenePrefixHash.snapshotHex(slot.digest);
        if (prefixHash == null) {
            // 绝不写入假校验值：宁可从此不做续传，也不能让坏前缀被当成好的。
            LOGGER.warn("当前 JVM 不支持 SHA-256 克隆，已停止断点续传检查点: hash={}", shortHash(slot.sha256));
            slot.digest = null;
            return;
        }
        boolean stored = store.store(new ScenePartialStore.PendingPart(
                slot.sha256, slot.totalSize, slot.committed, prefixHash, slot.fingerprint, now), force);
        if (stored) {
            slot.checkpointBytes = slot.committed;
            slot.lastCheckpointMillis = now;
        }
    }

    private void closeQuietly(Slot slot) {
        ScenePartFile file = slot.file;
        slot.file = null;
        if (file == null) return;
        closeFile(file, slot.dataFile);
    }

    private static void closeFile(ScenePartFile file, Path path) {
        try {
            file.close();
        } catch (IOException e) {
            LOGGER.debug("关闭场景部分文件失败: {}", path, e);
        }
    }

    private static String shortHash(String sha256) {
        return sha256 == null || sha256.length() < 8 ? String.valueOf(sha256) : sha256.substring(0, 8);
    }
}
