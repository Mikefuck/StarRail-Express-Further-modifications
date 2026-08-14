package com.habitrain.core.role.catalog;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * The production {@link RawRoleLookup} bound to the upstream {@code TMMRoles}
 * registry.
 *
 * <p>The singleton construction is inert (no {@code TMMRoles} access), so merely
 * referencing {@link #INSTANCE} is safe in a bare unit test; only {@code find}/
 * {@code all} touch the upstream registry, and the resolver guards those calls so
 * tests that never bootstrapped the game stay safe.
 */
public final class TmmRoleLookup implements RawRoleLookup {

    /** The process-wide adapter. Lazy per-method: construction touches nothing. */
    public static final RawRoleLookup INSTANCE = new TmmRoleLookup();

    private TmmRoleLookup() {}

    @Override
    public @Nullable SRERole find(ResourceLocation id) {
        return TMMRoles.getRole(id);
    }

    @Override
    public Collection<SRERole> all() {
        return TMMRoles.ROLES.values();
    }
}
