package com.habitrain.core.role.catalog;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * {@link RawRoleLookup} over an injected {@code Map}, preserving the test
 * injection style used across the v2 unit tests.
 */
public final class MapRoleLookup implements RawRoleLookup {

    private final Map<ResourceLocation, SRERole> roles;

    public MapRoleLookup(Map<ResourceLocation, SRERole> roles) {
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    @Override
    public @Nullable SRERole find(ResourceLocation id) {
        return roles.get(id);
    }

    @Override
    public Collection<SRERole> all() {
        return roles.values();
    }
}
