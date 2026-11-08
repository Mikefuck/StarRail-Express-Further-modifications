package com.habitrain.core.config;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShaderReportTrackerTest {
    @Test void initialSilenceExpiresAtBoundaryWithNegativeMonotonicOrigin() {
        var tracker = new ShaderReportTracker(); UUID id = UUID.randomUUID(); long start = -1_000_000_000L;
        tracker.begin(id, -1, start);
        assertTrue(tracker.expire(start + ShaderReportTracker.TIMEOUT - 1).isEmpty());
        assertEquals(java.util.List.of(id), tracker.expire(start + ShaderReportTracker.TIMEOUT));
        assertFalse(tracker.awaiting(id));
    }
    @Test void staleReportCannotCancelDeadlineOrReplaceAcceptedName() {
        var tracker = new ShaderReportTracker(); UUID id = UUID.randomUUID();
        assertTrue(tracker.accept(id, "allowed.zip", -1)); tracker.begin(id, 7, 0);
        for (int i = 0; i < 100; i++) assertFalse(tracker.accept(id, "", 6));
        assertTrue(tracker.awaiting(id)); assertEquals("allowed.zip", tracker.report(id));
        assertEquals(java.util.List.of(id), tracker.expire(ShaderReportTracker.TIMEOUT));
    }
    @Test void correctRefreshAndReconnectHaveIndependentState() {
        var tracker = new ShaderReportTracker(); UUID id = UUID.randomUUID();
        tracker.begin(id, 2, 0); assertTrue(tracker.accept(id, "", 2));
        assertFalse(tracker.awaiting(id)); tracker.clear(id); tracker.begin(id, -1, 100);
        assertFalse(tracker.hasReport(id)); assertTrue(tracker.awaiting(id));
    }
    @Test void emptyMeansDisabledButNullWhitespaceAndControlBytesAreMalformed() {
        assertTrue(ShaderReportTracker.validName("")); assertTrue(ShaderReportTracker.validName("pack.zip"));
        assertFalse(ShaderReportTracker.validName(null)); assertFalse(ShaderReportTracker.validName("  "));
        assertFalse(ShaderReportTracker.validName("pack\n.zip"));
    }
}
