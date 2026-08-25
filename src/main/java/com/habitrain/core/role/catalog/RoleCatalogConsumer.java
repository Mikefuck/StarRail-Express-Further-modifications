package com.habitrain.core.role.catalog;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Migration helper for legacy role consumers (audit P2-1).
 *
 * <p>Consumers that enumerate candidate pools or resolve baseline roles used to
 * read the v1 paths ({@code TMMRoles.ROLES} + the v1 override resolver), which
 * never surface v2 {@code ADD} roles or active {@code REPLACE} results. These
 * helpers resolve through the v2 {@link RoleCatalogApi} first so v2 roles enter
 * the same consumer paths (blackout role pool, sin fallback, killer pool,
 * hire/police selection), falling back to the v1 path only when the catalog has no
 * compiled snapshot yet (pre-freeze). A live snapshot that throws or is empty
 * returns empty / the baseline, not {@code TMMRoles.ROLES}.
 */
public final class RoleCatalogConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|RoleCatalogConsumer");

    private RoleCatalogConsumer() {}

    /**
     * The visible role pool: the catalog's effective roles when a snapshot is
     * live, otherwise the v1 visible registry (pre-freeze fallback).
     */
    public static List<SRERole> visiblePool() {
        Optional<RoleSnapshot> snapshot;
        try {
            snapshot = RoleCatalogApi.instance().currentSnapshot();
        } catch (RuntimeException t) {
            LOGGER.error("Role catalog currentSnapshot failed; returning empty (not TMMRoles)", t);
            return List.of();
        }
        if (snapshot.isEmpty()) {
            // Explicit pre-freeze fallback only.
            return SreRoleOverrideResolver.visibleRegistryRoles(TMMRoles.ROLES.values());
        }
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
            LOGGER.error("Role catalog snapshot is live but effectiveRoles is empty; returning empty");
            return List.of();
        } catch (RuntimeException t) {
            LOGGER.error("Role catalog visiblePool failed; returning empty (not TMMRoles)", t);
            return List.of();
        }
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
        Optional<RoleSnapshot> snapshot;
        try {
            snapshot = RoleCatalogApi.instance().currentSnapshot();
        } catch (RuntimeException t) {
            LOGGER.error("Role catalog currentSnapshot failed for {}; returning baseline", baseline.getIdentifier(), t);
            return baseline;
        }
        if (snapshot.isEmpty()) {
            return SreRoleOverrideResolver.resolveOrOriginal(baseline);
        }
        try {
            var effective = RoleCatalogApi.instance().find(
                    RoleKey.of(baseline.getIdentifier()));
            if (effective.isPresent() && effective.get().role() != null) {
                return effective.get().role();
            }
            LOGGER.error("Catalog snapshot live but {} is missing; not falling back to TMMRoles",
                    baseline.getIdentifier());
            return baseline;
        } catch (RuntimeException t) {
            LOGGER.error("Role catalog resolve failed for {}", baseline.getIdentifier(), t);
            return baseline;
        }
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
