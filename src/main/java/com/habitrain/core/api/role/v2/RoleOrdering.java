package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.EffectiveRole;

import java.util.Comparator;

/**
 * Stable ordering for the results of a {@link RoleQuery}.
 *
 * <p>{@link #REGISTRATION} is the catalog's insertion order (baseline roles
 * first, active replacements appended) — the ordering downstream consumers such
 * as role books and command completions already expect.
 */
public enum RoleOrdering {

    /** Catalog registration order (stable, deterministic). */
    REGISTRATION,

    /** Canonical role id, compared by {@code namespace:path} string. */
    ID,

    /** Display name, compared by {@code String} (byte) order. */
    NAME;

    /** A comparator over {@link EffectiveRole}s for this ordering. */
    public Comparator<EffectiveRole> comparator() {
        return switch (this) {
            case ID -> Comparator.comparing(er -> er.key().location().toString());
            // role() is null for archived snapshots (see EffectiveRole#role):
            // fall back to the key string so sorting a catalog containing
            // snapshots never NPEs (review M12).
            case NAME -> Comparator.comparing(er -> er.role() != null
                    ? er.role().getName().getString()
                    : er.key().location().toString());
            case REGISTRATION -> (a, b) -> 0;
        };
    }
}
