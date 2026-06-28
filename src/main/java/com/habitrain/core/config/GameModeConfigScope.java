package com.habitrain.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * per-GameMode 配置作用域。
 * DLC 模组可将任意自定义配置存入此处。
 */
public class GameModeConfigScope {
    private final String gameModeId;
    private boolean enabled = true;
    private final Map<String, JsonElement> customSettings = new HashMap<>();

    public GameModeConfigScope(String gameModeId) {
        this.gameModeId = gameModeId;
    }

    public String getGameModeId() { return gameModeId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public JsonElement getSetting(String key) { return customSettings.get(key); }
    public void setSetting(String key, JsonElement value) { customSettings.put(key, value); }
    public Map<String, JsonElement> getCustomSettings() { return customSettings; }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        if (!customSettings.isEmpty()) {
            JsonObject custom = new JsonObject();
            for (Map.Entry<String, JsonElement> e : customSettings.entrySet()) {
                custom.add(e.getKey(), e.getValue());
            }
            obj.add("customSettings", custom);
        }
        return obj;
    }

    public static GameModeConfigScope fromJson(String gameModeId, JsonObject json) {
        GameModeConfigScope scope = new GameModeConfigScope(gameModeId);
        if (json.has("enabled")) scope.enabled = json.get("enabled").getAsBoolean();
        if (json.has("customSettings")) {
            JsonObject custom = json.getAsJsonObject("customSettings");
            for (Map.Entry<String, JsonElement> e : custom.entrySet()) {
                scope.customSettings.put(e.getKey(), e.getValue());
            }
        }
        return scope;
    }
}
