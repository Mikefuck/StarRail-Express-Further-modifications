package com.habitrain.core.scene.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CaptureTicketWindowTest {
    @Test
    void slidingWindowIsBoundedAndKeepsRepeatedChunkSectionsTogether() {
        RecordingAccess access = new RecordingAccess();
        CaptureTicketWindow<String> window = new CaptureTicketWindow<>(
                List.of("a", "a", "b", "b", "c", "d"), 2, access);

        window.alignToSection(0);
        assertEquals(Set.of("a", "b"), window.heldTicketsForTest());
        assertEquals(2, window.heldTicketCount());

        window.alignToSection(2);
        assertEquals(Set.of("b", "c"), window.heldTicketsForTest());
        assertTrue(access.released.contains("a"));

        window.alignToSection(4);
        assertEquals(Set.of("c", "d"), window.heldTicketsForTest());
        assertTrue(access.released.contains("b"));
        assertTrue(access.maxHeld <= 2);
    }

    @Test
    void everyTerminationPathCanUseTheSameIdempotentRelease() {
        for (String termination : List.of("success", "timeout", "exception", "cancel", "offline", "server-stop")) {
            RecordingAccess access = new RecordingAccess();
            CaptureTicketWindow<String> window = new CaptureTicketWindow<>(
                    List.of("a", "b", "c"), 3, access);
            window.alignToSection(0);

            window.close();
            window.close();

            assertTrue(access.held.isEmpty(), termination);
            assertEquals(Set.of("a", "b", "c"), new HashSet<>(access.released), termination);
        }
    }

    @Test
    void acquisitionFailureReleasesTicketsAlreadyOwnedByTheWindow() {
        RecordingAccess access = new RecordingAccess();
        access.failOnAcquire = "c";
        CaptureTicketWindow<String> window = new CaptureTicketWindow<>(
                List.of("a", "b", "c", "d"), 4, access);

        assertThrows(IllegalStateException.class, () -> window.alignToSection(0));
        assertTrue(access.held.isEmpty());
        assertEquals(Set.of("a", "b"), new HashSet<>(access.released));
        assertThrows(IllegalStateException.class, () -> window.alignToSection(0));
    }

    private static final class RecordingAccess implements CaptureTicketWindow.TicketAccess<String> {
        final Set<String> held = new HashSet<>();
        final List<String> released = new ArrayList<>();
        int maxHeld;
        String failOnAcquire;

        @Override
        public void acquire(String key) {
            if (key.equals(failOnAcquire)) throw new IllegalStateException("test acquire failure");
            held.add(key);
            maxHeld = Math.max(maxHeld, held.size());
        }

        @Override
        public void release(String key) {
            held.remove(key);
            released.add(key);
        }
    }
}
