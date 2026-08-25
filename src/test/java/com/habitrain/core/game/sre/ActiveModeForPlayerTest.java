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
        Optional<String> picked = ActiveModeForPlayer.pick(null, List.of("blackout"), List.of("blackout", "murder"));
        assertEquals(Optional.of("blackout"), picked);
    }

    @Test
    void pickFallsBackToTheOnlyActiveMode() {
        Optional<String> picked = ActiveModeForPlayer.pick(null, List.of(), List.of("blackout"));
        assertEquals(Optional.of("blackout"), picked);
    }

    @Test
    void pickIsEmptyWhenSeveralActiveModesAndPlayerIsInNone() {
        Optional<String> picked = ActiveModeForPlayer.pick(null, List.of(), List.of("blackout", "murder"));
        assertTrue(picked.isEmpty());
    }

    @Test
    void blackoutRoundIncludesAliveOrRoleHistory() {
        assertTrue(ActiveModeForPlayer.belongsToBlackoutRound(true, false));
        assertTrue(ActiveModeForPlayer.belongsToBlackoutRound(false, true));
        assertFalse(ActiveModeForPlayer.belongsToBlackoutRound(false, false));
    }
}
