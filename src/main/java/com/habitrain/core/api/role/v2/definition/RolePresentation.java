package com.habitrain.core.api.role.v2.definition;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;

import java.util.Objects;

/**
 * Immutable presentation profile of a role: announcement color and mood type.
 *
 * <p>The display <em>name</em> and <em>description</em> are intentionally not
 * part of the profile: upstream derives them from translation keys keyed by the
 * role id path ({@code announcement.star.role.<path>} and
 * {@code info.screen.roleid.<path>}). Providers declare the stable id and ship
 * the corresponding language entries, matching how core's own roles are named
 * today.
 */
public record RolePresentation(int color, MoodType moodType) {

    public RolePresentation {
        Objects.requireNonNull(moodType, "moodType");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int color;
        private MoodType moodType = MoodType.REAL;

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder moodType(MoodType moodType) {
            this.moodType = Objects.requireNonNull(moodType, "moodType");
            return this;
        }

        public RolePresentation build() {
            return new RolePresentation(color, moodType);
        }
    }
}
