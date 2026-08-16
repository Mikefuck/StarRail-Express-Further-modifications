package com.habitrain.core.api.role.v2.behavior;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Read-only settlement snapshot handed to {@link RoleWinHooks#afterWinnersFinalized}.
 *
 * <p>{@code status} is an upstream {@code WinStatus} name when known
 * ({@code KILLERS}, {@code CUSTOM}, {@code BLACKOUT}, …). Hooks must not
 * mutate winners from this callback.
 */
public record WinOutcome(
        @Nullable String status,
        List<UUID> winners,
        @Nullable String reason
) {

    public WinOutcome {
        winners = winners == null ? List.of() : List.copyOf(winners);
    }

    public static WinOutcome empty() {
        return new WinOutcome(null, List.of(), null);
    }
}
