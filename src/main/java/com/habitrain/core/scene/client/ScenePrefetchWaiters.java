package com.habitrain.core.scene.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Keeps vote sessions attached to an asset across internal mesh rebuilds. */
final class ScenePrefetchWaiters {
    @FunctionalInterface
    interface Reporter {
        void report(long sessionId, String hash, boolean success);
    }

    private static final class Pending {
        final Set<Long> sessions = new LinkedHashSet<>();
        CompletableFuture<Boolean> future;
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Reporter reporter;

    ScenePrefetchWaiters(Reporter reporter) {
        this.reporter = reporter;
    }

    synchronized void await(long sessionId, String hash, CompletableFuture<Boolean> future) {
        Pending entry = pending.computeIfAbsent(hash, ignored -> new Pending());
        entry.sessions.add(sessionId);
        bind(hash, entry, future);
    }

    synchronized Set<String> assetHashes() {
        return Set.copyOf(pending.keySet());
    }

    /** Detach old callbacks before cancellation; rebuilding is not a terminal failure. */
    synchronized Set<String> suspend() {
        pending.values().forEach(entry -> entry.future = null);
        return assetHashes();
    }

    synchronized void resume(String hash, CompletableFuture<Boolean> future) {
        Pending entry = pending.get(hash);
        if (entry != null) bind(hash, entry, future);
    }

    private void bind(String hash, Pending entry, CompletableFuture<Boolean> future) {
        if (entry.future == future) return;
        entry.future = future;
        future.whenComplete((success, error) -> {
            synchronized (this) {
                if (pending.get(hash) != entry || entry.future != future) return;
                complete(hash, entry, error == null && Boolean.TRUE.equals(success));
            }
        });
    }

    synchronized void fail(String hash) {
        Pending entry = pending.get(hash);
        if (entry != null) complete(hash, entry, false);
    }

    synchronized void clear() {
        Map<String, Pending> cancelled = new LinkedHashMap<>(pending);
        pending.clear();
        cancelled.forEach((hash, entry) -> entry.sessions.forEach(id -> reporter.report(id, hash, false)));
    }

    private void complete(String hash, Pending entry, boolean success) {
        pending.remove(hash);
        entry.sessions.forEach(id -> reporter.report(id, hash, success));
    }
}
