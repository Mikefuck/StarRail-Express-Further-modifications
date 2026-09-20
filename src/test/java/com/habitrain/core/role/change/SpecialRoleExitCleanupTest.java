package com.habitrain.core.role.change;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecialRoleExitCleanupTest {
    @Test
    void recognizesUpstreamPermanentAndFrenzyEffects() {
        assertTrue(SpecialRoleExitCleanup.matchesOwnedEffect(0, -1, true, false, false, true));
        assertTrue(SpecialRoleExitCleanup.matchesOwnedEffect(0, 700, false, false, false, false));
        assertTrue(SpecialRoleExitCleanup.matchesOwnedEffect(0, 1, false, false, false, false));
    }

    @Test
    void preservesEffectsThatDoNotMatchTheDepartingState() {
        assertFalse(SpecialRoleExitCleanup.matchesOwnedEffect(1, -1, true, false, false, true));
        assertFalse(SpecialRoleExitCleanup.matchesOwnedEffect(0, 200, true, false, false, true));
        assertFalse(SpecialRoleExitCleanup.matchesOwnedEffect(0, -1, false, false, false, true));
        assertFalse(SpecialRoleExitCleanup.matchesOwnedEffect(0, -1, true, true, false, true));
        assertFalse(SpecialRoleExitCleanup.matchesOwnedEffect(0, -1, true, false, true, true));
        assertFalse(SpecialRoleExitCleanup.matchesOwnedEffect(0, 701, false, false, false, false));
        assertFalse(SpecialRoleExitCleanup.matchesOwnedEffect(0, -1, false, false, false, false));
    }
}
