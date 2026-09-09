package com.habitrain.core.game.sre.role.sins.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlothSleepPolicyTest {
    @Test
    void needsTwoCompletedSleepTasks() {
        assertFalse(SlothSleepPolicy.canInduce(0, false, false));
        assertFalse(SlothSleepPolicy.canInduce(1, false, false));
        assertTrue(SlothSleepPolicy.canInduce(2, false, false));
    }

    @Test
    void wakingAndCompletingMoreTasksCannotEnableASecondInduction() {
        assertFalse(SlothSleepPolicy.canInduce(2, true, true));
        assertFalse(SlothSleepPolicy.canInduce(2, false, true));
        assertFalse(SlothSleepPolicy.canInduce(100, false, true));
        assertTrue(SlothSleepPolicy.canInduce(2, false, false)); // new round, new eligibility
    }
}
