package com.habitrain.core.api.role.v2;

/**
 * The physical/logical side a {@link RoleQuery} is evaluated for.
 *
 * <p><b>Increment scope:</b> the catalog currently holds one role set shared by
 * both sides, so {@link #PHYSICAL} and {@link #LOGICAL} resolve to the same set.
 * The dimension is part of the query contract so that a later increment which
 * introduces side-scoped roles (server-authoritative vs client-extension roles)
 * can filter on it without a breaking API change. Callers should pass their own
 * side so future behavior is preserved.
 */
public enum QuerySide {
    /** No side restriction — the role set shared by both sides. */
    ANY,
    /** The physical side (dedicated server / integrated server host). */
    PHYSICAL,
    /** The logical side (the side running game logic). */
    LOGICAL
}
