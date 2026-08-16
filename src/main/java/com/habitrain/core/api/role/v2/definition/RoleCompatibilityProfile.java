package com.habitrain.core.api.role.v2.definition;

import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.SRERole;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable compatibility / integration profile of a role.
 *
 * <p>Holds upstream flags and the optional role component key that are not yet
 * covered by a dedicated profile. As the v2 platform grows dedicated profiles,
 * fields here may move into them; the record shape is kept minimal for now.
 */
public record RoleCompatibilityProfile(
        @Nullable ComponentKey<? extends RoleComponent> componentKey,
        boolean canSeeCoin,
        boolean canPickUpRevolver,
        boolean canBeRandomed,
        boolean otherModeRole,
        SRERole.SpecialMapRoleMap specialMapRole,
        boolean hiddenForRotation,
        int occupiedRoleCount) {

    public RoleCompatibilityProfile {
        specialMapRole = specialMapRole == null ? SRERole.SpecialMapRoleMap.ALL : specialMapRole;
        if (occupiedRoleCount < 1) {
            occupiedRoleCount = 1;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @Nullable ComponentKey<? extends RoleComponent> componentKey;
        private boolean canSeeCoin;
        private boolean canPickUpRevolver;
        private boolean canBeRandomed;
        private boolean otherModeRole;
        private SRERole.SpecialMapRoleMap specialMapRole = SRERole.SpecialMapRoleMap.ALL;
        private boolean hiddenForRotation;
        private int occupiedRoleCount = 1;

        public Builder componentKey(ComponentKey<? extends RoleComponent> key) {
            this.componentKey = Objects.requireNonNull(key, "componentKey");
            return this;
        }

        public Builder canSeeCoin() { this.canSeeCoin = true; return this; }
        public Builder canPickUpRevolver() { this.canPickUpRevolver = true; return this; }
        public Builder canBeRandomed() { this.canBeRandomed = true; return this; }
        public Builder otherModeRole() { this.otherModeRole = true; return this; }

        public Builder specialMapRole(SRERole.SpecialMapRoleMap map) {
            this.specialMapRole = Objects.requireNonNull(map, "specialMapRole");
            return this;
        }

        public Builder hiddenForRotation() { this.hiddenForRotation = true; return this; }

        public Builder occupiedRoleCount(int count) {
            this.occupiedRoleCount = count;
            return this;
        }

        public RoleCompatibilityProfile build() {
            return new RoleCompatibilityProfile(componentKey, canSeeCoin,
                    canPickUpRevolver, canBeRandomed, otherModeRole, specialMapRole,
                    hiddenForRotation, occupiedRoleCount);
        }
    }
}
