package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapVoteLoadProgressTest {
    @Test
    void fullCopyBlockResetAndFadeShareOneContinuousProgressBar() {
        MapVoteLoadProgress progress = new MapVoteLoadProgress();

        assertEquals(0, progress.current());
        assertEquals(35, progress.fullReset(50));
        assertEquals(70, progress.fullReset(100));
        // SRE creates a new reset task with a fresh counter after the full copy.
        assertEquals(70, progress.blockReset(0));
        assertEquals(80, progress.blockReset(50));
        assertEquals(90, progress.blockReset(100));
        assertEquals(90, progress.current()); // five-tick scheduler gap
        assertEquals(90, progress.startingFade(0, 60));
        assertEquals(95, progress.startingFade(30, 60));
        assertEquals(99, progress.startingFade(60, 60));
        assertEquals(99, progress.current()); // environment/scene gate still pending
        assertEquals(100, progress.complete());
    }

    @Test
    void blockOnlyAndNoResetMapsCanSkipTheFullCopy() {
        MapVoteLoadProgress blockOnly = new MapVoteLoadProgress();
        assertEquals(45, blockOnly.blockReset(50));
        assertEquals(90, blockOnly.blockReset(100));
        assertEquals(90, blockOnly.startingFade(0, 60));

        MapVoteLoadProgress noReset = new MapVoteLoadProgress();
        assertEquals(90, noReset.startingFade(0, 60));
        assertEquals(99, noReset.startingFade(60, 60));
        assertEquals(100, noReset.complete());
    }

    @Test
    void lowerCountersAndQueueGapsCannotRewindTheCurrentLoad() {
        MapVoteLoadProgress progress = new MapVoteLoadProgress();
        assertEquals(56, progress.fullReset(80));
        assertEquals(56, progress.fullReset(20));
        assertEquals(56, progress.current());
        assertEquals(86, progress.blockReset(80));
        assertEquals(86, progress.blockReset(0));
        assertEquals(99, progress.startingFade(60, 60));
        assertEquals(99, progress.startingFade(0, 60));
        assertEquals(100, progress.complete());
        assertEquals(100, progress.blockReset(0));
        assertEquals(0, new MapVoteLoadProgress().current());
    }

    @Test
    void clampsInvalidUpstreamCountersWithoutCompletingBeforeTheGate() {
        MapVoteLoadProgress progress = new MapVoteLoadProgress();
        assertEquals(0, progress.fullReset(-10));
        assertEquals(70, progress.fullReset(110));
        assertEquals(70, progress.blockReset(-10));
        assertEquals(90, progress.blockReset(110));
        assertEquals(90, progress.startingFade(-1, 0));
        assertEquals(99, progress.startingFade(100, 0));
    }
}
