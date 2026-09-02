package com.habitrain.core.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Identifies which UI entry point submitted a server configuration update. */
public enum ConfigUpdateScope {
    FULL_MOD_MENU,
    BACKPACK_TASKS,
    BACKPACK_MAP_VOTE,
    ADMIN_SCENE_TOOL;

    private static final String JSON_KEY = "_habitrain_config_update_scope";
    private static final String SCENE_MAP_JSON_KEY = "_habitrain_scene_map_key";

    /**
     * Keeps the existing ConfigUpdatePayload wire format compatible with older clients and servers.
     * The server still authorizes the resolved scope independently, so this metadata cannot grant access.
     */
    public static String attachToConfigJson(String configJson, ConfigUpdateScope scope) {
        return attachToConfigJson(configJson, scope, "");
    }

    public static String attachToConfigJson(String configJson, ConfigUpdateScope scope, String sceneMapKey) {
        JsonElement parsed = JsonParser.parseString(configJson == null ? "{}" : configJson);
        if (!parsed.isJsonObject()) return configJson;
        JsonObject root = parsed.getAsJsonObject();
        root.addProperty(JSON_KEY, (scope == null ? FULL_MOD_MENU : scope).name());
        if (scope == ADMIN_SCENE_TOOL && sceneMapKey != null && !sceneMapKey.isBlank()) {
            root.addProperty(SCENE_MAP_JSON_KEY, sceneMapKey.trim());
        }
        return root.toString();
    }

    /** Missing or invalid metadata is deliberately treated as full Mod Menu access (the safest default). */
    public static ConfigUpdateScope fromConfigJson(String configJson) {
        try {
            JsonElement parsed = JsonParser.parseString(configJson == null ? "{}" : configJson);
            if (!parsed.isJsonObject()) return FULL_MOD_MENU;
            JsonElement value = parsed.getAsJsonObject().get(JSON_KEY);
            if (value == null || !value.isJsonPrimitive()) return FULL_MOD_MENU;
            return ConfigUpdateScope.valueOf(value.getAsString());
        } catch (RuntimeException ignored) {
            return FULL_MOD_MENU;
        }
    }

    public static String sceneMapKeyFromConfigJson(String configJson) {
        try {
            JsonElement parsed = JsonParser.parseString(configJson == null ? "{}" : configJson);
            if (!parsed.isJsonObject()) return "";
            JsonElement value = parsed.getAsJsonObject().get(SCENE_MAP_JSON_KEY);
            if (value == null || !value.isJsonPrimitive()) return "";
            String mapKey = value.getAsString();
            return mapKey == null ? "" : mapKey.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
