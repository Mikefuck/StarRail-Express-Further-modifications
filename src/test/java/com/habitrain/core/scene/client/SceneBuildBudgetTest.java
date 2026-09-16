package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共享构建预算的令牌桶契约。
 *
 * <p>报告 §5.3 的修复点：此前每个背景各自 4 ms，合计没有上界。这里固定的是"所有构建
 * 共享同一份预算"以及"单次最多拿到 burst"。</p>
 */
class SceneBuildBudgetTest {
    private static final long MS = 1_000_000L;
    private static final long PER_SECOND = 4L * MS * 60L; // 每帧 4 ms @ 60 fps
    private static final long BURST = 4L * MS;

    private static final class FakeClock {
        long nowNanos;

        long current() {
            return nowNanos;
        }

        void advanceMillis(long millis) {
            nowNanos += millis * MS;
        }
    }

    private static SceneBuildBudget budget(FakeClock clock) {
        // 初始满桶：第一次 pump 立刻可用。
        return new SceneBuildBudget(clock::current, PER_SECOND, BURST);
    }

    @Test
    void startsWithAFullBucketSoTheFirstFrameIsNotStalled() {
        FakeClock clock = new FakeClock();
        assertEquals(BURST, budget(clock).availableNanos());
    }

    @Test
    void consumingDrainsTheBucketAndRefillsWithWallClockTime() {
        FakeClock clock = new FakeClock();
        SceneBuildBudget budget = budget(clock);

        budget.consume(BURST);
        assertEquals(0L, budget.availableNanos());
        assertFalse(budget.hasBudget());

        // 16.67 ms 之后应该刚好补回一帧的量。
        clock.advanceMillis(1000L / 60L);
        assertEquals(BURST, budget.availableNanos(), 2L * MS);
    }

    @Test
    void tokensNeverExceedTheBurstEvenAfterALongIdle() {
        FakeClock clock = new FakeClock();
        SceneBuildBudget budget = budget(clock);

        budget.consume(BURST);
        clock.advanceMillis(10_000);
        assertEquals(BURST, budget.availableNanos(), "长时间空闲不得攒出一个巨大的突发");
    }

    @Test
    void consumingMoreThanAvailableClampsToZeroInsteadOfGoingNegative() {
        FakeClock clock = new FakeClock();
        SceneBuildBudget budget = budget(clock);

        // 单个缓冲超过预算时不可避免会过冲；过冲必须只把桶清零，不能变成"欠账"，
        // 否则一次过冲会让后续好几帧完全没有预算。
        budget.consume(BURST * 3);
        assertEquals(0L, budget.availableNanos());
        clock.advanceMillis(1000L / 60L);
        assertEquals(BURST, budget.availableNanos(), 2L * MS);
    }

    @Test
    void loweringTheRateAlsoTrimsAlreadyAccumulatedTokens() {
        FakeClock clock = new FakeClock();
        SceneBuildBudget budget = budget(clock);
        assertEquals(BURST, budget.availableNanos());

        budget.setRate(1L * MS * 60L, 1L * MS);
        assertEquals(1L * MS, budget.availableNanos(), "调小之后不得再放行一次旧的大突发");
    }

    @Test
    void longRunRateMatchesTheConfiguredBudget() {
        FakeClock clock = new FakeClock();
        SceneBuildBudget budget = budget(clock);

        // 模拟 60 帧：每帧把可用预算全部花掉。
        long totalConsumed = 0L;
        for (int frame = 0; frame < 60; frame++) {
            long available = budget.availableNanos();
            budget.consume(available);
            totalConsumed += available;
            clock.advanceMillis(1000L / 60L);
        }

        // 一秒之内所有构建合计只应花掉约 4 ms × 60 = 240 ms。
        assertTrue(totalConsumed <= 250L * MS,
                "共享预算被突破：一秒钟消耗了 " + totalConsumed / MS + " ms");
        assertTrue(totalConsumed >= 200L * MS,
                "预算被过度节流，一秒钟只消耗了 " + totalConsumed / MS + " ms");
    }
}
