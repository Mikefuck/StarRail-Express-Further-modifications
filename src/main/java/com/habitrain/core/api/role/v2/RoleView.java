package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A read-only snapshot of a player's current role and faction.
 *
 * <p>{@code role} is the canonical role key (or {@code null} if the player has no
 * live role); {@code faction} is the current mode faction name (e.g. Blackout's
 * GOOD/BAD), or {@code null} when not applicable.
 */
public record RoleView(UUID playerId, @Nullable RoleKey role, @Nullable String faction) {

    public RoleView {
        Objects.requireNonNull(playerId, "playerId");
    }
}
