package com.habitrain.core.api.role.v2.state;

/**
 * How long a state value is intended to live.
 *
 * <p>This is a real storage contract, not a future placeholder. Production
 * {@code CcaRoleStateStore} (via {@code RoleStateServiceImpl}) routes:
 * <ul>
 *   <li>{@link #WORLD} / {@link #PERMANENT} → codec-encoded into the
 *       RoleStateStore: {@code StateScope.PLAYER} uses the player CCA
 *       component, {@code StateScope.WORLD} uses the world CCA component;</li>
 *   <li>{@link #ROUND} / {@link #NONE} → process-memory transient bag,
 *       never written to CCA;</li>
 *   <li>{@code StateScope.ROUND} always uses the in-memory round bag, even
 *       with {@link #PERMANENT}. ROUND+PERMANENT does not write world NBT.</li>
 * </ul>
 * Reset still honours {@link ResetCause}. Tests bind an in-memory store.
 */
public enum Persistence {
    /** Never written to CCA; dropped as soon as a matching reset fires. */
    NONE,
    /** Lives for the current round only (process-memory transient bag). */
    ROUND,
    /** Lives for the current world session (world CCA when scope is WORLD). */
    WORLD,
    /**
     * Survives world reloads when scope is PLAYER or WORLD (CCA NBT).
     * Combined with {@code StateScope.ROUND} it still stays in the memory bag.
     */
    PERMANENT
}
