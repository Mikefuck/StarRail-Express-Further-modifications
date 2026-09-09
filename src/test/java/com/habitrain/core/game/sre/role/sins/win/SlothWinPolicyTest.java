package com.habitrain.core.game.sre.role.sins.win;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlothWinPolicyTest {

    @Test
    void exactlyThreeSleepersAndRealBedAreReady() {
        assertTrue(SlothWinPolicy.shouldDeclare(3, true, true, false));
        assertTrue(SlothWinPolicy.shouldDeclare(4, true, true, false));
    }

    @Test
    void fewerThanThreeSleepersCannotWin() {
        assertFalse(SlothWinPolicy.shouldDeclare(0, true, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(2, true, true, false));
    }

    @Test
    void requiresLivingSlothRealBedAndOneShotGate() {
        assertFalse(SlothWinPolicy.shouldDeclare(3, false, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare(3, true, false, false));
        assertFalse(SlothWinPolicy.shouldDeclare(3, true, true, true));
    }
}
