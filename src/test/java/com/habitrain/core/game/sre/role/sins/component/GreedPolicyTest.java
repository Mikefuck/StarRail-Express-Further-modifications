package com.habitrain.core.game.sre.role.sins.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreedPolicyTest {
    @Test
    void collectionRequiresRoundedUpThirdMinusOneWithMinimumOne() {
        assertEquals(3, GreedPolicy.collectionTarget(12));
        assertEquals(4, GreedPolicy.collectionTarget(13));
        assertEquals(4, GreedPolicy.collectionTarget(14));
        assertEquals(4, GreedPolicy.collectionTarget(15));
        assertEquals(5, GreedPolicy.collectionTarget(16));
        assertEquals(1, GreedPolicy.collectionTarget(0));
        assertEquals(1, GreedPolicy.collectionTarget(3));
        assertEquals(1, GreedPolicy.collectionTarget(1));
    }

    @Test
    void splittingIncomeIntoSingleCoinsCannotEvadeTax() {
        for (boolean innocent : new boolean[]{false, true}) {
            int remainder = 0;
            int total = 0;
            for (int i = 0; i < 101; i++) {
                var share = GreedPolicy.incomeShare(1, innocent, remainder);
                total += share.coins();
                remainder = share.remainder();
            }
            assertEquals(GreedPolicy.incomeShare(101, innocent, 0),
                    new GreedPolicy.IncomeShare(total, remainder));
            assertEquals(innocent ? 50 : 25, total);
        }
    }

    @Test
    void factionChangePreservesFractionalIncomeAndLargeRewardsDoNotOverflow() {
        var neutral = GreedPolicy.incomeShare(3, false, 0);
        var civilian = GreedPolicy.incomeShare(1, true, neutral.remainder());
        assertEquals(new GreedPolicy.IncomeShare(1, 1), civilian);
        assertEquals(1073741823, GreedPolicy.incomeShare(Integer.MAX_VALUE, true, 0).coins());
    }

    @Test
    void estateConservesEveryCoinAndSharesDifferByAtMostOne() {
        for (int coins : new int[]{0, 2, 10, 101, Integer.MAX_VALUE}) {
            int total = 0;
            int minimum = Integer.MAX_VALUE;
            int maximum = 0;
            for (int i = 0; i < 7; i++) {
                int share = GreedPolicy.estateShare(coins, 7, i);
                total += share;
                minimum = Math.min(minimum, share);
                maximum = Math.max(maximum, share);
            }
            assertEquals(coins, total);
            assertTrue(maximum - minimum <= 1);
        }
        assertEquals(0, GreedPolicy.estateShare(100, 0, 0));
    }

    @Test
    void pricesUseDoubleUnitCostAndRejectInvalidOrOverflowingEntries() {
        assertEquals(600, GreedPolicy.doubledUnitPrice(300, 1));
        assertEquals(5, GreedPolicy.doubledUnitPrice(10, 4));
        assertEquals(3, GreedPolicy.doubledUnitPrice(5, 4));
        assertEquals(0, GreedPolicy.doubledUnitPrice(0, 1));
        assertEquals(-1, GreedPolicy.doubledUnitPrice(-1, 1));
        assertEquals(-1, GreedPolicy.doubledUnitPrice(100, 0));
        assertEquals(-1, GreedPolicy.doubledUnitPrice(Integer.MAX_VALUE, 1));
    }
}
