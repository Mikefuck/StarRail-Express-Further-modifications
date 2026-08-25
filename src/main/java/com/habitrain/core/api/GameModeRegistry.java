package com.habitrain.core.api;

import com.habitrain.core.game.sre.ActiveModeForPlayer;
import com.habitrain.core.game.sre.SREModeStartAdapter;
import com.habitrain.core.internal.CoreBootstrap;
import com.habitrain.core.task.TaskPoolBuilder;
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
 */
public class GameModeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameModeRegistry");
    private static final Map<String, GameMode> REGISTRY = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, GameMode> ACTIVE_MODES = new HashMap<>();
    /**
     * Passive fallback cache, including a negative result. It is valid for one level tick so
     * dozens of task instances cannot each rescan every registered mode, while an externally
     * activated passive mode is still discovered on the next tick.
     */
    private static final Map<ResourceKey<Level>, PassiveLookup> PASSIVE_CACHE = new ConcurrentHashMap<>();
    private record PassiveLookup(long gameTime, GameMode mode) {}
    private static boolean frozen = false;
    /** Last {@link MinecraftServer#getTickCount()} for which {@link #tickAll} ran. */
    private static int lastTickAllServerTick = Integer.MIN_VALUE;
    /** Per-level {@link ServerLevel#getGameTime()} already processed by {@link #tickAll}. */
    private static final Map<ResourceKey<Level>, Long> LAST_TICKED_GAME_TIME = new HashMap<>();

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

    public static GameMode get(String fullId) {
        return REGISTRY.get(fullId);
    }

    public static Collection<GameMode> getAll() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static Set<String> getAllIds() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
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
        ResourceKey<Level> levelKey = level.dimension();
        if (ACTIVE_MODES.containsKey(levelKey)) {
            throw new IllegalStateException("A GameMode is already active in " + levelKey.location());
        }
        GameMode mode = REGISTRY.get(fullId);
        if (mode == null) {
            throw new IllegalArgumentException("GameMode '" + fullId + "' is not registered");
        }
        if (SREModeStartAdapter.isSreGameBlocking(level)) {
            throw new IllegalStateException(
                    "An SRE game is already running or starting in " + levelKey.location());
        }
        ACTIVE_MODES.put(levelKey, mode);
        PASSIVE_CACHE.remove(levelKey);
        TaskPoolBuilder.invalidate(mode.getId());
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
    }

    /**
     * Stop the active GameMode in the given level. Calls onEnd + onCleanup.
     * No-op if no mode is active.
     */
    public static void stop(ServerLevel level, WinResult result) {
        ResourceKey<Level> levelKey = level.dimension();
        GameMode mode = ACTIVE_MODES.remove(levelKey);
        PASSIVE_CACHE.remove(levelKey);
        if (mode != null) {
            TaskPoolBuilder.invalidate(mode.getId());
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
     */
    public static Optional<GameMode> getActiveForLevel(ServerLevel level) {
        ResourceKey<Level> levelKey = level.dimension();
        GameMode explicit = ACTIVE_MODES.get(levelKey);
        if (explicit != null) return Optional.of(explicit);
        long gameTime = level.getGameTime();
        PassiveLookup cached = PASSIVE_CACHE.get(levelKey);
        if (cached != null && cached.gameTime == gameTime) {
            return Optional.ofNullable(cached.mode);
        }
        GameMode passive = REGISTRY.values().stream()
                .filter(m -> m.isActive(level))
                .findFirst()
                .orElse(null);
        PASSIVE_CACHE.put(levelKey, new PassiveLookup(gameTime, passive));
        return Optional.ofNullable(passive);
    }

    /**
     * JOIN/DISCONNECT lookup: the player's current level first, then any other
     * loaded level whose active round contains this UUID (rest/reconnect may be
     * in overworld while MATCH is another dimension).
     */
    public static Optional<GameMode> resolveActiveForPlayer(ServerPlayer player) {
        return ActiveModeForPlayer.resolve(player);
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
                || SREModeStartAdapter.isSreGameBlocking(level);
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
        if (!CoreBootstrap.isInBootstrap()) {
            LOGGER.warn("GameModeRegistry.freeze() ignored: only habitrain_core SERVER_STARTED bootstrap may freeze this registry");
            return;
        }
        frozen = true;
    }

    public static boolean isFrozen() { return frozen; }

    /**
     * Test-only: allow a second {@link #register} in the same JVM.
     * Production code must not unfreeze after {@link #freeze()}.
     */
    public static void unfreezeForTests() {
        frozen = false;
    }

    /** Drop per-level active/passive tables (call on server stop). */
    public static void clearActiveModes() {
        ACTIVE_MODES.clear();
        PASSIVE_CACHE.clear();
        resetTickGuards();
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

    /** Test-only: clear duplicate-tick guards between cases. */
    public static void resetTickGuards() {
        lastTickAllServerTick = Integer.MIN_VALUE;
        LAST_TICKED_GAME_TIME.clear();
    }
}
