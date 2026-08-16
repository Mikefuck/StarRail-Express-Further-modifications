package com.habitrain.core.api.role.v2.definition;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable presentation profile of a role: announcement color, mood type and
 * optional explicit display-text keys.
 *
 * <p>When the keys are {@code null}, upstream derives the display name /
 * description from the role id path ( {@code announcement.star.role.<path>} and
 * {@code info.screen.roleid.<path>}). Providers may override them with explicit
 * translation keys, and may additionally declare a simple-description key,
 * objectives key and an icon path for richer catalog clients.
 */
public record RolePresentation(
        int color,
        MoodType moodType,
        @Nullable String nameKey,
        @Nullable String descriptionKey,
        @Nullable String simpleDescriptionKey,
        @Nullable String objectivesKey,
        @Nullable String icon) {

    public RolePresentation {
        Objects.requireNonNull(moodType, "moodType");
    }

    /** Compact constructor for existing callers that only set color/mood. */
    public RolePresentation(int color, MoodType moodType) {
        this(color, moodType, null, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int color;
        private MoodType moodType = MoodType.REAL;
        private @Nullable String nameKey;
        private @Nullable String descriptionKey;
        private @Nullable String simpleDescriptionKey;
        private @Nullable String objectivesKey;
        private @Nullable String icon;

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder moodType(MoodType moodType) {
            this.moodType = Objects.requireNonNull(moodType, "moodType");
            return this;
        }

        public Builder nameKey(String nameKey) {
            this.nameKey = nameKey;
            return this;
        }

        public Builder descriptionKey(String descriptionKey) {
            this.descriptionKey = descriptionKey;
            return this;
        }

        public Builder simpleDescriptionKey(String simpleDescriptionKey) {
            this.simpleDescriptionKey = simpleDescriptionKey;
            return this;
        }

        public Builder objectivesKey(String objectivesKey) {
            this.objectivesKey = objectivesKey;
            return this;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public RolePresentation build() {
            return new RolePresentation(color, moodType, nameKey, descriptionKey,
                    simpleDescriptionKey, objectivesKey, icon);
        }
    }
}
