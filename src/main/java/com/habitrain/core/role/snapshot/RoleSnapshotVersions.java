package com.habitrain.core.role.snapshot;

import com.habitrain.core.api.role.v2.RoleSnapshotId;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic catalog generations independent of the v1 override-engine
 * {@code snapshotVersion}.
 *
 * <p>Every published compile ({@code publishSnapshotAfterRebuild}) consumes a
 * unique id so mid-round hot-reloads cannot overwrite an archived round in
 * {@link RoleSnapshotArchive}. Lazy catalog compiles use {@link #TEMP} and
 * never enter the archive.
 */
public final class RoleSnapshotVersions {

    /** Non-archived id for lazy catalog compiles before a lobby is published. */
    public static final RoleSnapshotId TEMP = new RoleSnapshotId(0);

    private static final AtomicLong NEXT = new AtomicLong(1);

    private RoleSnapshotVersions() {}

    /** Allocates the next published catalog generation. Starts at 1. */
    public static RoleSnapshotId nextPublished() {
        return new RoleSnapshotId(NEXT.getAndIncrement());
    }

    /** Restores the generation counter so unit tests do not leak across cases. */
    public static void resetForTests() {
        NEXT.set(1);
    }
}
