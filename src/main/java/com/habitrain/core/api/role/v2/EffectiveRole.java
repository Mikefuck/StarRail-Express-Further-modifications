package com.habitrain.core.api.role.v2;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable view of one role as it is effective in the current catalog.
 *
 * <p>The immutable {@link #profile()} is the authoritative snapshot value.  A
 * live {@link #role()} is only an optional runtime handle for upstream-facing
 * code; archived snapshots intentionally omit it so future role mutations
 * cannot rewrite historical data.
 */
public final class EffectiveRole {

    private final EffectiveRoleProfile profile;
    private final @Nullable SRERole runtimeRole;
    private final @Nullable CompiledModifyOverlay modifyOverlay;

    public EffectiveRole(EffectiveRoleProfile profile, @Nullable SRERole runtimeRole,
                         @Nullable CompiledModifyOverlay modifyOverlay) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runtimeRole = runtimeRole;
        this.modifyOverlay = modifyOverlay;
    }

    public EffectiveRole(EffectiveRoleProfile profile, @Nullable SRERole runtimeRole) {
        this(profile, runtimeRole, null);
    }

    /** Compatibility constructor for a current runtime view. */
    public EffectiveRole(RoleKey key, SRERole role, Source source) {
        this(EffectiveRoleProfile.from(key, Objects.requireNonNull(role, "role"), source), role, null);
    }

    /** Convenience constructor assuming a {@link Source#BASELINE} role. */
    public EffectiveRole(RoleKey key, SRERole role) {
        this(key, role, Source.BASELINE);
    }

    /** Frozen pure-data profile. */
    public EffectiveRoleProfile profile() {
        return profile;
    }

    /** Canonical role key from the frozen profile. */
    public RoleKey key() {
        return profile.key();
    }

    /** How this role entered the effective catalog. */
    public Source source() {
        return profile.source();
    }

    /** Optional live upstream handle; null for archived snapshots. */
    public @Nullable SRERole role() {
        return runtimeRole;
    }

    /** Precompiled overlay for activation, or null for an untouched profile. */
    public @Nullable CompiledModifyOverlay modifyOverlay() {
        return modifyOverlay;
    }

    /** Drops only the non-historical runtime handle. */
    public EffectiveRole withoutRuntimeHandle() {
        return new EffectiveRole(profile, null, null);
    }

    /** Convenience accessor for the canonical role id. */
    public ResourceLocation id() {
        return profile.key().location();
    }

    /** How this role entered the effective catalog. */
    public enum Source {
        /** A role registered directly in the upstream registry, not via v2. */
        BASELINE,
        /** A role added through the v2 {@code ADD} model. */
        ADDED,
        /** A baseline role currently carrying an active v2 {@code MODIFY} patch. */
        MODIFIED,
        /** A role serving as the active v2 {@code REPLACE} of a hidden target. */
        REPLACEMENT
    }
}
