package com.habitrain.core.game.sre.modifier.virtue;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节制：每玩家 itemId → 上次成交价。局终 / 修饰符移除时清空。
 */
public final class TemperancePurchaseState {
    private TemperancePurchaseState() {}

    /** playerUUID → (itemId → last paid price) */
    private static final Map<UUID, Map<ResourceLocation, Integer>> LAST_PRICE = new ConcurrentHashMap<>();

    public static Integer getLastPrice(UUID player, ResourceLocation itemId) {
        if (player == null || itemId == null) return null;
        Map<ResourceLocation, Integer> map = LAST_PRICE.get(player);
        if (map == null) return null;
        return map.get(itemId);
    }

    public static void recordPurchase(UUID player, ResourceLocation itemId, int pricePaid) {
        if (player == null || itemId == null || pricePaid < 0) return;
        LAST_PRICE.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(itemId, pricePaid);
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
