package com.habitrain.core.api.role.v2.client;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/** Terminal result; cancellation never implies confirmation or a selection. */
public record RoleScreenResult(Outcome outcome, @Nullable String selectedId, @Nullable UUID playerId) {
    public RoleScreenResult {
        java.util.Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.SELECTED && selectedId == null) {
            throw new IllegalArgumentException("SELECTED RoleScreenResult requires a non-null selectedId");
        }
        if (outcome != Outcome.SELECTED && (selectedId != null || playerId != null)) {
            throw new IllegalArgumentException(outcome + " RoleScreenResult must not carry selection fields");
        }
    }

    public enum Outcome { SELECTED, CONFIRMED, CANCELLED }
    public static RoleScreenResult selected(RoleScreenOption option) {
        java.util.Objects.requireNonNull(option, "option");
        return new RoleScreenResult(Outcome.SELECTED, option.id(), option.playerId());
    }
    public static RoleScreenResult confirmed() { return new RoleScreenResult(Outcome.CONFIRMED, null, null); }
    public static RoleScreenResult cancelled() { return new RoleScreenResult(Outcome.CANCELLED, null, null); }
}
