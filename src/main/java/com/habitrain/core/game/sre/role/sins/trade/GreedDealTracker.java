package com.habitrain.core.game.sre.role.sins.trade;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 贪婪匿名交易：每局按 level 记录各 itemId 成交次数（0–3），驱动买卖价格。
 * <p>
 * 卖价 = 30 + 30 * n；买价 = max(30, 300 - 30 * n)；n 为本局该 itemId 已成交次数。
 */
public final class GreedDealTracker {
    public static final int MAX_DEALS = 3;
    public static final int BASE_PRICE = 30;
    public static final int BUY_CEILING = 300;

    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<String, Integer>> BY_LEVEL =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<UUID, Set<String>>> SOLD_BY_GREED =
            new ConcurrentHashMap<>();

    private GreedDealTracker() {}

    public static int getDealCount(@Nullable ServerLevel level, @Nullable String itemId) {
        if (level == null || itemId == null || itemId.isEmpty()) return 0;
        ConcurrentHashMap<String, Integer> map = BY_LEVEL.get(level.dimension());
        if (map == null) return 0;
        return Math.min(MAX_DEALS, Math.max(0, map.getOrDefault(itemId, 0)));
    }

    public static int sellPrice(int n) {
        int clamped = Math.min(MAX_DEALS, Math.max(0, n));
        return BASE_PRICE + BASE_PRICE * clamped;
    }

    public static int buyPrice(int n) {
        int clamped = Math.min(MAX_DEALS, Math.max(0, n));
        return Math.max(BASE_PRICE, BUY_CEILING - BASE_PRICE * clamped);
    }

    /**
     * Increment deal count after successful commit. Caps at {@link #MAX_DEALS}.
     *
     * @return new count after increment (or current if already capped)
     */
    public static int recordDeal(@Nullable ServerLevel level, @Nullable String itemId) {
        if (level == null || itemId == null || itemId.isEmpty()) return 0;
        ConcurrentHashMap<String, Integer> map =
                BY_LEVEL.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        return map.compute(itemId, (k, v) -> {
            int cur = v == null ? 0 : v;
            if (cur >= MAX_DEALS) return MAX_DEALS;
            return cur + 1;
        });
    }

    public static boolean hasSold(@Nullable ServerLevel level, @Nullable UUID greedId,
                                  @Nullable String itemId) {
        if (level == null || greedId == null || itemId == null) return false;
        Map<UUID, Set<String>> byPlayer = SOLD_BY_GREED.get(level.dimension());
        return byPlayer != null && byPlayer.getOrDefault(greedId, Set.of()).contains(itemId);
    }

    public static void recordSale(@Nullable ServerLevel level, @Nullable UUID greedId,
                                  @Nullable String itemId) {
        if (level == null || greedId == null || itemId == null || itemId.isEmpty()) return;
        SOLD_BY_GREED.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(greedId, ignored -> ConcurrentHashMap.newKeySet())
                .add(itemId);
    }

    public static void clear(@Nullable ServerLevel level) {
        if (level == null) return;
        BY_LEVEL.remove(level.dimension());
        SOLD_BY_GREED.remove(level.dimension());
    }

    public static void clearAll() {
        BY_LEVEL.clear();
        SOLD_BY_GREED.clear();
    }

    public static Map<String, Integer> snapshot(@Nullable ServerLevel level) {
        if (level == null) return Map.of();
        ConcurrentHashMap<String, Integer> map = BY_LEVEL.get(level.dimension());
        if (map == null || map.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(map));
    }
}
