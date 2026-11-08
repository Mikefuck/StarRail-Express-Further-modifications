package com.habitrain.core.config;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Server-thread connection state, with caller-supplied monotonic time for boundary tests. */
final class ShaderReportTracker {
    static final long TIMEOUT = TimeUnit.SECONDS.toNanos(15);
    private record Pending(long started, long revision) {}
    private final Map<UUID, String> reports = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    boolean awaiting(UUID id) { return pending.containsKey(id); }
    boolean hasReport(UUID id) { return reports.containsKey(id); }
    String report(UUID id) { return reports.get(id); }
    void begin(UUID id, long revision, long now) { pending.put(id, new Pending(now, revision)); }
    void cancelPending(UUID id) { pending.remove(id); }
    boolean accept(UUID id, String shader, long revision) {
        Pending request = pending.get(id);
        if (request != null && request.revision() >= 0 && revision != request.revision()) return false;
        reports.put(id, shader);
        pending.remove(id);
        return true;
    }
    List<UUID> expire(long now) {
        List<UUID> expired = new ArrayList<>();
        pending.entrySet().removeIf(entry -> {
            if (now - entry.getValue().started() < TIMEOUT) return false;
            expired.add(entry.getKey()); return true;
        });
        return expired;
    }
    void clear(UUID id) { reports.remove(id); pending.remove(id); }
    void clear() { reports.clear(); pending.clear(); }
    static boolean validName(String shader) {
        return shader != null && (shader.isEmpty()
                || !shader.isBlank() && shader.chars().noneMatch(Character::isISOControl));
    }
}
