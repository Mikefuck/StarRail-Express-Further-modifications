package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreConsumableTaskPolicyTest {

    @Test
    void coreGloballyReplacesOnlyUpstreamEatAndDrink() {
        assertTrue(CoreConsumableTaskPolicy.replacesUpstream("EAT"));
        assertTrue(CoreConsumableTaskPolicy.replacesUpstream("DRINK"));
        assertFalse(CoreConsumableTaskPolicy.replacesUpstream("SLEEP"));
        assertFalse(CoreConsumableTaskPolicy.replacesUpstream(null));
    }
}
