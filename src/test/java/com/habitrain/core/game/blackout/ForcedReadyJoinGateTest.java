package com.habitrain.core.game.blackout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedReadyJoinGateTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @BeforeEach
    @AfterEach
    void reset() {
        ForcedReadyJoinGate.clear();
    }

    @Test
    void snapshotReplacesAndIgnoresNullIds() {
        ForcedReadyJoinGate.snapshot(List.of(A, B));
        assertTrue(ForcedReadyJoinGate.contains(A));
        assertTrue(ForcedReadyJoinGate.contains(B));
        assertFalse(ForcedReadyJoinGate.contains(C));
        assertFalse(ForcedReadyJoinGate.contains(null));

        ForcedReadyJoinGate.snapshot(java.util.Arrays.asList(C, null));
        assertFalse(ForcedReadyJoinGate.contains(A), "snapshot must replace, not union");
        assertTrue(ForcedReadyJoinGate.contains(C));
        assertEquals(1, ForcedReadyJoinGate.view().size());
    }

    @Test
    void emptyOrNullSnapshotClears() {
        ForcedReadyJoinGate.snapshot(List.of(A));
        ForcedReadyJoinGate.snapshot(List.of());
        assertFalse(ForcedReadyJoinGate.contains(A));

        ForcedReadyJoinGate.snapshot(List.of(B));
        ForcedReadyJoinGate.snapshot(null);
        assertFalse(ForcedReadyJoinGate.contains(B));
        assertTrue(ForcedReadyJoinGate.view().isEmpty());
    }

    @Test
    void clearDropsRoster() {
        ForcedReadyJoinGate.snapshot(List.of(A, B));
        ForcedReadyJoinGate.clear();
        assertFalse(ForcedReadyJoinGate.contains(A));
        assertFalse(ForcedReadyJoinGate.contains(B));
    }
}
