package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveModeForPlayerTest {

    @Test
    void pickPrefersCurrentLevelEvenWhenAnotherRoundMatchExists() {
        Optional<String> picked = ActiveModeForPlayer.pick("overworld", List.of("match"), List.of("match"));
        assertEquals(Optional.of("overworld"), picked);
    }

    @Test
    void pickUsesRoundMembershipWhenCurrentLevelHasNoMode() {
        Optional<String> picked = ActiveModeForPlayer.pick(null, List.of("repair"), List.of("repair", "murder"));
        assertEquals(Optional.of("repair"), picked);
    }

    @Test
    void pickFallsBackToTheOnlyActiveMode() {
        Optional<String> picked = ActiveModeForPlayer.pick(null, List.of(), List.of("repair"));
        assertEquals(Optional.of("repair"), picked);
    }

    @Test
    void pickIsEmptyWhenSeveralActiveModesAndPlayerIsInNone() {
        Optional<String> picked = ActiveModeForPlayer.pick(null, List.of(), List.of("repair", "murder"));
        assertTrue(picked.isEmpty());
    }

}
