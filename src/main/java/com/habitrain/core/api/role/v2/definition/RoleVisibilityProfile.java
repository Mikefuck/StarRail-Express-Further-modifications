package com.habitrain.core.api.role.v2.definition;

/**
 * Immutable visibility / instinct profile of a role.
 *
 * <p>Maps onto the upstream instinct trio that v1 {@code FlagsPatch} already
 * exposes: whether the holder can use instinct, whether instinct grants night
 * vision, and whether teammate killers remain visible. An absent profile on a
 * {@link RoleDefinition} leaves the upstream defaults (instinct off, teammate
 * visibility on).
 */
public record RoleVisibilityProfile(
        boolean canUseInstinct,
        boolean instinctNightVision,
        boolean canSeeTeammateKiller) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean canUseInstinct;
        private boolean instinctNightVision;
        private boolean canSeeTeammateKiller;

        public Builder canUseInstinct() {
            this.canUseInstinct = true;
            return this;
        }

        public Builder instinctNightVision() {
            this.instinctNightVision = true;
            return this;
        }

        public Builder canSeeTeammateKiller() {
            this.canSeeTeammateKiller = true;
            return this;
        }

        public Builder canSeeTeammateKiller(boolean value) {
            this.canSeeTeammateKiller = value;
            return this;
        }

        public RoleVisibilityProfile build() {
            return new RoleVisibilityProfile(canUseInstinct, instinctNightVision, canSeeTeammateKiller);
        }
    }
}
