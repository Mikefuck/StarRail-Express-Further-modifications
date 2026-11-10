package com.habitrain.core.game.sre.role.tailor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DollTailorRulesTest {
    @Test void observationsAccumulateAcrossInterruptedVisitsWithoutOverflow() {
        int progress = 0;
        for (int i = 0; i < 120; i++) progress = DollTailorRules.observe(progress, true, true);
        for (int i = 0; i < 500; i++) progress = DollTailorRules.observe(progress, false, true);
        assertEquals(120, progress);
        for (int i = 0; i < 500; i++) progress = DollTailorRules.observe(progress, true, false);
        assertEquals(120, progress, "Looking away or losing sight pauses observation without resetting progress");
        for (int i = 0; i < 180; i++) progress = DollTailorRules.observe(progress, true, true);
        assertEquals(300, progress);
        assertEquals(300, DollTailorRules.observe(progress, true, true));
    }
    @Test void requiresExactAffordabilityAndAnUnlockedPattern() {
        assertFalse(DollTailorRules.canSew(true, true, 199, false, false));
        assertTrue(DollTailorRules.canSew(true, true, 200, false, false));
        assertFalse(DollTailorRules.canSew(true, false, 500, false, false));
        assertFalse(DollTailorRules.canSew(false, true, 500, false, false));
    }
    @Test void bothAnExistingDollAndAnUnfinishedDollPreventAnotherPayment() {
        assertFalse(DollTailorRules.canSew(true, true, 500, true, false));
        assertFalse(DollTailorRules.canSew(true, true, 500, false, true));
        assertTrue(DollTailorRules.canSew(true, true, 200, false, false), "No additional cooldown after destruction");
    }
}
