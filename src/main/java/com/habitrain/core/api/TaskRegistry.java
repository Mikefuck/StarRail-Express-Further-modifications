package com.habitrain.core.api;

import com.habitrain.core.api.spi.CoreLifecycle;
import com.habitrain.core.api.spi.CoreSpi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 任务注册中心 — 取代 HabiTaskRegistry。
 * 新增: 按 GameMode 查询。
 * 仅 habitrain_core 的 SERVER_STARTED bootstrap 可 freeze()；外部调用会被忽略。
 *
 * <h2>线程契约（审核 A-06）</h2>
 * <p>与 {@link GameModeRegistry} 保持同一策略：
 * <ul>
 *   <li>写操作（{@link #register}、{@link #freeze}、{@link #resetLifecycle}）只在
 *       模组初始化 / 服务器启动 / 停服阶段由服务器线程调用；</li>
 *   <li>只读查询（{@link #getAll()}、{@link #getAllIds()}、{@link #get(String)} 等）
 *       返回<b>不可变快照</b>而非活视图，因此可以在任意线程安全遍历，
 *       不会因为并发的注册抛出 {@code ConcurrentModificationException}；</li>
 *   <li>{@code REGISTRY} 本身不是并发容器：读快照期间若有线程并发 {@code register}，
 *       仍属调用方违约（快照本身不会损坏，但结果可能不包含刚注册的项）。</li>
 * </ul>
 */
public class TaskRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("TaskRegistry");
    private static final Map<String, TaskDefinition> REGISTRY = new LinkedHashMap<>();
    private static boolean frozen = false;

    public static void register(TaskDefinition definition) {
        if (definition == null) {
            throw new NullPointerException("TaskDefinition must not be null");
        }
        if (frozen) throw new IllegalStateException("Task registry is frozen!");
        String fullId = definition.getFullId();
        if (REGISTRY.containsKey(fullId)) throw new IllegalArgumentException("Task '" + fullId + "' is already registered!");
        REGISTRY.put(fullId, definition);
    }

    public static TaskDefinition register(String modId, String taskId, Consumer<TaskDefinition.Builder> builder) {
        TaskDefinition.Builder b = new TaskDefinition.Builder(modId, taskId);
        builder.accept(b);
        TaskDefinition def = b.build();
        register(def);
        return def;
    }

    /**
     * 全部已注册任务的<b>快照</b>（审核 A-06）。
     *
     * <p>旧实现返回 {@code Collections.unmodifiableCollection(REGISTRY.values())}——
     * 那是<b>活视图</b>：遍历期间任何 {@code register} 都会抛
     * {@code ConcurrentModificationException}，与已修好的
     * {@link GameModeRegistry#getAll()} 语义不一致。
     */
    public static Collection<TaskDefinition> getAll() { return List.copyOf(REGISTRY.values()); }

    /** @return 该 fullId 的任务定义，未注册时为 {@code null}。 */
    public static @org.jetbrains.annotations.Nullable TaskDefinition get(String fullId) {
        return fullId == null ? null : REGISTRY.get(fullId);
    }

    /** 全部已注册 ID 的<b>快照</b>（审核 A-06）。 */
    public static Set<String> getAllIds() { return Set.copyOf(REGISTRY.keySet()); }

    public static boolean isRegistered(String fullId) { return fullId != null && REGISTRY.containsKey(fullId); }

    public static int size() { return REGISTRY.size(); }

    /**
     * 按 GameMode ID 查询属于某个模式的所有任务。
     * <p><b>这是注册表查询，不是派发池判据</b>：它不做分类、地图启用、可派发性过滤；
     * 需要「本局实际能派给该玩家的任务」请用
     * {@code TaskPoolBuilder.getPool(...)}（内部经 {@code isTaskAllowedForPool}）。
     * <p>注意 {@link TaskDefinition#getGameModeId()} 默认为 {@code "sre:base"}，
     * 未显式声明的下游任务都会落在这个 ID 下。
     *
     * <p><b>审核 A-06</b>：{@code gameModeId == null} 时旧实现直接
     * {@code gameModeId.equals(...)} 抛 NPE。现在显式拒绝并给出说明：
     * 「按 null 查模式」没有合理语义（不会匹配任何任务），静默返回空列表会让调用方
     * 把「传错了参数」误读成「该模式没有任务」。
     *
     * @throws NullPointerException {@code gameModeId} 为 {@code null}
     */
    public static List<TaskDefinition> getByGameMode(String gameModeId) {
        Objects.requireNonNull(gameModeId, "gameModeId must not be null (see TaskRegistry javadoc / audit A-06)");
        return REGISTRY.values().stream()
                .filter(def -> gameModeId.equals(def.getGameModeId()))
                .collect(Collectors.toList());
    }

    /**
     * 按分类查询。
     * <p><b>这是注册表查询，不是派发池判据</b>：匹配「分类相等 / 自定义分类相等 /
     * 定义为通用 ALL」，<b>不</b>考虑当前游戏模式声明的分类，也不考虑地图启用与
     * {@link TaskDefinition#isPoolEligible()}。派发池的等价判据见
     * {@code TaskPoolBuilder.isTaskAllowedForPool}（它额外把当前分类与
     * {@link GameMode#getTaskCategories()} 计入）。
     */
    public static List<TaskDefinition> getByCategory(TaskCategory category) {
        return REGISTRY.values().stream()
                .filter(def -> def.getCategory().equals(category)
                        || category.equals(def.getCustomCategory())
                        || TaskCategory.ALL.equals(def.getCategory()))
                .toList();
    }

    /**
     * Core-lifecycle only: freeze after all entrypoints have registered.
     * Callers outside habitrain_core's SERVER_STARTED bootstrap are ignored
     * (no-op + warning). Idempotent once frozen.
     */
    public static void freeze() {
        if (!CoreLifecycle.isActive()) {
            LOGGER.warn("TaskRegistry.freeze() ignored: only habitrain_core SERVER_STARTED bootstrap may freeze this registry");
            return;
        }
        frozen = true;
        CoreSpi.invalidateTaskPoolCacheAll();
    }
    public static boolean isFrozen() { return frozen; }

    /**
     * Core-lifecycle only（审核 A5）: reset the freeze flag for a fresh server instance。
     *
     * <p>集成服务器（单人 / 局域网）退出世界后 static 字段不会重置，旧实现里
     * {@code frozen} 一旦置位就永不复位，第二次进世界必然抛
     * {@code IllegalStateException: Task registry is frozen!}。现在由 core 在停服时复位；
     * 任务定义本身保留（{@code REGISTRY} 不清空），因为 entrypoint 只注册一次。
     */
    public static void resetLifecycle() {
        if (!CoreLifecycle.isActive()) {
            LOGGER.warn("TaskRegistry.resetLifecycle() ignored: only habitrain_core server lifecycle may call it");
            return;
        }
        frozen = false;
    }

    /** Test-only（包私有，见审核 B10）: allow a second {@link #register} in the same JVM. */
    static void unfreezeForTests() {
        frozen = false;
    }
}
