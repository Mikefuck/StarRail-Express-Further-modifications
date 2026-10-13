package com.habitrain.core.role.catalog;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.game.sre.role.SreRoleResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves upstream candidate pools and role handles through the v2 catalog.
 * Before a snapshot is published, the v2 resolver compiles the upstream registry.
 */
public final class RoleCatalogConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|RoleCatalogConsumer");

    private RoleCatalogConsumer() {}

    /**
     * The visible role pool: the catalog's effective roles when a snapshot is
     * live, otherwise the v2 view of the raw registry (pre-freeze fallback).
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
            return SreRoleResolver.visibleRegistryRoles(TMMRoles.ROLES.values());
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
     * catalog (v2 REPLACE / ALIAS aware), with the v2 role resolver as the
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
            return SreRoleResolver.resolveOrOriginal(baseline);
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
