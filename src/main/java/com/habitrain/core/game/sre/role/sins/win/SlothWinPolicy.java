package com.habitrain.core.game.sre.role.sins.win;

/**
 * Pure policy for Sloth's rewritten instant win.
 */
public final class SlothWinPolicy {

    private SlothWinPolicy() {}

    public static boolean shouldDeclare(int simultaneousSleepers,
                                        int startingPlayers,
                                        boolean slothInRealBed,
                                        boolean slothAlive,
                                        boolean alreadyTriggered) {
        return slothAlive
                && !alreadyTriggered
                && slothInRealBed
                && simultaneousSleepers >= Math.max(1, (int) Math.ceil(startingPlayers / 5.0));
    }
}
