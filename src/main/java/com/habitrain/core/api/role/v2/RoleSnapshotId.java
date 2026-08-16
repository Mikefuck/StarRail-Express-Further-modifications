package com.habitrain.core.api.role.v2;

/**
 * Identifies a specific compiled role-catalog snapshot.
 *
 * <p>Increment 1 exposes only the override engine's monotonic snapshot version.
 * A content-hash form used for client manifest handshake arrives in a later
 * phase, so callers must treat this as an opaque identity, not a stable
 * fingerprint of the role set.
 */
public record RoleSnapshotId(long version) {

    @Override
    public String toString() {
        return "role-snapshot-v" + version;
    }
}
