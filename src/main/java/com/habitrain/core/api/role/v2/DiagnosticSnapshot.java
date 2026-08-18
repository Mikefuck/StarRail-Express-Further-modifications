package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Diagnostic summary of the currently compiled role snapshot.
 */
public record DiagnosticSnapshot(RoleSnapshotId id, int roleCount, int replacedCount,
                                 int aliasCount, @Nullable RoleSnapshotId pendingId) {

    public DiagnosticSnapshot {
        Objects.requireNonNull(id, "id");
    }

    /** Compatibility constructor retained for consumers built before pending diagnostics. */
    public DiagnosticSnapshot(RoleSnapshotId id, int roleCount, int replacedCount,
                              int aliasCount) {
        this(id, roleCount, replacedCount, aliasCount, null);
    }
}
