package com.habitrain.core.api.role.v2.definition;

/**
 * Immutable spawn / assignment profile of a role.
 *
 * <p>These map onto the upstream spawn fields that {@code RoleOverrideTickApplier}
 * also manages for {@code MODIFY} entries: default max count, enable chance and
 * the enabled-player-count window.
 */
public record RoleSpawnProfile(int defaultMaxCount, int defaultEnableChance,
                               int defaultEnableNeedPlayerCount,
                               int defaultEnableMaxPlayerCount) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int defaultMaxCount = 1;
        private int defaultEnableChance = 100;
        private int defaultEnableNeedPlayerCount;
        private int defaultEnableMaxPlayerCount;

        public Builder defaultMax(int count) { this.defaultMaxCount = count; return this; }
        public Builder enableChance(int chance) { this.defaultEnableChance = chance; return this; }
        public Builder needPlayerCount(int count) { this.defaultEnableNeedPlayerCount = count; return this; }
        public Builder maxPlayerCount(int count) { this.defaultEnableMaxPlayerCount = count; return this; }

        public RoleSpawnProfile build() {
            return new RoleSpawnProfile(defaultMaxCount, defaultEnableChance,
                    defaultEnableNeedPlayerCount, defaultEnableMaxPlayerCount);
        }
    }
}
