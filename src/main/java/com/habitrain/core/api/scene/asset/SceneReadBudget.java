package com.habitrain.core.api.scene.asset;

import com.habitrain.core.api.scene.SceneLimits;
import java.io.IOException;

/** Conservative allocation accounting shared by encode validation and all asset readers. */
final class SceneReadBudget {
    private final long byteLimit;
    private final long objectLimit;
    SceneReadBudget() { this(SceneLimits.MAX_DECODE_MEMORY_BYTES, SceneLimits.MAX_DECODE_OBJECTS); }
    SceneReadBudget(long byteLimit, long objectLimit) { this.byteLimit = byteLimit; this.objectLimit = objectLimit; }
    private long bytes;
    private long objects;
    void charge(long allocationBytes, long allocationObjects) throws IOException {
        if (allocationBytes < 0 || allocationObjects < 0
                || allocationBytes > byteLimit - bytes
                || allocationObjects > objectLimit - objects)
            throw new IOException("Scene decoded object graph exceeds memory/object budget");
        bytes += allocationBytes;
        objects += allocationObjects;
    }
    void sections(int count) throws IOException { charge(count * (12_288L + 512L), count * 12L); }
    void palette(int count) throws IOException { charge(64L + 8L * count, 1); }
    void string(String value) throws IOException { charge(64L + 2L * value.length(), 2); }
    void payload(int length) throws IOException { charge(4096L + 8L * length, 64L + length / 16L); }
}
