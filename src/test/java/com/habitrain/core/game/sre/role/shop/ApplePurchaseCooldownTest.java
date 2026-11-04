package com.habitrain.core.game.sre.role.shop;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApplePurchaseCooldownTest {
    @Test void firstPurchaseNeedsNoWaitEvenAtRoundStart() {
        var cooldown = new ApplePurchaseCooldown();
        assertEquals(0, cooldown.remainingTicks(UUID.randomUUID(), 0));
    }

    @Test void successfulPurchaseLocksExactly120Seconds() {
        var cooldown = new ApplePurchaseCooldown();
        UUID player = UUID.randomUUID();
        cooldown.recordPurchase(player, 100);
        assertEquals(2400, cooldown.remainingTicks(player, 100));
        assertEquals(1, cooldown.remainingTicks(player, 2499));
        assertEquals(0, cooldown.remainingTicks(player, 2500));
        assertEquals(0, cooldown.remainingTicks(player, 2501));
        cooldown.recordPurchase(player, 2501);
        assertEquals(2400, cooldown.remainingTicks(player, 2501));
    }

    @Test void checksAndOtherPlayersDoNotRefreshTheDeadline() {
        var cooldown = new ApplePurchaseCooldown();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        cooldown.recordPurchase(first, 100);
        assertEquals(2300, cooldown.remainingTicks(first, 200));
        assertEquals(2300, cooldown.remainingTicks(first, 200));
        assertEquals(0, cooldown.remainingTicks(second, 200));
        cooldown.recordPurchase(second, 200);
        assertEquals(2300, cooldown.remainingTicks(first, 200));
        assertEquals(2400, cooldown.remainingTicks(second, 200));
    }
}
