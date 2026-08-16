package com.habitrain.core.role.snapshot;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Archives compiled {@link RoleSnapshot}s so replay / history can restore
 * a past canonical view after the live lobby has moved on (design P2).
 *
 * <p>Bounded: the oldest entries drop once {@link #MAX} is exceeded.
 */
public final class RoleSnapshotArchive {

    public static final RoleSnapshotArchive INSTANCE = new RoleSnapshotArchive();
    public static final int MAX = 32;

    private final LinkedHashMap<Long, RoleSnapshot> archive = new LinkedHashMap<>();

    private RoleSnapshotArchive() {}

    public synchronized void put(RoleSnapshot snapshot) {
        if (snapshot == null || snapshot.id() == null) {
            return;
        }
        // Do not let an archived replay/profile accidentally hold a mutable
        // upstream role instance.  Runtime handles remain only in the manager's
        // live lobby/round slots.
        archive.put(snapshot.id().version(), snapshot.withoutRuntimeHandles());
        while (archive.size() > MAX) {
            Long oldest = archive.keySet().iterator().next();
            archive.remove(oldest);
        }
    }

    public synchronized @Nullable RoleSnapshot get(@Nullable RoleSnapshotId id) {
        return id == null ? null : archive.get(id.version());
    }

    public synchronized @Nullable RoleSnapshot get(long version) {
        return archive.get(version);
    }

    public synchronized Collection<RoleSnapshot> all() {
        return List.copyOf(archive.values());
    }

    /**
     * Resolves {@code key} against an archived snapshot, falling back to
     * the live manager current snapshot.
     */
    public Optional<EffectiveRole> restore(@Nullable RoleSnapshotId id, RoleKey key) {
        if (key == null) {
            return Optional.empty();
        }
        RoleSnapshot snap = get(id);
        if (snap == null) {
            snap = RoleSnapshotManager.INSTANCE.current();
        }
        if (snap == null) {
            return Optional.empty();
        }
        return snap.find(key);
    }

    public synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("archive " + archive.size() + "/" + MAX);
        if (archive.isEmpty()) {
            lines.add("  (none)");
            return lines;
        }
        for (RoleSnapshot snap : archive.values()) {
            lines.add("  " + snap.id()
                    + " roles=" + snap.roles().size()
                    + " replaced=" + snap.replacedTargets().size()
                    + " aliases=" + snap.aliases().size());
        }
        return lines;
    }

    public synchronized void clear() {
        archive.clear();
    }
}
