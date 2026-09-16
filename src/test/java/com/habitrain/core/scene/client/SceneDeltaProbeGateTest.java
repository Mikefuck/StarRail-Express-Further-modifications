package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SceneDeltaProbeGateTest {
    @Test
    void offerArrivingAfterManifestResumesAllWaitersExactlyOnce() {
        SceneDeltaProbeGate gate = new SceneDeltaProbeGate(() -> 0L, 100);
        List<String> calls = new ArrayList<>();
        assertTrue(gate.begin("target"));
        assertFalse(gate.begin("target"));
        assertTrue(gate.await("target", () -> calls.add("primary")));
        assertTrue(gate.await("target", () -> calls.add("additional")));
        assertTrue(calls.isEmpty(), "应答之前不能抢先下载全量资产");
        gate.complete("target");
        gate.complete("target");
        assertEquals(List.of("primary", "additional"), calls);
        assertFalse(gate.await("target", () -> fail("already resolved")));
    }

    @Test
    void unansweredProbeFallsBackAtDeadline() {
        long[] now = {0};
        SceneDeltaProbeGate gate = new SceneDeltaProbeGate(() -> now[0], 100);
        List<String> calls = new ArrayList<>();
        gate.begin("target");
        gate.await("target", () -> calls.add("full"));
        now[0] = 99;
        gate.tick();
        assertTrue(calls.isEmpty());
        now[0] = 100;
        gate.tick();
        gate.tick();
        assertEquals(List.of("full"), calls);
    }

    @Test
    void resetReleasesWaitersAfterSessionInvalidation() {
        AssetTransferRegistry registry = new AssetTransferRegistry();
        long generation = registry.generation();
        SceneDeltaProbeGate gate = new SceneDeltaProbeGate(() -> 0, 100);
        List<Boolean> live = new ArrayList<>();
        gate.begin("target");
        gate.await("target", () -> live.add(registry.isCurrent(generation)));
        registry.invalidateAll();
        gate.reset();
        gate.complete("target");
        assertEquals(List.of(false), live);
    }
}
