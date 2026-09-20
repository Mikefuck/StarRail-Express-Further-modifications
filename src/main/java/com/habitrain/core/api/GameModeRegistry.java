package com.habitrain.core.api;

import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.spi.CoreLifecycle;
import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏模式注册中心。
 * DLC 模组在 onInitialize() 中通过 register() 注册自定义 GameMode。
 * 注册表仅在 habitrain_core 的 SERVER_STARTED bootstrap 中冻结；外部 freeze() 会被忽略。
 *
 * <h2>线程契约（审核 B13）</h2>
 * <p>注册相关操作（{@link #register}、{@link #freeze}、{@link #resetLifecycle}）只在
 * 启动/停服阶段由服务器线程调用。对局期的读写：
 * <ul>
 *   <li>运行期可变的两张表（{@code ACTIVE_MODES}、{@code PASSIVE_CACHE}）与 tick 守卫表
 *       已改为并发容器，{@code frozen}/{@code lastTickAllServerTick} 为 {@code volatile}，
 *       因此 {@link #getActiveForLevel} / {@link #isActiveInLevel} 等只读查询可以在任意线程
 *       安全调用；</li>
 *   <li>但 {@code start} / {@code stop} / {@code tickAll} 仍<b>只在服务器线程</b>调用，
 *       它们在并发容器上的复合操作（先查后写）不具原子性。</li>
 * </ul>
 * {@code REGISTRY} 在 freeze 之后不再写入，因此读它是安全的活视图。
 *
 * <h2>空值策略（审核 A-07）</h2>
 * <p>2.0.11 及以前同一类里两种风格并存：{@code start} / {@code stop} /
 * {@code getActiveForLevel} / {@code get} 不判空（传 {@code null} 得到 NPE，
 * 且 NPE 的位置在 {@code level.dimension()} 这种内部调用上，错误信息与真实原因相距很远），
 * 而 {@code hasExplicitActiveMode} / {@code isActiveInLevel} 判空返回 {@code false}。
 *
 * <p>现在统一为<b>两类明确语义</b>，并在 javadoc 与 {@code @Nullable} 上显式声明：
 * <ul>
 *   <li><b>询问式</b>（{@code hasExplicitActiveMode} / {@code isActiveInLevel} /
 *       {@code getActiveForLevel} / {@code resolveActiveForPlayer}）：允许 {@code null}，
 *       返回文档化的失败值（{@code false} 或 {@link Optional#empty()}）。
 *       理由：JOIN / DISCONNECT / 维度卸载等生命周期钩子天然可能拿到 {@code null}，
 *       此时「没有活跃模式」就是正确答案。</li>
 *   <li><b>动作式</b>（{@code start} / {@code stop}）：{@code null} 是编程错误，
 *       显式 {@link Objects#requireNonNull} 抛出并带上参数名，而不是让
 *       {@code level.dimension()} 在更深的地方抛一个难以定位的 NPE。</li>
 *   <li><b>查表式</b>（{@code get}）：{@code null} 视为「查不到」，返回 {@code null}。</li>
 * </ul>
 */
public class GameModeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameModeRegistry");
    private static final Map<String, GameMode> REGISTRY = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, GameMode> ACTIVE_MODES = new ConcurrentHashMap<>();
    /**
     * Passive fallback cache, including a negative result. It is valid for one level tick so
     * dozens of task instances cannot each rescan every registered mode, while an externally
     * activated passive mode is still discovered on the next tick.
     */
    private static final Map<ResourceKey<Level>, PassiveLookup> PASSIVE_CACHE = new ConcurrentHashMap<>();
    private record PassiveLookup(long gameTime, GameMode mode) {}
    private static volatile boolean frozen = false;
    /** Last {@link MinecraftServer#getTickCount()} for which {@link #tickAll} ran. */
    private static volatile int lastTickAllServerTick = Integer.MIN_VALUE;
    /** Per-level {@link ServerLevel#getGameTime()} already processed by {@link #tickAll}. */
    private static final Map<ResourceKey<Level>, Long> LAST_TICKED_GAME_TIME = new ConcurrentHashMap<>();

    public static void register(String modId, String modeId, GameMode mode) {
        if (frozen) {
            throw new IllegalStateException("GameMode registry is frozen! Register modes during mod initialization only.");
        }
        String fullId = modId + ":" + modeId;
        if (REGISTRY.containsKey(fullId)) {
            throw new IllegalArgumentException("GameMode '" + fullId + "' is already registered!");
        }
        REGISTRY.put(fullId, mode);
        LOGGER.info("Registered GameMode: {} ({})", fullId, mode.getDisplayName());
    }

    /** @return 已注册的模式，未注册（或 {@code fullId} 为 {@code null}）时为 {@code null}（审核 C5 / A-07）。 */
    public static @org.jetbrains.annotations.Nullable GameMode get(String fullId) {
        return fullId == null ? null : REGISTRY.get(fullId);
    }

    /**
     * 全部已注册模式。
     *
     * <p>审核 C5：返回<b>快照</b>。旧实现返回 {@code unmodifiableCollection(REGISTRY.values())}
     * 这个活视图——注册期结束后虽然不再变，但语义上不是快照，调用方遍历期间若发生注册会 CME。</p>
     */
    public static Collection<GameMode> getAll() {
        return List.copyOf(REGISTRY.values());
    }

    /** 全部已注册模式 ID 的<b>快照</b>（审核 C5）。 */
    public static Set<String> getAllIds() {
        return Set.copyOf(REGISTRY.keySet());
    }

    /**
     * Start a GameMode in the given level. Calls onPreStart + onStart.
     *
     * <p>{@code ACTIVE_MODES} is mutually exclusive per level: throws if this
     * registry already has a started mode here. SRE occupancy (including murder
     * / repair started via GameUtils rather than this registry) also blocks
     * {@code start} and throws.
     *
     * <p>{@link #isActiveInLevel} is true when {@code ACTIVE_MODES} contains the
     * level <em>or</em> SRE occupancy is blocking. {@link #getActiveForLevel} may
     * still return a registered mode whose {@link GameMode#isActive} is true even
     * when {@code ACTIVE_MODES} is empty (passive fallback). {@link #tickAll} only
     * ticks {@code ACTIVE_MODES} entries; it does not tick passive SRE games.
     *
     * @throws IllegalStateException if {@code ACTIVE_MODES} already has this level
     *     or SRE occupancy is blocking
     * @throws IllegalArgumentException if {@code fullId} is not registered
     */
    public static void start(String fullId, ServerLevel level) {
        Objects.requireNonNull(fullId, "fullId must not be null (audit A-07)");
        Objects.requireNonNull(level, "level must not be null (audit A-07)");
        ResourceKey<Level> levelKey = level.dimension();
        if (ACTIVE_MODES.containsKey(levelKey)) {
            throw new IllegalStateException("A GameMode is already active in " + levelKey.location());
        }
        GameMode mode = REGISTRY.get(fullId);
        if (mode == null) {
            throw new IllegalArgumentException("GameMode '" + fullId + "' is not registered");
        }
        if (CoreSpi.isSreGameBlocking(level)) {
            throw new IllegalStateException(
                    "An SRE game is already running or starting in " + levelKey.location());
        }
        ACTIVE_MODES.put(levelKey, mode);
        PASSIVE_CACHE.remove(levelKey);
        CoreSpi.invalidateTaskPoolCache(mode.getId());
        try {
            mode.onPreStart(level);
            mode.onStart(level);
            LOGGER.info("Started GameMode: {} in {}", fullId, levelKey.location());
        } catch (Throwable t) {
            ACTIVE_MODES.remove(levelKey);
            try {
                mode.onCleanup(level);
            } catch (Exception cleanupError) {
                LOGGER.error("GameMode cleanup failed after start error for {}", fullId, cleanupError);
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(t);
        }
        // 审核 A7：注册表启动的模式过去完全不触发 MatchEvents，导致按教程实现的第三方
        // 模式收不到开局重置钩子。现在与上游 SRE 对局一样发 STARTED。
        fireStarted(level);
    }

    /**
     * Stop the active GameMode in the given level. Calls onEnd + onCleanup.
     * No-op if no mode is active.
     *
     * @throws NullPointerException {@code level} 为 {@code null}（审核 A-07：动作式方法显式判空）
     */
    public static void stop(ServerLevel level, WinResult result) {
        Objects.requireNonNull(level, "level must not be null (audit A-07)");
        ResourceKey<Level> levelKey = level.dimension();
        GameMode mode = ACTIVE_MODES.remove(levelKey);
        PASSIVE_CACHE.remove(levelKey);
        if (mode != null) {
            CoreSpi.invalidateTaskPoolCache(mode.getId());
            // 用 try/finally 保证 onEnd 抛异常时 onCleanup 仍会执行，
            // 避免 per-level 状态（角色/计时器/商店等 manager 的 map 条目）因异常泄漏。
            try {
                mode.onEnd(level, result);
            } catch (RuntimeException e) {
                LOGGER.error("GameMode onEnd failed for {} in {}", mode.getId(), levelKey.location(), e);
            } finally {
                try {
                    mode.onCleanup(level);
                } catch (RuntimeException cleanupError) {
                    LOGGER.error("GameMode onCleanup failed for {} in {}", mode.getId(), levelKey.location(), cleanupError);
                }
            }
            LOGGER.info("Stopped GameMode: {} in {} (result: {})",
                    mode.getId(), levelKey.location(), result.getReason());
            // 审核 A7：与 STARTED 对称地补发结算事件。注册表模式的胜者/参与者集合不可知，
            // 因此 settlement 只携带模式 ID 与结果原因（winners/participants 均为空集），
            // 消费方用它做「跨局状态重置」，不要据此发奖。
            fireRoundEnded(level, mode, result);
        }
    }

    /** 审核 A7：把注册表启动的对局广播给 {@link MatchEvents} 订阅者。 */
    private static void fireStarted(ServerLevel level) {
        try {
            MatchEvents.STARTED.invoker().onMatchStarted(level);
        } catch (Throwable t) {
            LOGGER.error("MatchEvents.STARTED dispatch failed for {}", level.dimension().location(), t);
        }
    }

    /** 审核 A7：结算事件的空集 settlement（见 {@link #stop(ServerLevel, WinResult)} 注释）。 */
    private static void fireRoundEnded(ServerLevel level, GameMode mode, WinResult result) {
        try {
            MatchSettlement settlement = new MatchSettlement(
                    mode.getId(),
                    MatchWinKind.NONE,
                    level.dimension().location() + "|" + level.getGameTime() + "|" + mode.getId(),
                    Set.of(),
                    Set.of(),
                    Map.of());
            MatchEvents.ROUND_ENDED.invoker().onRoundEnded(level, settlement);
        } catch (Throwable t) {
            LOGGER.error("MatchEvents.ROUND_ENDED dispatch failed for {}", level.dimension().location(), t);
        } finally {
            // result 只用于日志；显式引用避免「未使用参数」的误读。
            if (result == null) {
                LOGGER.debug("GameMode {} stopped with null WinResult", mode.getId());
            }
        }
    }

    /**
     * Stop the active GameMode in the given level with a default force-end result.
     * Delegates to {@link #stop(ServerLevel, WinResult)}.
     */
    public static void stop(ServerLevel level) {
        stop(level, WinResult.forceEnd("管理员终止"));
    }

    /**
     * Tick {@code ACTIVE_MODES} only (not passive SRE occupancy). Call from
     * {@code ServerTickEvents.END_SERVER_TICK}. Duplicate or re-entrant calls in
     * the same server tick, or the same {@code level.getGameTime()}, are ignored.
     */
    public static void tickAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!beginServerTick(server.getTickCount())) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> levelKey = level.dimension();
            if (!beginLevelTick(levelKey, level.getGameTime())) {
                continue;
            }
            GameMode mode = ACTIVE_MODES.get(levelKey);
            if (mode != null) {
                try {
                    mode.onTick(level);
                    if (ACTIVE_MODES.get(levelKey) == mode) {
                        Optional<WinResult> result = mode.checkWinCondition(level);
                        if (result.isPresent() && ACTIVE_MODES.get(levelKey) == mode) {
                            stop(level, result.get());
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("GameMode {} tick failed in {}", mode.getId(), levelKey.location(), t);
                }
            }
        }
    }

    /**
     * Get the active GameMode in a level (also checks passive {@link GameMode#isActive}
     * as fallback). Passive results are cached per level and invalidated on start/stop.
     * This can see passive {@code isActive()} modes even when {@code ACTIVE_MODES} is
     * empty; it does not by itself mean {@link #start} would succeed (SRE occupancy
     * still blocks). {@link #tickAll} never ticks these passive matches.
     *
     * <p>审核 A4：当同一维度有<b>多个</b>被动模式的 {@code isActive()} 同时为真时，
     * 结果取决于注册顺序（上游 {@code SREOriginalModeBridge} 按扫描顺序注册），
     * 第三方无法控制。此时会 {@code WARN} 并列出全部候选，便于诊断。</p>
     */
    public static Optional<GameMode> getActiveForLevel(ServerLevel level) {
        if (level == null) {
            return Optional.empty();
        }
        ResourceKey<Level> levelKey = level.dimension();
        GameMode explicit = ACTIVE_MODES.get(levelKey);
        if (explicit != null) return Optional.of(explicit);
        long gameTime = level.getGameTime();
        PassiveLookup cached = PASSIVE_CACHE.get(levelKey);
        if (cached != null && cached.gameTime == gameTime) {
            return Optional.ofNullable(cached.mode);
        }
        List<GameMode> matches = REGISTRY.values().stream()
                .filter(m -> m.isActive(level))
                .toList();
        GameMode passive = matches.isEmpty() ? null : matches.get(0);
        if (matches.size() > 1) {
            LOGGER.warn("Multiple passive GameModes are active in {} ({}); '{}' wins by registration order: {}",
                    levelKey.location(), matches.size(), passive != null ? passive.getId() : "?",
                    matches.stream().map(GameMode::getId).toList());
        }
        PASSIVE_CACHE.put(levelKey, new PassiveLookup(gameTime, passive));
        return Optional.ofNullable(passive);
    }

    /**
     * {@code true} when {@link #start} registered a mode for this level (i.e. the
     * {@code ACTIVE_MODES} entry exists). Unlike {@link #getActiveForLevel} this
     * never falls back to passive {@code isActive()} guesses, so it is a reliable
     * "a core GameMode round is running here" predicate（审核 A7）。
     */
    public static boolean hasExplicitActiveMode(ServerLevel level) {
        return level != null && ACTIVE_MODES.containsKey(level.dimension());
    }

    /**
     * JOIN/DISCONNECT lookup: the player's current level first, then any other
     * loaded level whose active round contains this UUID (rest/reconnect may be
     * in overworld while MATCH is another dimension).
     */
    public static Optional<GameMode> resolveActiveForPlayer(ServerPlayer player) {
        return CoreSpi.resolveActiveForPlayer(player);
    }

    /**
     * {@code true} if this registry has an {@code ACTIVE_MODES} entry for the
     * level or an SRE game is occupying it. Does not include purely passive
     * {@link GameMode#isActive} modes (those are only visible via
     * {@link #getActiveForLevel}).
     */
    public static boolean isActiveInLevel(ServerLevel level) {
        if (level == null) {
            return false;
        }
        return ACTIVE_MODES.containsKey(level.dimension())
                || CoreSpi.isSreGameBlocking(level);
    }

    public static boolean isRegistered(String fullId) {
        return REGISTRY.containsKey(fullId);
    }

    public static int size() { return REGISTRY.size(); }

    /**
     * Core-lifecycle only: freeze after all entrypoints have registered.
     * Callers outside habitrain_core's SERVER_STARTED bootstrap are ignored
     * (no-op + warning). Idempotent once frozen.
     */
    public static void freeze() {
        if (!CoreLifecycle.isActive()) {
            LOGGER.warn("GameModeRegistry.freeze() ignored: only habitrain_core SERVER_STARTED bootstrap may freeze this registry");
            return;
        }
        frozen = true;
    }

    public static boolean isFrozen() { return frozen; }

    /**
     * Test-only（包私有，见审核 B10）: allow a second {@link #register} in the same JVM.
     * Production code must not unfreeze after {@link #freeze()}.
     */
    static void unfreezeForTests() {
        frozen = false;
    }

    /** Drop per-level active/passive tables (call on server stop). */
    public static void clearActiveModes() {
        ACTIVE_MODES.clear();
        PASSIVE_CACHE.clear();
        resetTickGuards();
    }

    /**
     * Core-lifecycle only（审核 A5）: reset the registry lifecycle for a fresh server instance.
     *
     * <p>{@code onInitialize()} 只跑一次，而集成服务器（单人 / 局域网）退出世界后
     * <b>不会</b>重置 static 字段：旧实现里 {@code frozen} 一旦置位就永不复位，
     * 于是「第一次进世界成功、第二次进世界必然抛
     * {@code IllegalStateException: GameMode registry is frozen!}」，而报错信息与真实原因
     * （跨服务器实例残留）相距很远。现在由 core 在停服时复位。
     *
     * <p>{@code REGISTRY} <b>不</b>在此清空——模组 entrypoint 只会注册一次，
     * 清掉会让第二次进世界时注册表为空。
     */
    public static void resetLifecycle() {
        if (!CoreLifecycle.isActive()) {
            LOGGER.warn("GameModeRegistry.resetLifecycle() ignored: only habitrain_core server lifecycle may call it");
            return;
        }
        frozen = false;
        clearActiveModes();
    }

    /** @return true if this server tick has not been processed yet and is now marked. */
    static boolean beginServerTick(int serverTick) {
        if (serverTick == lastTickAllServerTick) {
            return false;
        }
        lastTickAllServerTick = serverTick;
        return true;
    }

    /** @return true if this level/gameTime has not been processed yet and is now marked. */
    static boolean beginLevelTick(ResourceKey<Level> levelKey, long gameTime) {
        if (levelKey == null) {
            return false;
        }
        Long prev = LAST_TICKED_GAME_TIME.get(levelKey);
        if (prev != null && prev == gameTime) {
            return false;
        }
        LAST_TICKED_GAME_TIME.put(levelKey, gameTime);
        return true;
    }

    /** Test-only（包私有，见审核 B10）: clear duplicate-tick guards between cases. */
    static void resetTickGuards() {
        lastTickAllServerTick = Integer.MIN_VALUE;
        LAST_TICKED_GAME_TIME.clear();
    }
}
