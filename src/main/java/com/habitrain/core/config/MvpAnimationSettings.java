package com.habitrain.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MVP 结算动画配置。
 */
public final class MvpAnimationSettings {
    public static final List<String> DEFAULT_ANIMATION_IDS = List.of(
            "victory_bow",
            "penguin_dance",
            "cool_sit",
            "victory_dab",
            "victory_floss",
            "grace_pose",
            "heart_pose",
            "victory_jump",
            "meditation_fly",
            "champion_tpose",
            "victory_backflip",
            "champion_clap",
            "come_here",
            "kazotsky_victory",
            "victory_palm",
            "victory_point",
            "potion_dance",
            "champion_wave",
            "royal_salute",
            "fist_pump",
            "power_pose",
            "star_pose",
            "cross_arms",
            "double_cheer",
            "disco_point",
            "victory_spin",
            "humble_thanks",
            "shoulder_dance",
            "sky_punch",
            "hero_landing"
    );

    public boolean enabled = true;
    public boolean randomSelection = true;
    public boolean avoidDuplicates = true;
    public boolean showRoleItems = false;
    public float speed = 1.0f;
    public final LinkedHashMap<String, Boolean> animations = new LinkedHashMap<>();

    public MvpAnimationSettings() {
    }

    public static MvpAnimationSettings createDefault() {
        MvpAnimationSettings settings = new MvpAnimationSettings();
        settings.enabled = true;
        settings.randomSelection = true;
        settings.avoidDuplicates = true;
        settings.showRoleItems = false;
        settings.speed = 1.0f;
        for (String id : DEFAULT_ANIMATION_IDS) {
            settings.animations.put(id, true);
        }
        return settings;
    }

    public boolean isAnimationEnabled(String id) {
        if (id == null) return false;
        return animations.getOrDefault(id, true);
    }

    public void setAnimationEnabled(String id, boolean enabled) {
        if (id != null) {
            animations.put(id, enabled);
        }
    }

    public int getEnabledCount() {
        int count = 0;
        for (String id : DEFAULT_ANIMATION_IDS) {
            if (isAnimationEnabled(id)) {
                count++;
            }
        }
        return count;
    }

    public void setSpeed(float speed) {
        if (!Float.isFinite(speed)) {
            this.speed = 1.0f;
        } else {
            this.speed = Math.max(0.5f, Math.min(1.5f, speed));
        }
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", this.enabled);
        json.addProperty("randomSelection", this.randomSelection);
        json.addProperty("avoidDuplicates", this.avoidDuplicates);
        json.addProperty("showRoleItems", this.showRoleItems);
        json.addProperty("speed", this.speed);

        JsonObject anims = new JsonObject();
        for (String id : DEFAULT_ANIMATION_IDS) {
            anims.addProperty(id, isAnimationEnabled(id));
        }
        for (Map.Entry<String, Boolean> entry : this.animations.entrySet()) {
            if (!anims.has(entry.getKey())) {
                anims.addProperty(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
            }
        }
        json.add("animations", anims);
        return json;
    }

    public static MvpAnimationSettings fromJson(JsonObject json) {
        MvpAnimationSettings settings = createDefault();
        if (json == null) {
            return settings;
        }

        if (json.has("enabled")) {
            settings.enabled = json.get("enabled").getAsBoolean();
        }
        if (json.has("randomSelection")) {
            settings.randomSelection = json.get("randomSelection").getAsBoolean();
        }
        if (json.has("avoidDuplicates")) {
            settings.avoidDuplicates = json.get("avoidDuplicates").getAsBoolean();
        }
        if (json.has("showRoleItems")) {
            settings.showRoleItems = json.get("showRoleItems").getAsBoolean();
        }
        if (json.has("speed")) {
            try {
                settings.setSpeed(json.get("speed").getAsFloat());
            } catch (Exception e) {
                settings.speed = 1.0f;
            }
        }
        if (json.has("animations") && json.get("animations").isJsonObject()) {
            JsonObject anims = json.getAsJsonObject("animations");
            for (Map.Entry<String, JsonElement> entry : anims.entrySet()) {
                try {
                    settings.animations.put(entry.getKey(), entry.getValue().getAsBoolean());
                } catch (Exception ignored) {
                }
            }
            for (String id : DEFAULT_ANIMATION_IDS) {
                settings.animations.putIfAbsent(id, true);
            }
        }
        return settings;
    }

    public MvpAnimationSettings copy() {
        MvpAnimationSettings copy = new MvpAnimationSettings();
        copy.enabled = this.enabled;
        copy.randomSelection = this.randomSelection;
        copy.avoidDuplicates = this.avoidDuplicates;
        copy.showRoleItems = this.showRoleItems;
        copy.speed = this.speed;
        copy.animations.putAll(this.animations);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MvpAnimationSettings that = (MvpAnimationSettings) o;
        return enabled == that.enabled &&
                randomSelection == that.randomSelection &&
                avoidDuplicates == that.avoidDuplicates &&
                showRoleItems == that.showRoleItems &&
                Float.compare(that.speed, speed) == 0 &&
                Objects.equals(animations, that.animations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, randomSelection, avoidDuplicates, showRoleItems, speed, animations);
    }
}
