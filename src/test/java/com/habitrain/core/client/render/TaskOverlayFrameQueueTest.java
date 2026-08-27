package com.habitrain.core.client.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOverlayFrameQueueTest {

    @Test
    void beginFrameDropsCommandsLeftByAnAbortedPreviousFrame() {
        TaskOverlayFrameQueue<String> queue = new TaskOverlayFrameQueue<>();
        queue.enqueue("stale");

        queue.beginFrame();

        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void drainReturnsCommandsOnceAndPreservesTheirOrder() {
        TaskOverlayFrameQueue<String> queue = new TaskOverlayFrameQueue<>();
        queue.enqueue("first");
        queue.enqueue("second");

        assertEquals(List.of("first", "second"), queue.drain());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void overlayDistanceMatchesTwelveChunks() {
        assertEquals(192.0, TaskOverlayDrawer.SURVIVAL_OVERLAY_DISTANCE, 1e-6);
        assertEquals(192.0, TaskOverlayDrawer.SPECTATOR_OVERLAY_DISTANCE, 1e-6);
        assertEquals(192.0 * 192.0, TaskOverlayDrawer.SURVIVAL_OVERLAY_DISTANCE_SQ, 1e-6);
        assertEquals(192.0 * 192.0, TaskOverlayDrawer.SPECTATOR_OVERLAY_DISTANCE_SQ, 1e-6);
    }
}
