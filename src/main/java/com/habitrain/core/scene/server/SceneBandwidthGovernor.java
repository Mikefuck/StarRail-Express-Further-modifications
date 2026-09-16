package com.habitrain.core.scene.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 场景资产下行的带宽预约器。
 *
 * <p>此前的限速在唯一的 IO 线程里 {@code reserve} 之后直接 {@code Thread.sleep(delay)}：
 * 一个玩家等自己的配额时，其他玩家的文件读取被一并阻塞，多人同时冷缓存加入时表现为
 * 排队时间随人数线性增长。这里只负责算出「这一批字节最早可以从什么时刻开始发送」，
 * 真正的等待与投递交给调用方的 pacer，IO 线程只做读取。</p>
 *
 * <p><b>唯一的关键不变式：同一玩家连续 {@link #reserve} 返回的 readyAtNanos 单调不减。</b>
 * 调用方据此把「先预约、再按 deadline 延迟投递」当成有序队列使用。一旦破坏这一点，延迟
 * 投递会让同一玩家的分片乱序到达，而客户端的写盘流水线要求严格连续前缀。</p>
 *
 * <p>不依赖 Minecraft，时钟由构造参数注入，因此排队行为可以用假时钟完整覆盖。</p>
 */
public final class SceneBandwidthGovernor {
    private final LongSupplier clockNanos;
    private final long globalBytesPerSecond;

    /** 每玩家下一个可发送时刻；只增不减，玩家下线时移除。 */
    private final Map<UUID, Long> nextPlayerSendNanos = new HashMap<>();
    /** 全局下一个可发送时刻，所有玩家共享。 */
    private long nextGlobalSendNanos;

    public SceneBandwidthGovernor(LongSupplier clockNanos, long globalBytesPerSecond) {
        this.clockNanos = clockNanos != null ? clockNanos : System::nanoTime;
        this.globalBytesPerSecond = Math.max(1L, globalBytesPerSecond);
    }

    /**
     * 预约 {@code bytes} 个字节的下行配额。
     *
     * @param bytesPerSecond 该玩家当前阶段（加载期/对局期）的单玩家限速
     * @return 这一批字节最早可以发送的绝对时刻（与 {@link #clockNanos} 同一时基）
     */
    public synchronized long reserve(UUID playerId, int bytes, long bytesPerSecond) {
        long now = clockNanos.getAsLong();
        long size = Math.max(0L, bytes);
        long playerRate = Math.max(1L, bytesPerSecond);
        long playerCursor = playerId != null ? nextPlayerSendNanos.getOrDefault(playerId, now) : now;
        long start = Math.max(now, Math.max(playerCursor, nextGlobalSendNanos));

        if (playerId != null) {
            nextPlayerSendNanos.put(playerId, start + nanosFor(size, playerRate));
        }
        nextGlobalSendNanos = start + nanosFor(size, globalBytesPerSecond);
        return start;
    }

    /** 玩家下线：丢掉其游标，避免 UUID 表无界增长。 */
    public synchronized void forget(UUID playerId) {
        if (playerId != null) nextPlayerSendNanos.remove(playerId);
    }

    public synchronized void reset() {
        nextPlayerSendNanos.clear();
        nextGlobalSendNanos = 0L;
    }

    /**
     * 与 {@link #reserve} 同一时基的当前时刻。
     *
     * <p>调用方必须用它（而不是 {@code System.nanoTime()}）去计算 {@code readyAt - now}，
     * 否则注入假时钟时两者不同源，延迟投递的等待时间无法在单测里被验证。</p>
     */
    public long nowNanos() {
        return clockNanos.getAsLong();
    }

    /** 供诊断使用：全局游标落后于当前时刻即说明当前没有排队。 */
    public synchronized long nextGlobalSendNanos() {
        return nextGlobalSendNanos;
    }

    public synchronized int trackedPlayers() {
        return nextPlayerSendNanos.size();
    }

    private static long nanosFor(long bytes, long bytesPerSecond) {
        return (long) (bytes * (1_000_000_000.0 / bytesPerSecond));
    }
}
