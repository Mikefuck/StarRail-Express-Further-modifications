package com.habitrain.core.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 游戏模式注册中心。
 * DLC 模组在 onInitialize() 中通过 register() 注册自定义 GameMode。
 * 注册表在模组加载完成后冻结。
 */
public class GameModeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameModeRegistry");
    private static final Map<String, GameMode> REGISTRY = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, GameMode> ACTIVE_MODES = new HashMap<>();
    private static boolean frozen = false;

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
     * Throws if another mode is already active in this level.
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
        ACTIVE_MODES.put(levelKey, mode);
        try {
            mode.onPreStart(level);
            mode.onStart(level);
            LOGGER.info("Started GameMode: {} in {}", fullId, levelKey.location());
        } catch (RuntimeException e) {
            ACTIVE_MODES.remove(levelKey);
            try {
                mode.onCleanup(level);
            } catch (Exception cleanupError) {
                LOGGER.error("GameMode cleanup failed after start error for {}", fullId, cleanupError);
            }
            throw e;
        }
    }

    /**
     * Stop the active GameMode in the given level. Calls onEnd + onCleanup.
     * No-op if no mode is active.
     */
    public static void stop(ServerLevel level, WinResult result) {
        ResourceKey<Level> levelKey = level.dimension();
        GameMode mode = ACTIVE_MODES.remove(levelKey);
        if (mode != null) {
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
     * Tick all active GameModes. Call from ServerTickEvents.END_SERVER_TICK.
     */
    public static void tickAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> levelKey = level.dimension();
            GameMode mode = ACTIVE_MODES.get(levelKey);
            if (mode != null) {
                mode.onTick(level);
            }
        }
    }

    /**
     * Get the active GameMode in a level (also checks passive isActive() as fallback).
     */
    public static Optional<GameMode> getActiveForLevel(ServerLevel level) {
        ResourceKey<Level> levelKey = level.dimension();
        GameMode explicit = ACTIVE_MODES.get(levelKey);
        if (explicit != null) return Optional.of(explicit);
        // fallback: passive check
        return REGISTRY.values().stream()
                .filter(m -> m.isActive(level))
                .findFirst();
    }

    public static boolean isActiveInLevel(ServerLevel level) {
        return ACTIVE_MODES.containsKey(level.dimension());
    }

    public static boolean isRegistered(String fullId) {
        return REGISTRY.containsKey(fullId);
    }

    public static int size() { return REGISTRY.size(); }

    public static void freeze() { frozen = true; }

    public static boolean isFrozen() { return frozen; }
}
