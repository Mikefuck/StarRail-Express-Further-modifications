package com.habitrain.core.scene.client;

import com.habitrain.core.client.config.SceneClientPerformanceRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 客户端场景网格构建的统一调度器。
 *
 * <p>把所有构建（主背景、附加背景、预取、编辑器预览）收到同一个队列上，按优先级依次推进，
 * 并以一份<b>共享</b>的 {@link SceneBuildBudget} 限制每个客户端帧的总构建时间。</p>
 *
 * <p><b>不引用 Minecraft</b>：本类只在客户端线程被调用，由渲染钩子与客户端 tick 从外部驱动
 * （见 {@code HabiTrainCoreClient} 与 {@code SceneClientTicker}）。这样调度与配额逻辑可以在
 * 单测里用假时钟完整覆盖，而所有 Minecraft 相关的构建细节留在 {@link SceneMeshBuilder}。</p>
 *
 * <p><b>驱动方式的取舍</b>：主驱动器是每帧一次的渲染钩子（渲染线程正是
 * {@code BakedModel}/{@code VertexBuffer} 唯一合法的线程）；客户端 tick 只是兜底，
 * 防止世界长时间不渲染时构建永久停住。</p>
 */
public final class SceneBuildScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneBuildScheduler.class.getSimpleName());

    /** 越小越优先；同一优先级内按提交顺序 FIFO。 */
    public enum Priority {
        /** 当前主背景与编辑器预览。 */
        PRIMARY,
        /** 当前启用的附加背景。 */
        ADDITIONAL,
        /** 预取（尚未启用但很可能用到）。 */
        PREFETCH
    }

    /** 一个可增量推进的构建任务。 */
    public interface BuildStep {
        /**
         * 推进一段工作。
         *
         * @param deadlineNanos 本次推进的时间上限（与调度器时钟同一时基）；实现应在
         *                      较小的批次之间检查它，并在到达后尽快返回
         * @return true 表示任务已完成（无论成功还是已经失败）
         */
        boolean step(long deadlineNanos);

        /** 任务被取消：释放尚未交付的资源。 */
        default void abort() {}
    }

    private static final SceneBuildScheduler INSTANCE = new SceneBuildScheduler(
            System::nanoTime,
            SceneClientPerformanceRules.meshBuildBudgetNanosPerSecond(
                    SceneClientPerformanceRules.DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME),
            SceneClientPerformanceRules.meshBuildBudgetBurstNanos(
                    SceneClientPerformanceRules.DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME));

    public static SceneBuildScheduler getInstance() {
        return INSTANCE;
    }

    private static final class Task {
        final BuildStep step;
        final Priority priority;
        final long sequence;

        Task(BuildStep step, Priority priority, long sequence) {
            this.step = step;
            this.priority = priority;
            this.sequence = sequence;
        }
    }

    private final LongSupplier clockNanos;
    private final SceneBuildBudget budget;
    private final List<Task> tasks = new ArrayList<>();
    private long sequence;
    private boolean pumping;
    private long lastPumpNanos;

    /** 供单测使用：注入假时钟与配额。 */
    public SceneBuildScheduler(LongSupplier clockNanos, long nanosPerSecond, long burstNanos) {
        this.clockNanos = clockNanos != null ? clockNanos : System::nanoTime;
        this.budget = new SceneBuildBudget(this.clockNanos, nanosPerSecond, burstNanos);
    }

    public void submit(BuildStep step, Priority priority) {
        if (step == null) return;
        tasks.add(new Task(step, priority == null ? Priority.PREFETCH : priority, sequence++));
    }

    /** 取消一个尚未完成的任务（会话重置、或构建失败后主动收尾）。 */
    public boolean cancel(BuildStep step) {
        if (step == null) return false;
        for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
            Task task = it.next();
            if (task.step != step) continue;
            it.remove();
            abortQuietly(task.step);
            return true;
        }
        return false;
    }

    /** 作废全部待推进的任务（换局/换世界）。 */
    public void reset() {
        List<Task> pending = new ArrayList<>(tasks);
        tasks.clear();
        for (Task task : pending) {
            abortQuietly(task.step);
        }
    }

    /** 按当前配置刷新配额；配置页改动后由 {@code SceneAssetCache.applyClientConfig()} 调用。 */
    public void applyBudgetMsPerFrame(int budgetMsPerFrame) {
        budget.setRate(
                SceneClientPerformanceRules.meshBuildBudgetNanosPerSecond(budgetMsPerFrame),
                SceneClientPerformanceRules.meshBuildBudgetBurstNanos(budgetMsPerFrame));
    }

    /**
     * 推进构建，直到预算耗尽或没有待办任务。
     *
     * <p>由渲染钩子每帧调用一次，客户端 tick 作为兜底再调一次。可重入保护是必需的：
     * 1.21.1 的 {@code Minecraft.execute} 在客户端线程上是内联执行的，构建完成回调可能
     * 直接回到这里。</p>
     */
    public void pump() {
        if (pumping) return;
        pumping = true;
        try {
            long start = clockNanos.getAsLong();
            long available = budget.availableNanos();
            if (available <= 0L || tasks.isEmpty()) return;

            long deadline = start + available;
            while (true) {
                Task task = pickNextTask();
                if (task == null) break;
                boolean done;
                try {
                    done = task.step.step(deadline);
                } catch (Throwable t) {
                    LOGGER.error("场景网格构建步骤异常", t);
                    abortQuietly(task.step);
                    done = true;
                }
                if (done) tasks.remove(task);
                if (clockNanos.getAsLong() >= deadline) break;
            }
            budget.consume(clockNanos.getAsLong() - start);
            lastPumpNanos = clockNanos.getAsLong() - start;
        } finally {
            pumping = false;
        }
    }

    public int pendingCount() {
        return tasks.size();
    }

    /** 最近一次 pump 实际消耗的纳秒数（供诊断）。 */
    public long lastPumpNanos() {
        return lastPumpNanos;
    }

    public long availableBudgetNanos() {
        return budget.availableNanos();
    }

    private Task pickNextTask() {
        Task best = null;
        for (Task task : tasks) {
            if (best == null
                    || task.priority.ordinal() < best.priority.ordinal()
                    || (task.priority == best.priority && task.sequence < best.sequence)) {
                best = task;
            }
        }
        return best;
    }

    private static void abortQuietly(BuildStep step) {
        try {
            step.abort();
        } catch (Throwable t) {
            LOGGER.warn("取消场景网格构建任务时异常", t);
        }
    }
}
