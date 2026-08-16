package com.habitrain.core.api.role.v2;

import java.util.Objects;

/**
 * Diagnostic summary of the currently compiled role snapshot.
 */
public record DiagnosticSnapshot(RoleSnapshotId id, int roleCount, int replacedCount,
                                 int aliasCount) {

    public DiagnosticSnapshot {
        Objects.requireNonNull(id, "id");
    }
}
