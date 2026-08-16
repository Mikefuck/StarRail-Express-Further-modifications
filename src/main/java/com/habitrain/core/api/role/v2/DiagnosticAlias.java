package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One diagnostic row for a v2 {@code ALIAS} entry, with its validity.
 */
public record DiagnosticAlias(RoleKey from, RoleKey to, boolean valid, @Nullable String message) {

    public DiagnosticAlias {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
