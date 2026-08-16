package com.habitrain.core.role.catalog;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;

import java.util.ArrayList;
import java.util.List;

/**
 * Migration helper for legacy role consumers (audit P2-1).
 *
 * <p>Consumers that enumerate candidate pools or resolve baseline roles used to
 * read the v1 paths ({@code TMMRoles.ROLES} + the v1 override resolver), which
 * never surface v2 {@code ADD} roles or active {@code REPLACE} results. These
 * helpers resolve through the v2 {@link RoleCatalogApi} first so v2 roles enter
 * the same consumer paths (blackout role pool, sin fallback, killer pool,
 * hire/police selection), falling back to the v1 path when the catalog has no
 * compiled snapshot yet (pre-freeze) or fails.
 */
public final class RoleCatalogConsumer {

    private RoleCatalogConsumer() {}

    /**
     * The visible role pool: the catalog's effective roles when a snapshot is
     * live, otherwise the v1 visible registry (pre-freeze fallback).
     */
    public static List<SRERole> visiblePool() {
        try {
            java.util.Collection<EffectiveRole> effective =
                    RoleCatalogApi.instance().effectiveRoles();
            if (effective != null && !effective.isEmpty()) {
                List<SRERole> out = new ArrayList<>();
                for (EffectiveRole er : effective) {
                    if (er != null && er.role() != null) {
                        out.add(er.role());
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        } catch (Throwable t) {
            // catalog not ready / failed: fall through to v1
        }
        return SreRoleOverrideResolver.visibleRegistryRoles(TMMRoles.ROLES.values());
    }

    /**
     * Resolves an upstream baseline role to its effective view through the
     * catalog (v2 REPLACE / ALIAS aware), with the v1 override resolver as the
     * pre-freeze fallback.
     */
    public static SRERole resolveOrOriginal(SRERole baseline) {
        if (baseline == null || baseline.getIdentifier() == null) {
            return baseline;
        }
        try {
            var effective = RoleCatalogApi.instance().find(
                    RoleKey.of(baseline.getIdentifier()));
            if (effective.isPresent() && effective.get().role() != null) {
                return effective.get().role();
            }
        } catch (Throwable t) {
            // fall through
        }
        return SreRoleOverrideResolver.resolveOrOriginal(baseline);
    }

    /**
     * The visible pool restricted to one role type (e.g. killers), or
     * {@code null} when empty — a common consumer shape that previously filtered
     * {@code TMMRoles.ROLES} directly.
     */
    public static List<SRERole> visiblePoolOfType(int roleType) {
        List<SRERole> out = new ArrayList<>();
        for (SRERole role : visiblePool()) {
            try {
                if (role != null && role.getRoleType() == roleType) {
                    out.add(role);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
