package com.habitrain.core.game.sre.role.idiot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdiotRulesTest {
    @Test void exactlyTwoPercentBeforeTheGuarantee() {
        var luck = new IdiotRules.Luck();
        assertTrue(luck.wouldHatch(.019999));
        assertFalse(luck.wouldHatch(.02));
        assertFalse(luck.wouldHatch(.999));
    }
    @Test void sixtiethValidImpactIsGuaranteedAfter59Failures() {
        var luck = new IdiotRules.Luck();
        for (int i = 0; i < 59; i++) {
            assertFalse(luck.wouldHatch(.99));
            assertEquals(60 - i, luck.remaining());
            luck.record(false);
        }
        assertEquals(1, luck.remaining());
        assertTrue(luck.wouldHatch(.99));
        luck.record(true);
        assertEquals(0, luck.remaining());
        assertTrue(luck.firstHatched());
    }
    @Test void naturalFirstSuccessDisablesAnyLaterGuarantee() {
        var luck = new IdiotRules.Luck();
        luck.record(true);
        for (int i = 0; i < 200; i++) {
            assertFalse(luck.wouldHatch(.99));
            luck.record(false);
        }
        assertEquals(0, luck.remaining());
        assertTrue(luck.wouldHatch(.01));
    }
    @Test void failedEntityCreationCannotLoseOrOverflowTheGuarantee() {
        var luck = new IdiotRules.Luck();
        for (int i = 0; i < 100; i++) luck.record(false);
        assertEquals(1, luck.remaining());
        assertTrue(luck.wouldHatch(.999));
    }
    @Test void anotherHatchRefreshesTenSecondsWithoutAddingTime() {
        assertEquals(300, IdiotRules.refreshBoost(0, 100));
        assertEquals(350, IdiotRules.refreshBoost(300, 150));
        assertEquals(600, IdiotRules.refreshBoost(600, 150));
    }
    @Test void existingVanillaSpeedNeverStacksWithTheReward() {
        assertEquals(.4, IdiotRules.speedMultiplier(-1), .000001);
        assertEquals(1.4, 1.2 * (1 + IdiotRules.speedMultiplier(0)), .000001);
        assertEquals(0, IdiotRules.speedMultiplier(1));
        assertEquals(0, IdiotRules.speedMultiplier(3));
    }
}
