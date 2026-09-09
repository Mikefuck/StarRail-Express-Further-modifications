package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.habitrain.core.scene.model.SceneMotionMigration;
import com.habitrain.core.scene.model.SceneBackgroundConfig;
import com.habitrain.core.scene.model.SceneBackgroundKey;
import com.habitrain.core.scene.model.SceneProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * 移动场景系统根配置（保存在 config/habitrain_core.json 的 sceneMotion 节点）。
 */
public final class SceneMotionSettings {
    public static final int CURRENT_SCHEMA_VERSION = 4;
    public static final String DEFAULT_MAP_KEY = "__default__";
    public static final int MAX_BACKGROUNDS_PER_MAP = 5;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public boolean enabled = true;
    public String defaultMapKey = DEFAULT_MAP_KEY;
    public final Map<String, SceneProfile> profiles = new LinkedHashMap<>();
    /** Additional backgrounds only. The legacy/default profile remains in {@link #profiles}. */
    public final Map<String, LinkedHashMap<String, SceneBackgroundConfig>> backgrounds = new LinkedHashMap<>();

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
            return def != null ? def.copyForNewProfile() : SceneProfile.createDefault();
        });
    }

    public Map<String, SceneProfile> getAllProfiles() {
        return Collections.unmodifiableMap(profiles);
    }

    public SceneProfile getBackgroundProfile(String mapKey, String backgroundId) {
        String map = SceneBackgroundKey.normalizeMapKey(mapKey);
        String id = SceneBackgroundKey.normalizeBackgroundId(backgroundId);
        if (SceneBackgroundKey.DEFAULT_ID.equals(id)) return getProfile(map);
        SceneBackgroundConfig background = backgrounds.getOrDefault(map, new LinkedHashMap<>()).get(id);
        return background != null ? background.getProfile() : getProfile(map);
    }

    public String getBackgroundName(String mapKey, String backgroundId) {
        String id = SceneBackgroundKey.normalizeBackgroundId(backgroundId);
        if (SceneBackgroundKey.DEFAULT_ID.equals(id)) return "默认设置";
        SceneBackgroundConfig background = backgrounds
                .getOrDefault(SceneBackgroundKey.normalizeMapKey(mapKey), new LinkedHashMap<>()).get(id);
        return background != null ? background.getName() : id;
    }

    public List<ResolvedBackground> getResolvedBackgrounds(String mapKey) {
        String map = SceneBackgroundKey.normalizeMapKey(mapKey);
        List<ResolvedBackground> result = new ArrayList<>();
        result.add(new ResolvedBackground(SceneBackgroundKey.DEFAULT_ID, "默认设置", getProfile(map), true));
        LinkedHashMap<String, SceneBackgroundConfig> additional = backgrounds.get(map);
        if (additional != null) {
            additional.forEach((id, config) -> result.add(new ResolvedBackground(
                    id, config.getName(), config.getProfile(), false)));
        }
        return Collections.unmodifiableList(result);
    }

    public boolean putBackground(String mapKey, String backgroundId, SceneBackgroundConfig config) {
        String map = SceneBackgroundKey.normalizeMapKey(mapKey);
        String id = SceneBackgroundKey.normalizeBackgroundId(backgroundId);
        if (SceneBackgroundKey.DEFAULT_ID.equals(id) || config == null) return false;
        LinkedHashMap<String, SceneBackgroundConfig> mapBackgrounds =
                backgrounds.computeIfAbsent(map, ignored -> new LinkedHashMap<>());
        if (!mapBackgrounds.containsKey(id) && mapBackgrounds.size() >= MAX_BACKGROUNDS_PER_MAP - 1) return false;
        mapBackgrounds.put(id, config);
        return true;
    }

    public boolean removeBackground(String mapKey, String backgroundId) {
        String map = SceneBackgroundKey.normalizeMapKey(mapKey);
        String id = SceneBackgroundKey.normalizeBackgroundId(backgroundId);
        if (SceneBackgroundKey.DEFAULT_ID.equals(id)) return false;
        LinkedHashMap<String, SceneBackgroundConfig> mapBackgrounds = backgrounds.get(map);
        if (mapBackgrounds == null || mapBackgrounds.remove(id) == null) return false;
        if (mapBackgrounds.isEmpty()) backgrounds.remove(map);
        return true;
    }

    public record ResolvedBackground(String id, String name, SceneProfile profile, boolean fallback) {
        public String assetKey(String mapKey) { return SceneBackgroundKey.assetKey(mapKey, id); }
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
        JsonObject backgroundsObj = new JsonObject();
        for (Map.Entry<String, LinkedHashMap<String, SceneBackgroundConfig>> mapEntry : backgrounds.entrySet()) {
            JsonObject mapObj = new JsonObject();
            int count = 0;
            for (Map.Entry<String, SceneBackgroundConfig> entry : mapEntry.getValue().entrySet()) {
                if (count >= MAX_BACKGROUNDS_PER_MAP - 1) break;
                String id = SceneBackgroundKey.normalizeBackgroundId(entry.getKey());
                if (!SceneBackgroundKey.DEFAULT_ID.equals(id) && entry.getValue() != null) {
                    mapObj.add(id, entry.getValue().toJson());
                    count++;
                }
            }
            if (mapObj.size() > 0) backgroundsObj.add(mapEntry.getKey(), mapObj);
        }
        json.add("backgrounds", backgroundsObj);
        return json;
    }

    public static SceneMotionSettings fromJson(JsonObject json) {
        SceneMotionSettings settings = new SceneMotionSettings();
        if (json == null) return settings;

        json = SceneMotionMigration.migrateToCurrent(json);

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
        if (json.has("backgrounds") && json.get("backgrounds").isJsonObject()) {
            JsonObject all = json.getAsJsonObject("backgrounds");
            for (Map.Entry<String, com.google.gson.JsonElement> mapEntry : all.entrySet()) {
                if (!mapEntry.getValue().isJsonObject()) continue;
                LinkedHashMap<String, SceneBackgroundConfig> mapBackgrounds = new LinkedHashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> entry
                        : mapEntry.getValue().getAsJsonObject().entrySet()) {
                    if (mapBackgrounds.size() >= MAX_BACKGROUNDS_PER_MAP - 1) break;
                    String id = SceneBackgroundKey.normalizeBackgroundId(entry.getKey());
                    if (!SceneBackgroundKey.DEFAULT_ID.equals(id) && entry.getValue().isJsonObject()) {
                        mapBackgrounds.put(id, SceneBackgroundConfig.fromJson(entry.getValue().getAsJsonObject()));
                    }
                }
                if (!mapBackgrounds.isEmpty()) settings.backgrounds.put(mapEntry.getKey(), mapBackgrounds);
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
                Objects.equals(profiles, that.profiles) &&
                Objects.equals(backgrounds, that.backgrounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, enabled, defaultMapKey, profiles, backgrounds);
    }
}
