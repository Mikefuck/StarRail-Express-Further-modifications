package com.habitrain.core.api.role.v2;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A full diagnostic report of the v2 role-extension platform: the providers that
 * registered entries, every entry with its status, the aliases with validity, and
 * a snapshot summary.
 */
public record DiagnosticReport(Set<String> providers, List<DiagnosticEntry> entries,
                               List<DiagnosticAlias> aliases, DiagnosticSnapshot snapshot) {

    public DiagnosticReport {
        providers = Set.copyOf(Objects.requireNonNull(providers, "providers"));
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
