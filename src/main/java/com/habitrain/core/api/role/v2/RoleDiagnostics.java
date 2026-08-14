package com.habitrain.core.api.role.v2;

import java.util.List;

/**
 * Read-only diagnostics for the v2 role-extension platform.
 *
 * <p>Reports the providers that registered entries, every entry with its status,
 * the aliases with validity, and a snapshot summary. Backs the diagnostic
 * commands and lets operators see conflicts, invalid entries and pending changes.
 */
public interface RoleDiagnostics {

    /** The process-wide diagnostics service. */
    static RoleDiagnostics instance() {
        return DefaultHolder.INSTANCE;
    }

    /** Lazily-bound default instance; avoids touching the registries on class load. */
    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleDiagnostics INSTANCE =
                new com.habitrain.core.role.diag.RoleDiagnosticsImpl();
    }

    /** A full diagnostic report. */
    DiagnosticReport report();

    /** Every registered entry with its status. */
    List<DiagnosticEntry> entries();

    /** Every registered alias with its validity. */
    List<DiagnosticAlias> aliases();

    /** A summary of the currently compiled snapshot. */
    DiagnosticSnapshot snapshotInfo();
}
