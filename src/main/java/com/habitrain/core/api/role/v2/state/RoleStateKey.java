package com.habitrain.core.api.role.v2.state;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Typed handle returned when a {@link RoleStateSpec} is registered.
 *
 * <p>The id lives in the provider namespace ({@code provider:path}); the
 * bound {@link #role()} is the second half of the storage key
 * ({@code provider/role/state-key}).
 */
public record RoleStateKey<T>(ResourceLocation id, RoleKey role, Class<T> type) {

    public RoleStateKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(type, "type");
    }

    public String provider() {
        return id.getNamespace();
    }

    public String path() {
        return id.getPath();
    }

    @Override
    public String toString() {
        return id + "@" + role;
    }
}
