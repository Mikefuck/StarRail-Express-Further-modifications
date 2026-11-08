package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A read-only snapshot of a player's current role and faction.
 *
 * <p>{@code role} is the canonical role key (or {@code null} if the player has no
 * live role); {@code faction} is the derived faction label (e.g. {@code "KILLER"},
 * {@code "VIGILANTE"}, {@code "NEUTRAL"}, {@code "INNOCENT"}) when a role is active,
 * or {@code null} when the player has no active role or the role is not in the catalog.
 */
public record RoleView(UUID playerId, @Nullable RoleKey role, @Nullable String faction) {

    public RoleView {
        Objects.requireNonNull(playerId, "playerId");
    }
}
