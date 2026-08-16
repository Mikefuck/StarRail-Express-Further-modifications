package com.habitrain.core.api.role.v2.definition;

import com.habitrain.core.api.role.v2.RoleKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable relation profile: occupation, opposing and related roles, stored as
 * {@link RoleKey}s so a definition can be compiled before the counterpart
 * {@code SRERole} objects exist.
 *
 * <p>{@link com.habitrain.core.role.extension.ManagedSRERole#from} only stores
 * these keys. Linking onto upstream setters happens later via
 * {@link com.habitrain.core.role.extension.RoleExtensionCompiler#linkRelations}.
 */
public record RoleRelationProfile(
        List<RoleKey> occupation,
        List<RoleKey> opposing,
        List<RoleKey> related,
        boolean opposingTwoWay) {

    public RoleRelationProfile {
        occupation = List.copyOf(Objects.requireNonNull(occupation, "occupation"));
        opposing = List.copyOf(Objects.requireNonNull(opposing, "opposing"));
        related = List.copyOf(Objects.requireNonNull(related, "related"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<RoleKey> occupation = new ArrayList<>();
        private final List<RoleKey> opposing = new ArrayList<>();
        private final List<RoleKey> related = new ArrayList<>();
        private boolean opposingTwoWay;

        public Builder occupation(RoleKey key) {
            this.occupation.add(Objects.requireNonNull(key, "occupation"));
            return this;
        }

        public Builder opposing(RoleKey key) {
            this.opposing.add(Objects.requireNonNull(key, "opposing"));
            return this;
        }

        public Builder related(RoleKey key) {
            this.related.add(Objects.requireNonNull(key, "related"));
            return this;
        }

        public Builder opposingTwoWay() {
            this.opposingTwoWay = true;
            return this;
        }

        public RoleRelationProfile build() {
            return new RoleRelationProfile(occupation, opposing, related, opposingTwoWay);
        }
    }
}
