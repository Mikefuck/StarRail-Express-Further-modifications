package com.habitrain.core.api;

import net.minecraft.server.level.ServerLevel;
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
     * 查找在指定世界激活的 GameMode。
     * 如果多个模式同时激活，返回第一个匹配的。
     */
    public static Optional<GameMode> getActiveForLevel(ServerLevel level) {
        return REGISTRY.values().stream()
                .filter(m -> m.isActive(level))
                .findFirst();
    }

    public static boolean isRegistered(String fullId) {
        return REGISTRY.containsKey(fullId);
    }

    public static int size() { return REGISTRY.size(); }

    public static void freeze() { frozen = true; }

    public static boolean isFrozen() { return frozen; }
}
