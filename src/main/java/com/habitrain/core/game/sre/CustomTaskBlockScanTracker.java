package com.habitrain.core.game.sre;

/** Tracks which map owns the in-memory custom-task block snapshot. */
final class CustomTaskBlockScanTracker {

    private final long dedupWindowMs;
    private CustomTaskBlockScanKey lastCompletedKey;
    private long lastCompletedAtMs;

    CustomTaskBlockScanTracker(long dedupWindowMs) {
        this.dedupWindowMs = dedupWindowMs;
    }

    boolean isCurrent(CustomTaskBlockScanKey key, boolean cacheEmpty) {
        return !cacheEmpty && key != null && key.equals(lastCompletedKey);
    }

    boolean shouldSkipScan(CustomTaskBlockScanKey key, boolean cacheEmpty, long nowMs) {
        if (!isCurrent(key, cacheEmpty)) {
            return false;
        }
        long elapsedMs = nowMs - lastCompletedAtMs;
        return elapsedMs >= 0L && elapsedMs <= dedupWindowMs;
    }

    void markCompleted(CustomTaskBlockScanKey key, long completedAtMs) {
        lastCompletedKey = key;
        lastCompletedAtMs = completedAtMs;
    }
}
