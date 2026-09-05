package com.habitrain.core.scene.model;

import com.google.gson.JsonObject;

import java.util.Objects;

/** A named non-default background belonging to one map. */
public final class SceneBackgroundConfig {
    private String name;
    private SceneProfile profile;

    public SceneBackgroundConfig(String name, SceneProfile profile) {
        setName(name);
        this.profile = profile != null ? profile : SceneProfile.createDefault();
    }

    public String getName() { return name; }

    public void setName(String name) {
        String normalized = name == null ? "" : name.trim();
        this.name = normalized.isEmpty() ? "动态背景" : normalized.substring(0, Math.min(32, normalized.length()));
    }

    public SceneProfile getProfile() { return profile; }
    public void setProfile(SceneProfile profile) { this.profile = profile != null ? profile : SceneProfile.createDefault(); }

    public SceneBackgroundConfig copy() {
        return new SceneBackgroundConfig(name, profile.copy());
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.add("profile", profile.toJson());
        return json;
    }

    public static SceneBackgroundConfig fromJson(JsonObject json) {
        if (json == null) return new SceneBackgroundConfig("动态背景", SceneProfile.createDefault());
        String name = json.has("name") ? json.get("name").getAsString() : "动态背景";
        SceneProfile profile = json.has("profile") && json.get("profile").isJsonObject()
                ? SceneProfile.fromJson(json.getAsJsonObject("profile")) : SceneProfile.createDefault();
        return new SceneBackgroundConfig(name, profile);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneBackgroundConfig that)) return false;
        return Objects.equals(name, that.name) && Objects.equals(profile, that.profile);
    }

    @Override
    public int hashCode() { return Objects.hash(name, profile); }
}
