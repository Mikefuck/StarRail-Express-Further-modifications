package com.habitrain.core.game.sre.role.shop;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static com.habitrain.core.game.sre.role.shop.SnakeFruitPricePolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class SnakeFruitPricePolicyTest {
    @Test void equalSurvivalGivesEqualOddsRegardlessOfTeamSize() {
        assertEquals(0.5, increaseChance(Faction.GOOD, 12, 24, 3, 6));
        assertEquals(0.5, increaseChance(Faction.KILLER, 12, 24, 3, 6));
    }

    @Test void disadvantagedKillerExampleAndOppositeGoodOdds() {
        assertEquals(0.4, increaseChance(Faction.KILLER, 10, 20, 3, 10), 1e-12);
        assertEquals(0.6, increaseChance(Faction.GOOD, 10, 20, 3, 10), 1e-12);
    }

    @Test void neutralAndMissingTeamsAreAlwaysFair() {
        assertEquals(0.5, increaseChance(Faction.NEUTRAL, 1, 24, 6, 6));
        assertEquals(0.5, increaseChance(Faction.GOOD, 1, 24, 0, 0));
        assertEquals(0.5, increaseChance(Faction.KILLER, 0, 0, 6, 6));
    }

    @Test void chanceIsClampedEvenForInvalidCounts() {
        assertEquals(0.1, increaseChance(Faction.GOOD, -1, 24, 100, 6), 1e-12);
        assertEquals(0.9, increaseChance(Faction.GOOD, 100, 24, -1, 6), 1e-12);
    }

    @Test void directionThresholdAndInclusiveEndpoints() {
        assertEquals(0, rollPrice(150, 0.4, 0.4, n -> 0));
        assertEquals(149, rollPrice(150, 0.4, 0.4, n -> n - 1));
        assertEquals(151, rollPrice(150, 0.4, 0.399, n -> 0));
        assertEquals(1000, rollPrice(150, 0.4, 0.399, n -> n - 1));
    }

    @Test void zeroCapAndAboveCapUseOnlyFeasibleDirection() {
        assertEquals(1, rollPrice(0, 0.5, 0.9, n -> 0));
        assertEquals(1000, rollPrice(0, 0.5, 0.9, n -> n - 1));
        assertEquals(999, rollPrice(1000, 0.5, 0.0, n -> n - 1));
        assertEquals(1000, rollPrice(2000, 0.5, 0.0, n -> n - 1));
        assertEquals(-1, rollPrice(-1, 0.5, 0.0, n -> fail("sentinel must not draw")));
    }

    @Test void allNormalPricesAndRandomDrawsStayInBounds() {
        Random random = new Random(42);
        for (int normal = 0; normal <= 2000; normal++) {
            for (int n = 0; n < 50; n++) {
                double direction = random.nextDouble();
                int price = rollPrice(normal, 0.4, direction, random::nextInt);
                assertTrue(price >= 0 && price <= 1000);
                if (normal > 0 && normal < 1000) assertEquals(direction < 0.4, price > normal);
            }
        }
    }

    @Test void eachEntryDrawsItsOwnDirectionAndPrice() {
        AtomicInteger draws = new AtomicInteger();
        int first = rollPrice(150, 0.5, 0.2, n -> { draws.incrementAndGet(); return 0; });
        int second = rollPrice(150, 0.5, 0.8, n -> { draws.incrementAndGet(); return 0; });
        assertEquals(151, first);
        assertEquals(0, second);
        assertEquals(2, draws.get());
    }

    @Test void labelsUseSignedChangesAndHandleZero() {
        assertEquals("-50%", changeLabel(200, 100));
        assertEquals("+200%", changeLabel(200, 600));
        assertEquals("-100%", changeLabel(200, 0));
        assertEquals("+∞%", changeLabel(0, 500));
        assertEquals("+1%", changeLabel(999, 1000));
        assertEquals("", changeLabel(0, 0));
    }

    @Test void scrambleChangesEveryTwoTicksThenSettlesForTheEntireRound() {
        var state = state(17, 91, 1200, 50);
        for (long tick = 1000; tick < 1200; tick += 2) {
            var first = state.offer("role", 0, "apple", tick);
            assertEquals(first, state.offer("role", 0, "apple", tick + 1));
            assertEquals(scramblePrice(17, 0, (int) ((tick - 1000) / 2)), first.quote().price());
            assertTrue(first.quote().price() >= 0 && first.quote().price() <= MAX_PRICE);
            assertFalse(state.settle(tick));
        }
        assertTrue(state.settle(1200));
        assertFalse(state.settle(1201));
        for (long tick : new long[]{1200, 1201, 90000}) {
            var fixed = state.offer("role", 0, "apple", tick);
            assertEquals(ApplePriceState.FIXED_FRAME, fixed.frame());
            assertEquals(50, fixed.quote().price());
        }
    }

    @Test void independentSeedsAndSlotsProduceManyDifferentScramblePrices() {
        var values = new java.util.HashSet<Integer>();
        for (int frame = 0; frame < 100; frame++) {
            values.add(scramblePrice(42, 0, frame));
            assertEquals(scramblePrice(42, 0, frame), scramblePrice(42, 0, frame));
        }
        assertTrue(values.size() > 85);
        assertNotEquals(scramblePrice(42, 0, 0), scramblePrice(42, 1, 0));
        assertNotEquals(scramblePrice(42, 0, 0), scramblePrice(43, 0, 0));
    }

    @Test void finalPriceDoesNotDependOnScrambleSeedOrHowOftenItWasRead() {
        var first = state(17, 91, 1200, 50);
        var second = state(771, 92, 1200, 50);
        for (long tick = 1000; tick < 1200; tick++) first.quote("role", 0, "apple", tick);
        first.settle(1200);
        second.settle(1200);
        assertEquals(first.quote("role", 0, "apple", 1200), second.quote("role", 0, "apple", 1200));
        assertNotEquals(50, scramblePrice(17, 0, 99));
        assertEquals(50, first.quote("role", 0, "apple", 10000).price());
    }

    @Test void exactDisplayedPriceIsAcceptedWithinAShortLatencyWindowOnly() {
        var state = state(17, 91, 1200, 50);
        var displayed = state.offer("role", 0, "apple", 1020);
        assertEquals(displayed.quote(), state.purchaseQuote("role", 0, "apple", 1020, 91, 10));
        assertEquals(displayed.quote(), state.purchaseQuote("role", 0, "apple", 1026, 91, 10));
        assertNull(state.purchaseQuote("role", 0, "apple", 1027, 91, 10));
        assertNull(state.purchaseQuote("role", 0, "apple", 1020, 90, 10));
        assertNull(state.purchaseQuote("role", 0, "apple", 1020, 91, 11));
        assertNull(state.purchaseQuote("role", 0, "apple", 1020, 91, -2));
        assertNull(state.purchaseQuote("role", 0, "apple", 1020, 91, Integer.MAX_VALUE));
        assertNull(state.purchaseQuote("role", 0, "apple", 1020, 91, ApplePriceState.FIXED_FRAME));
    }

    @Test void deadlineRejectsOldScrambleInsteadOfSilentlyChargingFixedPrice() {
        var state = state(17, 91, 1200, 50);
        assertNotNull(state.purchaseQuote("role", 0, "apple", 1199, 91, 99));
        assertNull(state.purchaseQuote("role", 0, "apple", 1200, 91, 99));
        state.settle(1200);
        assertNull(state.purchaseQuote("role", 0, "apple", 1201, 91, 99));
        assertEquals(50, state.purchaseQuote("role", 0, "apple", 90000, 91, -1).price());
    }

    @Test void sameItemDifferentSlotsRemainIndependentAndSignatureOrRoleChangesInvalidate() {
        var state = new ApplePriceState<String>();
        state.apply("role", 1200, 17, 91, true, Map.of(0, new ApplePriceState.Quote<>("apple", 150, 50),
                1, new ApplePriceState.Quote<>("apple", 150, 900)));
        assertEquals(50, state.quote("role", 0, "apple", 1200).price());
        assertEquals(900, state.quote("role", 1, "apple", 1200).price());
        assertNull(state.quote("new_role", 0, "apple", 1200));
        assertNull(state.quote("role", 0, "renamed_apple", 1200));
        assertNull(state.quote("role", 2, "apple", 1200));
        assertNull(state.purchaseQuote("new_role", 0, "apple", 1200, 91, -1));
        assertNull(state.purchaseQuote("role", 0, "renamed_apple", 1200, 91, -1));
    }

    @Test void reapplicationReplacesRatherThanStacksAndDoesNotMutateTheInput() {
        var state = new ApplePriceState<String>();
        var input = new HashMap<Integer, ApplePriceState.Quote<String>>();
        input.put(0, new ApplePriceState.Quote<>("apple", 100, 20));
        state.apply("role", 1200, 17, 91, false, input);
        input.clear();
        assertEquals(20, state.quotes().get(0).price());
        state.apply("role", 1300, 18, 92, false, Map.of(0, new ApplePriceState.Quote<>("apple", 100, 800)));
        assertNull(state.purchaseQuote("role", 0, "apple", 1200, 91, 50));
        assertFalse(state.settle(1299));
        assertTrue(state.settle(1300));
        assertEquals(800, state.quote("role", 0, "apple", 1300).price());
        state.clear();
        assertTrue(state.isEmpty());
        assertNull(state.quote("role", 0, "apple", 1400));
        assertNull(state.purchaseQuote("role", 0, "apple", 1400, 92, -1));
    }

    private ApplePriceState<String> state(long seed, long revision, long settlesAt, int finalPrice) {
        var state = new ApplePriceState<String>();
        state.apply("role", settlesAt, seed, revision, false,
                Map.of(0, new ApplePriceState.Quote<>("apple", 150, finalPrice)));
        return state;
    }
}
