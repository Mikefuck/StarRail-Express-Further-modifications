package com.habitrain.core.scene.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneCaptureAccountingTest {
    @Test
    void definitiveOutcomesMustExactlyMatchExpectedSectionCount() {
        SceneCaptureAccounting accounting = new SceneCaptureAccounting();
        accounting.record(SceneCaptureAccounting.SectionOutcome.CAPTURED);
        accounting.record(SceneCaptureAccounting.SectionOutcome.EMPTY);
        accounting.record(SceneCaptureAccounting.SectionOutcome.OUTSIDE_BUILD_HEIGHT);

        assertEquals(1, accounting.capturedSections());
        assertEquals(1, accounting.emptySections());
        assertEquals(1, accounting.outsideBuildHeightSections());
        assertEquals(3, accounting.processedSections());
        assertTrue(accounting.isComplete(3));
        assertFalse(accounting.isComplete(4));
    }

    @Test
    void missingSectionsNeverCountAsCapturedOrComplete() {
        SceneCaptureAccounting accounting = new SceneCaptureAccounting();
        for (int i = 0; i < SceneCaptureService.MAX_MISSING_CHUNK_WAIT_TICKS; i++) {
            accounting.record(SceneCaptureAccounting.SectionOutcome.MISSING);
        }

        assertEquals(0, accounting.processedSections());
        assertEquals(SceneCaptureService.MAX_MISSING_CHUNK_WAIT_TICKS,
                accounting.consecutiveMissingTicks());
        assertFalse(accounting.isComplete(1));
    }

    @Test
    void successfulRetryClearsTheConsecutiveMissingCounter() {
        SceneCaptureAccounting accounting = new SceneCaptureAccounting();
        accounting.record(SceneCaptureAccounting.SectionOutcome.MISSING);
        accounting.record(SceneCaptureAccounting.SectionOutcome.MISSING);
        accounting.record(SceneCaptureAccounting.SectionOutcome.EMPTY);

        assertEquals(0, accounting.consecutiveMissingTicks());
        assertTrue(accounting.isComplete(1));
    }
}
