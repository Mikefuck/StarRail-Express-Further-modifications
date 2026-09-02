package com.habitrain.core.game.sre;

/** Pure decision used by the map-vote loading gate and unit tests. */
public final class ScenePreloadReleasePolicy {
    public static final long RESET_COMPLETE_TIMEOUT_TICKS = 5L * 20L;

    private ScenePreloadReleasePolicy() {}

    public static boolean shouldRelease(boolean environmentReady, boolean allPlayersReady,
                                        long currentTick, long deadlineTick) {
        if (!environmentReady) return false;
        return allPlayersReady || deadlineTick >= 0L && currentTick >= deadlineTick;
    }
}
