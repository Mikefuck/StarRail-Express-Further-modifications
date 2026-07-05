package com.habitrain.core.client.gui;

import com.habitrain.core.game.blackout.BlackoutRoleDefinition;
import com.habitrain.core.game.blackout.BlackoutRoleRegistry;
import com.habitrain.core.game.blackout.BlackoutRoles;
import io.wifi.starrailexpress.api.SRERole;

import java.util.List;

/**
 * Role data used by the blackout role introduction screen.
 */
public final class BlackoutRoleIntroData {
    private BlackoutRoleIntroData() {
    }

    public static List<SRERole> getRoles() {
        return BlackoutRoleRegistry.getAll().stream()
                .map(BlackoutRoleDefinition::sreRole)
                .toList();
    }

    public static SRERole getCivilianRole() {
        return BlackoutRoles.CIVILIAN.sreRole();
    }

    public static SRERole getKillerRole() {
        return BlackoutRoles.KILLER.sreRole();
    }

    public static SRERole getSheriffRole() {
        return BlackoutRoles.SHERIFF.sreRole();
    }
}
