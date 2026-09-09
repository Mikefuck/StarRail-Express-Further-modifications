package com.habitrain.core.game.sre.role.sins.win;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlothWinPolicyTest {

    @Test
    void populationThresholdRoundsUpAndRequiresAtLeastOneSleeper() {
        assertTrue(SlothWinPolicy.shouldDeclare(2, 10, true, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(2, 11, true, true, false));
        assertTrue(SlothWinPolicy.shouldDeclare(3, 11, true, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(3, 16, true, true, false));
        assertTrue(SlothWinPolicy.shouldDeclare(4, 16, true, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(0, 0, true, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(0, 1, true, true, false));
    }

    @Test
    void oneFifthOfFifteenPlayersAndRealBedAreReady() {
        assertTrue(SlothWinPolicy.shouldDeclare(3, 15, true, true, false));
        assertTrue(SlothWinPolicy.shouldDeclare(4, 15, true, true, false));
    }

    @Test
    void fewerThanOneFifthCannotWin() {
        assertFalse(SlothWinPolicy.shouldDeclare(0, 15, true, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(2, 15, true, true, false));
    }

    @Test
    void requiresLivingSlothRealBedAndOneShotGate() {
        assertFalse(SlothWinPolicy.shouldDeclare(3, 15, false, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(3, 15, true, false, false));
        assertFalse(SlothWinPolicy.shouldDeclare(3, 15, true, true, true));
    }
}
