package com.habitrain.core.game.sre.modifier.virtue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节制：每玩家 itemId → 上次成交价。局终 / 修饰符移除时清空。
 */
public final class TemperancePurchaseState {
    private TemperancePurchaseState() {}

    /** playerUUID → (itemId → last paid price) */
    private static final Map<UUID, Map<String, Integer>> LAST_PRICE = new ConcurrentHashMap<>();

    public static Integer getLastPrice(UUID player, String entryKey) {
        if (player == null || entryKey == null) return null;
        Map<String, Integer> map = LAST_PRICE.get(player);
        if (map == null) return null;
        return map.get(entryKey);
    }

    public static void recordPurchase(UUID player, String entryKey, int temperedBase) {
        if (player == null || entryKey == null || temperedBase < 0) return;
        LAST_PRICE.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(entryKey, temperedBase);
    }

    public static void clearPlayer(UUID player) {
        if (player != null) {
            LAST_PRICE.remove(player);
        }
    }

    public static void clearAll() {
        LAST_PRICE.clear();
    }
}
