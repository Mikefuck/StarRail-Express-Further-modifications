package com.habitrain.core.scene.client;

import java.util.function.LongSupplier;

/**
 * 场景网格构建的共享令牌桶。
 *
 * <p>此前每个 {@code BuildState} 各自持有「每步 2 个 section / 4 ms」的私有预算，于是主背景、
 * 最多 4 个附加背景、预取与编辑器预览可以同时在同一个客户端线程上各花 4 ms——总时间没有
 * 上界。这里把预算收成一份全局令牌：<b>所有构建合计</b>每帧最多消耗 {@code burstNanos}，
 * 长期速率是 {@code nanosPerSecond}。</p>
 *
 * <p>令牌按墙钟时间连续补充，因此调用方不需要「每帧重置」的钩子：帧率高的机器自然每帧少
 * 分一点，帧率低的机器每次多分一点，两者都不会让单次 {@link #availableNanos()} 超过 burst。</p>
 *
 * <p>不依赖 Minecraft，时钟由构造参数注入，配额行为可用假时钟完整覆盖。</p>
 */
public final class SceneBuildBudget {
    private final LongSupplier clockNanos;

    private long nanosPerSecond;
    private long burstNanos;
    private long tokensNanos;
    private long lastRefillNanos;

    public SceneBuildBudget(LongSupplier clockNanos, long nanosPerSecond, long burstNanos) {
        this.clockNanos = clockNanos != null ? clockNanos : System::nanoTime;
        this.nanosPerSecond = Math.max(1L, nanosPerSecond);
        this.burstNanos = Math.max(1L, burstNanos);
        this.tokensNanos = this.burstNanos;
        this.lastRefillNanos = this.clockNanos.getAsLong();
    }

    /**
     * 更新配额（配置页热更）。
     *
     * <p>调小时立即把已积累的令牌夹到新的 burst 之内，避免一次调小之后仍然放行一次旧的大突发。</p>
     */
    public synchronized void setRate(long nanosPerSecond, long burstNanos) {
        refillLocked();
        this.nanosPerSecond = Math.max(1L, nanosPerSecond);
        this.burstNanos = Math.max(1L, burstNanos);
        this.tokensNanos = Math.min(this.tokensNanos, this.burstNanos);
    }

    /** 当前可用预算（纳秒）。 */
    public synchronized long availableNanos() {
        refillLocked();
        return tokensNanos;
    }

    public synchronized boolean hasBudget() {
        refillLocked();
        return tokensNanos > 0L;
    }

    /** 记一次实际消耗。允许超额（单步无法再切分时），超额只会把桶清零，不会变成负值。 */
    public synchronized void consume(long nanos) {
        refillLocked();
        tokensNanos = Math.max(0L, tokensNanos - Math.max(0L, nanos));
    }

    public synchronized long burstNanos() {
        return burstNanos;
    }

    private void refillLocked() {
        long now = clockNanos.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0L) return;
        lastRefillNanos = now;
        long gained = (long) (elapsed * (nanosPerSecond / 1_000_000_000.0));
        if (gained > 0L) {
            tokensNanos = Math.min(burstNanos, tokensNanos + gained);
        }
    }
}
