package com.habitrain.core.role.catalog;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Pure-data boundary for the raw upstream role source (fix-doc §6.2).
 *
 * <p>Every pure consumer ({@code RoleCatalogImpl}, {@code RoleSnapshotCompiler},
 * the resolver) reads roles only through this interface, so a bare unit test can
 * inject a {@link MapRoleLookup} without triggering {@code TMMRoles}'s static
 * initializer. Production binds {@link TmmRoleLookup}; the two physical
 * {@code TMMRoles} write paths ({@code ADD}/{@code REPLACE} registration) are not
 * part of this interface.
 */
public interface RawRoleLookup {

    /** The role registered under {@code id}, or {@code null} if absent. */
    @Nullable
    SRERole find(ResourceLocation id);

    /** Every raw role currently registered. */
    Collection<SRERole> all();
}
