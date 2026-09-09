package com.habitrain.core.game.sre.role.sins.component;

/** Per-target eligibility is shared by the roster and the server's click validation. */
public final class SlothSleepPolicy {
    private SlothSleepPolicy() {}

    public static boolean canInduce(int completedSleepTasks, boolean sleeping, boolean inducedThisRound) {
        return completedSleepTasks >= 2 && !sleeping && !inducedThisRound;
    }
}
