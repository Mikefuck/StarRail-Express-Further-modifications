package com.habitrain.core.game.blackout;

import com.habitrain.core.game.blackout.BlackoutRoleManager.Faction;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record BlackoutRoleDefinition(
        ResourceLocation identifier,
        String displayName,
        String description,
        String announcementName,
        String announcementSubtitle,
        String announcementGoal,
        Faction faction,
        SRERole sreRole,
        boolean selectableInRandomAssignment
) {
    public BlackoutRoleDefinition {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(announcementName, "announcementName");
        Objects.requireNonNull(announcementSubtitle, "announcementSubtitle");
        Objects.requireNonNull(announcementGoal, "announcementGoal");
        Objects.requireNonNull(faction, "faction");
        Objects.requireNonNull(sreRole, "sreRole");
    }

    public String key() {
        return identifier.toString();
    }
}
