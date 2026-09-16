package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 构建调度器的契约：优先级顺序、共享预算、以及"预算耗尽后停在原地等下一次 pump"。
 *
 * <p>报告 §5.3 指出此前每个 BuildState 私有推进 4 ms，多个背景同时构建时互相叠加。
 * 这里固定的是它们现在共用一份预算，且一次的推进量有上界。</p>
 */
class SceneBuildSchedulerTest {
    private static final long MS = 1_000_000L;
    private static final long PER_SECOND = 4L * MS * 60L; // 每帧 4 ms @ 60 fps
    private static final long BURST = 4L * MS;
    private static final long FRAME_MILLIS = 1000L / 60L;

    private static final class FakeClock {
        long nowNanos;

        long current() {
            return nowNanos;
        }

        void advanceMillis(long millis) {
            nowNanos += millis * MS;
        }
    }

    /** 只记录推进次序、不消耗时间的任务。 */
    private static final class NamedStep implements SceneBuildScheduler.BuildStep {
        final String name;
        final List<String> log;
        int remaining;
        boolean aborted;

        NamedStep(String name, List<String> log, int steps) {
            this.name = name;
            this.log = log;
            this.remaining = steps;
        }

        @Override
        public boolean step(long deadlineNanos) {
            log.add(name);
            return --remaining <= 0;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }

    /** 每推进一次就消耗固定时间的任务，用来触发预算耗尽。 */
    private static final class CostlyStep implements SceneBuildScheduler.BuildStep {
        final FakeClock clock;
        final List<String> log;
        final long costNanos;
        int remaining;
        boolean aborted;

        CostlyStep(FakeClock clock, List<String> log, long costNanos, int steps) {
            this.clock = clock;
            this.log = log;
            this.costNanos = costNanos;
            this.remaining = steps;
        }

        @Override
        public boolean step(long deadlineNanos) {
            log.add("costly");
            clock.nowNanos += costNanos;
            return --remaining <= 0;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }

    private static SceneBuildScheduler scheduler(FakeClock clock) {
        return new SceneBuildScheduler(clock::current, PER_SECOND, BURST);
    }

    @Test
    void higherPriorityTaskRunsFirst() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);
        List<String> log = new ArrayList<>();

        scheduler.submit(new NamedStep("prefetch", log, 1), SceneBuildScheduler.Priority.PREFETCH);
        scheduler.submit(new NamedStep("primary", log, 1), SceneBuildScheduler.Priority.PRIMARY);

        scheduler.pump();

        assertEquals(List.of("primary", "prefetch"), log);
    }

    @Test
    void samePriorityIsFifo() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);
        List<String> log = new ArrayList<>();

        scheduler.submit(new NamedStep("first", log, 1), SceneBuildScheduler.Priority.ADDITIONAL);
        scheduler.submit(new NamedStep("second", log, 1), SceneBuildScheduler.Priority.ADDITIONAL);

        scheduler.pump();

        assertEquals(List.of("first", "second"), log);
    }

    @Test
    void completedTasksAreDroppedFromTheQueue() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);

        scheduler.submit(new NamedStep("a", new ArrayList<>(), 1), SceneBuildScheduler.Priority.PRIMARY);
        scheduler.submit(new NamedStep("b", new ArrayList<>(), 3), SceneBuildScheduler.Priority.PRIMARY);
        assertEquals(2, scheduler.pendingCount());

        scheduler.pump();

        assertEquals(0, scheduler.pendingCount(),
                "零成本任务应当在一次 pump 内跑完并被移除");
    }

    /**
     * 预算耗尽必须停下——否则「共享预算」就是空话。
     *
     * <p>一个每次推进都吃掉 3 ms 的重任务，在一帧 4 ms 的预算下最多被推进两段就会被叫停。</p>
     */
    @Test
    void aSinglePumpNeverConsumesMuchMoreThanTheBucketHolds() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);
        List<String> log = new ArrayList<>();
        CostlyStep costly = new CostlyStep(clock, log, 3L * MS, 100);
        scheduler.submit(costly, SceneBuildScheduler.Priority.PRIMARY);

        scheduler.pump();

        long consumed = log.size() * 3L * MS;
        assertTrue(consumed <= 8L * MS,
                "单次 pump 只应花掉一帧预算附近的时间，实际 " + consumed / MS + " ms");
        assertTrue(log.size() >= 1, "至少应推进一段");
        assertEquals(1, scheduler.pendingCount(), "未完成的任务必须留在队列里");
    }

    @Test
    void workResumesOnTheNextFrameAfterTheBudgetIsSpent() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);
        List<String> log = new ArrayList<>();
        CostlyStep costly = new CostlyStep(clock, log, 3L * MS, 3);
        scheduler.submit(costly, SceneBuildScheduler.Priority.PRIMARY);

        scheduler.pump();
        int afterFirstFrame = log.size();
        assertTrue(afterFirstFrame < 3, "本用例的前提是第一次 pump 没能跑完");

        clock.advanceMillis(FRAME_MILLIS);
        scheduler.pump();

        assertTrue(log.size() > afterFirstFrame, "下一帧必须继续推进未完成的任务");
    }

    @Test
    void aThrowingStepIsDroppedInsteadOfWedgeingTheQueue() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);
        List<String> log = new ArrayList<>();

        boolean[] aborted = {false};
        scheduler.submit(new SceneBuildScheduler.BuildStep() {
            public boolean step(long deadline) { throw new IllegalStateException("boom"); }
            public void abort() { aborted[0] = true; }
        }, SceneBuildScheduler.Priority.PRIMARY);
        scheduler.submit(new NamedStep("next", log, 1), SceneBuildScheduler.Priority.PRIMARY);

        scheduler.pump();

        assertEquals(List.of("next"), log, "抛异常的任务必须被移除，后续任务照常推进");
        assertEquals(0, scheduler.pendingCount());
        assertTrue(aborted[0], "失败任务也必须释放资源并完成其等待者");
    }

    @Test
    void cancelDropsTheTaskAndAbortsIt() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);
        NamedStep step = new NamedStep("a", new ArrayList<>(), 5);

        scheduler.submit(step, SceneBuildScheduler.Priority.PRIMARY);
        assertTrue(scheduler.cancel(step));
        assertTrue(step.aborted);
        assertEquals(0, scheduler.pendingCount());
        assertFalse(scheduler.cancel(step), "重复取消应返回 false");
    }

    @Test
    void resetAbortsEveryPendingTask() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);
        NamedStep a = new NamedStep("a", new ArrayList<>(), 5);
        NamedStep b = new NamedStep("b", new ArrayList<>(), 5);

        scheduler.submit(a, SceneBuildScheduler.Priority.PRIMARY);
        scheduler.submit(b, SceneBuildScheduler.Priority.PREFETCH);
        scheduler.reset();

        assertTrue(a.aborted);
        assertTrue(b.aborted);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void anEmptyPumpIsANoOp() {
        FakeClock clock = new FakeClock();
        SceneBuildScheduler scheduler = scheduler(clock);

        scheduler.pump();

        assertEquals(0, scheduler.pendingCount());
        assertEquals(BURST, scheduler.availableBudgetNanos(), "没有待办任务时不应消耗预算");
    }
}
