package com.habitrain.core.scene.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Client-thread gate: give an optional delta offer one bounded round trip before full download. */
final class SceneDeltaProbeGate {
    private record Pending(long started, List<Runnable> waiters) {}
    private final Map<String, Pending> pending = new HashMap<>();
    private final LongSupplier clock;
    private final long timeoutNanos;

    SceneDeltaProbeGate(LongSupplier clock, long timeoutNanos) {
        this.clock = clock;
        this.timeoutNanos = timeoutNanos;
    }

    boolean begin(String hash) {
        return pending.putIfAbsent(hash, new Pending(clock.getAsLong(), new ArrayList<>())) == null;
    }

    boolean await(String hash, Runnable continuation) {
        Pending probe = pending.get(hash);
        if (probe == null) return false;
        probe.waiters().add(continuation);
        return true;
    }

    void complete(String hash) {
        Pending probe = pending.remove(hash);
        if (probe != null) probe.waiters().forEach(Runnable::run);
    }

    void tick() {
        long now = clock.getAsLong();
        List<String> expired = pending.entrySet().stream()
                .filter(e -> now - e.getValue().started() >= timeoutNanos)
                .map(Map.Entry::getKey).toList();
        expired.forEach(this::complete);
    }

    void reset() {
        List<Pending> cancelled = new ArrayList<>(pending.values());
        pending.clear();
        cancelled.forEach(p -> p.waiters().forEach(Runnable::run));
    }
}
