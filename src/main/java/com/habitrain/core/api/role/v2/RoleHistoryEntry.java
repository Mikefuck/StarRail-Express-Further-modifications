package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One entry in a player's role history timeline: the role they held and why it
 * changed, with a monotonic timestamp.
 *
 * <p>P2 adds the snapshot the change was recorded against, plus the
 * display name / provider captured at that moment so replay and logs
 * can restore the canonical id after a later config refresh.
 */
public record RoleHistoryEntry(
        RoleKey role,
        RoleChangeCause cause,
        long timestamp,
        @Nullable RoleSnapshotId snapshot,
        @Nullable String displayName,
        @Nullable String provider) {

    public RoleHistoryEntry {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(cause, "cause");
    }

    /** Compatible 3-arg form used by existing callers. */
    public RoleHistoryEntry(RoleKey role, RoleChangeCause cause, long timestamp) {
        this(role, cause, timestamp, null, null, null);
    }
}
