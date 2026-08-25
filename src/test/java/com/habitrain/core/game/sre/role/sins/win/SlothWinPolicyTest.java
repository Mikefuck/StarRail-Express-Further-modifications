package com.habitrain.core.game.sre.role.sins.win;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlothWinPolicyTest {

    @Test
    void blackoutProbeIsNeverStealReady() {
        assertFalse(SlothWinPolicy.shouldDeclare("BLACKOUT", true, false, false),
                "living sloth must not steal the per-second BLACKOUT probe");
    }

    @Test
    void realFactionAndTimerProposalsAreStealReady() {
        assertTrue(SlothWinPolicy.shouldDeclare("KILLERS", true, false, false));
        assertTrue(SlothWinPolicy.shouldDeclare("PASSENGERS", true, false, false));
        assertTrue(SlothWinPolicy.shouldDeclare("TIME", true, false, false));
    }

    @Test
    void deadOrBlockedSlothDoesNotDeclare() {
        assertFalse(SlothWinPolicy.shouldDeclare("KILLERS", false, false, false));
        assertFalse(SlothWinPolicy.shouldDeclare("KILLERS", true, true, false));
        assertFalse(SlothWinPolicy.shouldDeclare("PASSENGERS", true, false, true));
        assertFalse(SlothWinPolicy.shouldDeclare("TIME", true, true, false));
    }

    @Test
    void otherProposalsAreIgnored() {
        assertFalse(SlothWinPolicy.shouldDeclare(null, true, false, false));
        assertFalse(SlothWinPolicy.shouldDeclare("LOVERS", true, false, false));
        assertFalse(SlothWinPolicy.shouldDeclare("", true, false, false));
    }
}
