package com.habitrain.core.scene.server;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ScenePreloadAcknowledgementTest {
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);
    private static final Set<String> EXPECTED = Set.of(FIRST, SECOND);

    @Test void independentCachedAssetsCanAcknowledgeBackToBack() {
        var progress = new ScenePreloadCoordinator.PlayerProgress(100L);
        assertTrue(progress.acknowledge(FIRST, true, EXPECTED));
        assertFalse(progress.ready);
        assertTrue(progress.acknowledge(SECOND, true, EXPECTED));
        assertTrue(progress.ready);
        assertEquals(100L, progress.confirmedBytes);
    }

    @Test void duplicateAndUnknownRepliesDoNotChangeReadinessOrGrowTheDeduplicationSet() {
        var progress = new ScenePreloadCoordinator.PlayerProgress(100L);
        assertTrue(progress.acknowledge(FIRST, true, EXPECTED));
        assertFalse(progress.acknowledge(FIRST.toUpperCase(java.util.Locale.ROOT), false, EXPECTED));
        assertFalse(progress.acknowledge("c".repeat(64), true, EXPECTED));
        assertFalse(progress.failed);
        assertFalse(progress.ready);
        assertEquals(Set.of(FIRST), progress.reportedHashes);
        assertTrue(progress.acknowledge(SECOND, false, EXPECTED));
        assertTrue(progress.failed);
    }
}
