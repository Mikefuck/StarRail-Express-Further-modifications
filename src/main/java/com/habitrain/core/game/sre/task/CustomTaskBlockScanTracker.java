package com.habitrain.core.game.sre.task;

/** Tracks which map owns the in-memory custom-task block snapshot. */
final class CustomTaskBlockScanTracker {

    private final long dedupWindowMs;
    private CustomTaskBlockScanKey lastCompletedKey;
    private long lastCompletedAtMs;
    private boolean completedEmpty;

    CustomTaskBlockScanTracker(long dedupWindowMs) {
        this.dedupWindowMs = dedupWindowMs;
    }

    boolean isCurrent(CustomTaskBlockScanKey key, boolean cacheEmpty) {
        return key != null && cacheEmpty == completedEmpty && key.equals(lastCompletedKey);
    }

    boolean shouldSkipScan(CustomTaskBlockScanKey key, boolean cacheEmpty, long nowMs) {
        if (!isCurrent(key, cacheEmpty)) {
            return false;
        }
        long elapsedMs = nowMs - lastCompletedAtMs;
        return elapsedMs >= 0L && elapsedMs <= dedupWindowMs;
    }

    void markCompleted(CustomTaskBlockScanKey key, long completedAtMs) {
        markCompleted(key, completedAtMs, false);
    }

    void markCompleted(CustomTaskBlockScanKey key, long completedAtMs, boolean empty) {
        lastCompletedKey = key;
        lastCompletedAtMs = completedAtMs;
        completedEmpty = empty;
    }
}
