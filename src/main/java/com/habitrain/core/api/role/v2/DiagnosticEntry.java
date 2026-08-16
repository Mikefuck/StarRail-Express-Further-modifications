package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One diagnostic row for a v2 role-extension entry (ADD/MODIFY/REPLACE/ALIAS).
 *
 * <p>The {@code kind/id/target/status/message} tuple is the compact form every
 * consumer used before Phase F; the additional fields are populated from the
 * compiled entry shell (fix-doc §13.2) so a rich UI can show the owning provider,
 * the core entryId, the enablement source and the conflict field list.
 */
public record DiagnosticEntry(String kind, String id, @Nullable RoleKey target,
                              DiagnosticStatus status, @Nullable String message,
                              @Nullable String providerId, @Nullable String entryId,
                              @Nullable String enabledSource, @Nullable String conflictFields,
                              @Nullable String definitionHash) {

    public DiagnosticEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }

    /** Compact form; extra fields default to {@code null}. */
    public DiagnosticEntry(String kind, String id, @Nullable RoleKey target,
                           DiagnosticStatus status, @Nullable String message) {
        this(kind, id, target, status, message, null, null, null, null, null);
    }
}
