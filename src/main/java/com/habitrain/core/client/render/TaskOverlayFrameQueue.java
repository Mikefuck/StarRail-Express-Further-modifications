package com.habitrain.core.client.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame command queue used by the final task-overlay render pass.
 *
 * <p>A new frame always discards leftovers from an aborted previous frame. Draining returns a
 * stable snapshot and clears the queue before GPU submission, so an exception cannot replay the
 * same commands on the next frame.
 */
final class TaskOverlayFrameQueue<T> {

    private final List<T> commands = new ArrayList<>();

    void beginFrame() {
        commands.clear();
    }

    void enqueue(T command) {
        commands.add(command);
    }

    List<T> drain() {
        if (commands.isEmpty()) {
            return List.of();
        }
        List<T> snapshot = List.copyOf(commands);
        commands.clear();
        return snapshot;
    }
}
