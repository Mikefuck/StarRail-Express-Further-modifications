package com.habitrain.core.api.role.v2;

import io.wifi.starrailexpress.api.SRERole;

/**
 * A high-level team / faction tag that a {@link RoleQuery} can filter on.
 *
 * <p>Values map onto {@link SRERole} predicates; a role may satisfy several at
 * once, and a query matches a role when it satisfies <em>any</em> selected
 * faction (the default for an empty selection is "all factions").
 */
public enum RoleFaction {

    /** Civilian-aligned innocent: {@code role.isInnocent()}. */
    INNOCENT,

    /** Killer-aligned: {@code role.isKillerTeam()}. */
    KILLER,

    /** Neutral: {@code role.isNeutrals()}. */
    NEUTRAL,

    /** Vigilante team: {@code role.isVigilanteTeam()}. */
    VIGILANTE,

    /** Mafia team: {@code role.isMafiaTeam()}. */
    MAFIA;

    /** True when the role satisfies this faction predicate. */
    public boolean matches(SRERole role) {
        if (role == null) {
            return false;
        }
        return switch (this) {
            case INNOCENT -> role.isInnocent();
            case KILLER -> role.isKillerTeam();
            case NEUTRAL -> role.isNeutrals();
            case VIGILANTE -> role.isVigilanteTeam();
            case MAFIA -> role.isMafiaTeam();
        };
    }
}
