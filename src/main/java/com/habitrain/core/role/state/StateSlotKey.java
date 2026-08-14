package com.habitrain.core.role.state;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.api.role.v2.state.StateScope;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Logical identity of one stored state slot (fix-doc §10.2 storage path:
 * {@code providerId / canonicalRoleId / stateId / dataVersion} plus the
 * scope owner).
 *
 * <p>{@link StateScope#PLAYER} slots are keyed by player UUID only — the value
 * follows the player across dimensions, exactly like an entity-attached CCA
 * component. {@link StateScope#WORLD}/{@link StateScope#ROUND} slots are keyed
 * by {@code worldKey} (dimension location) and never share a static map between
 * worlds.
 */
public record StateSlotKey(
        @Nullable String worldKey,
        @Nullable UUID playerId,
        StateScope scope,
        ResourceLocation id,
        RoleKey role) {

    public StateSlotKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        if (scope == StateScope.PLAYER) {
            if (playerId == null) {
                throw new IllegalArgumentException("PLAYER scope requires a playerId");
            }
            worldKey = null; // player state follows the player across worlds
        } else {
            if (playerId != null) {
                throw new IllegalArgumentException(scope + " scope cannot carry a playerId");
            }
        }
    }

    /** Builds the key for a spec, resolving the world from {@code worldKey} when needed. */
    public static StateSlotKey of(RoleStateSpec<?> spec, @Nullable UUID playerId,
                                  @Nullable ResourceLocation worldKey) {
        if (spec.scope() == StateScope.PLAYER) {
            return new StateSlotKey(null, playerId, StateScope.PLAYER, spec.id(), spec.role());
        }
        return new StateSlotKey(worldKey == null ? null : worldKey.toString(), null,
                spec.scope(), spec.id(), spec.role());
    }

    /**
     * Stable string identity used as the NBT map key inside CCA containers.
     * The separator is {@code |} because a {@link ResourceLocation} path may
     * contain {@code /} but never {@code |}.
     */
    public String encode() {
        String owner = scope == StateScope.PLAYER ? "p|" + playerId : "w|" + worldKey;
        return owner + "|" + scope + "|" + id + "|" + role;
    }

    /** Parses a string produced by {@link #encode()}, or {@code null} if malformed. */
    public static @Nullable StateSlotKey parse(String encoded) {
        if (encoded == null) {
            return null;
        }
        // encode() emits 5 pipe-separated segments: owner-kind, owner-value,
        // scope, id, role.  Split all five so the owner's embedded '|' is
        // consumed exactly once (a ResourceLocation/UUID never contains '|').
        String[] parts = encoded.split("\\|", 5);
        if (parts.length != 5) {
            return null;
        }
        StateScope scope;
        try {
            scope = StateScope.valueOf(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(parts[3]);
        RoleKey role = RoleKey.tryParse(parts[4]);
        if (id == null || role == null) {
            return null;
        }
        UUID playerId = null;
        String worldKey = null;
        switch (parts[0]) {
            case "p" -> {
                try {
                    playerId = UUID.fromString(parts[1]);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            case "w" -> worldKey = "null".equals(parts[1]) ? null : parts[1];
            default -> {
                return null;
            }
        }
        try {
            return new StateSlotKey(worldKey, playerId, scope, id, role);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return encode();
    }
}
