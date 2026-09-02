package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.habitrain.core.scene.model.SceneProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 移动场景系统根配置（保存在 config/habitrain_core.json 的 sceneMotion 节点）。
 */
public final class SceneMotionSettings {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String DEFAULT_MAP_KEY = "__default__";

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public boolean enabled = true;
    public String defaultMapKey = DEFAULT_MAP_KEY;
    public final Map<String, SceneProfile> profiles = new LinkedHashMap<>();

    public SceneMotionSettings() {
        profiles.put(DEFAULT_MAP_KEY, SceneProfile.createDefault());
    }

    public static SceneMotionSettings createDefault() {
        return new SceneMotionSettings();
    }

    public SceneProfile getProfile(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) mapKey = defaultMapKey;
        SceneProfile profile = profiles.get(mapKey);
        if (profile == null) {
            profile = profiles.get(DEFAULT_MAP_KEY);
        }
        return profile != null ? profile : SceneProfile.createDefault();
    }

    public SceneProfile getOrCreateProfile(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) mapKey = defaultMapKey;
        return profiles.computeIfAbsent(mapKey, k -> {
            SceneProfile def = profiles.get(DEFAULT_MAP_KEY);
            return def != null ? def.copy() : SceneProfile.createDefault();
        });
    }

    public Map<String, SceneProfile> getAllProfiles() {
        return Collections.unmodifiableMap(profiles);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("enabled", enabled);
        json.addProperty("defaultMapKey", defaultMapKey != null ? defaultMapKey : DEFAULT_MAP_KEY);

        JsonObject profilesObj = new JsonObject();
        for (Map.Entry<String, SceneProfile> e : profiles.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                profilesObj.add(e.getKey(), e.getValue().toJson());
            }
        }
        json.add("profiles", profilesObj);
        return json;
    }

    public static SceneMotionSettings fromJson(JsonObject json) {
        SceneMotionSettings settings = new SceneMotionSettings();
        if (json == null) return settings;

        if (json.has("schemaVersion")) {
            settings.schemaVersion = json.get("schemaVersion").getAsInt();
        }
        if (json.has("enabled")) {
            settings.enabled = json.get("enabled").getAsBoolean();
        }
        if (json.has("defaultMapKey")) {
            settings.defaultMapKey = json.get("defaultMapKey").getAsString();
        }

        if (json.has("profiles") && json.get("profiles").isJsonObject()) {
            JsonObject profObj = json.getAsJsonObject("profiles");
            settings.profiles.clear();
            for (Map.Entry<String, com.google.gson.JsonElement> e : profObj.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    settings.profiles.put(e.getKey(), SceneProfile.fromJson(e.getValue().getAsJsonObject()));
                }
            }
            if (!settings.profiles.containsKey(DEFAULT_MAP_KEY)) {
                settings.profiles.put(DEFAULT_MAP_KEY, SceneProfile.createDefault());
            }
        }
        return settings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneMotionSettings that)) return false;
        return schemaVersion == that.schemaVersion &&
                enabled == that.enabled &&
                Objects.equals(defaultMapKey, that.defaultMapKey) &&
                Objects.equals(profiles, that.profiles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, enabled, defaultMapKey, profiles);
    }
}
