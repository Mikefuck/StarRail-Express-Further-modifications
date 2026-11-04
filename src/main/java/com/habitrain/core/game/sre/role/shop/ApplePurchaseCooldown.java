package com.habitrain.core.game.sre.role.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player purchase deadlines, kept separate from apple use and item cooldowns. */
public final class ApplePurchaseCooldown {
    public static final int DURATION_TICKS = 120 * 20;

    private final Map<UUID, Long> deadlines = new HashMap<>();

    public long remainingTicks(UUID player, long now) {
        Long deadline = deadlines.get(player);
        if (deadline == null) return 0;
        if (now >= deadline) {
            deadlines.remove(player);
            return 0;
        }
        return deadline - now;
    }

    public void recordPurchase(UUID player, long now) {
        deadlines.put(player, now + DURATION_TICKS);
    }
}
