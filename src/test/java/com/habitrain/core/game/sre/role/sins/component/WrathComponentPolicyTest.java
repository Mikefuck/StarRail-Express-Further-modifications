package com.habitrain.core.game.sre.role.sins.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WrathComponentPolicyTest {

    @Test
    void rageEffectsCapAtThreeLevels() {
        assertEquals(0, WrathPolicy.effectStacksForRage(-1));
        assertEquals(0, WrathPolicy.effectStacksForRage(0));
        assertEquals(1, WrathPolicy.effectStacksForRage(1));
        assertEquals(2, WrathPolicy.effectStacksForRage(2));
        assertEquals(3, WrathPolicy.effectStacksForRage(3));
        assertEquals(3, WrathPolicy.effectStacksForRage(99));
    }

    @Test
    void berserkThresholdIsKillerCountMinusOneWithSafeMinimum() {
        assertEquals(1, WrathPolicy.berserkThresholdForKillerCount(0));
        assertEquals(1, WrathPolicy.berserkThresholdForKillerCount(1));
        assertEquals(1, WrathPolicy.berserkThresholdForKillerCount(2));
        assertEquals(2, WrathPolicy.berserkThresholdForKillerCount(3));
        assertEquals(5, WrathPolicy.berserkThresholdForKillerCount(6));
    }
}
