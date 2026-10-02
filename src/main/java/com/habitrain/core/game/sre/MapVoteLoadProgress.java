package com.habitrain.core.game.sre;

/** One continuous percentage across SRE's full reset, block reset and launch gate. */
final class MapVoteLoadProgress {
    private static final int FULL_RESET_END = 70;
    private static final int BLOCK_RESET_END = 90;
    private static final int FADE_END = 99;

    private boolean fullResetObserved;
    private int progress;

    int fullReset(int percent) {
        fullResetObserved = true;
        return advance(scale(percent, 0, FULL_RESET_END));
    }

    int blockReset(int percent) {
        // SRE can skip the full copy and reset only the recorded blocks.
        return advance(scale(percent, fullResetObserved ? FULL_RESET_END : 0, BLOCK_RESET_END));
    }

    int startingFade(int fade, int total) {
        int percent = (int) Math.round(fade * 100.0 / Math.max(1, total));
        return advance(scale(percent, BLOCK_RESET_END, FADE_END));
    }

    int current() {
        // Queue/scheduler gaps and environment/scene waits keep the last real progress.
        return progress;
    }

    int complete() {
        return advance(100);
    }

    private int advance(int candidate) {
        progress = Math.max(progress, candidate);
        return progress;
    }

    private static int scale(int percent, int start, int end) {
        int clamped = Math.max(0, Math.min(100, percent));
        return start + Math.round(clamped * (end - start) / 100.0f);
    }
}
