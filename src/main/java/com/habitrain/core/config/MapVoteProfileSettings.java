package com.habitrain.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional Mod Menu overrides for the map information sheet shown during map voting.
 * A {@code null} value on {@link MapVoteEntry#profile} keeps the world-local maps.json
 * profile authoritative.
 */
public final class MapVoteProfileSettings {
    public static final int MAX_DESCRIPTION_LENGTH = 256;
    public static final int MAX_TAGS = 8;
    public static final int MAX_TAG_LENGTH = 32;
    public static final int MAX_PREVIEW_PATH_LENGTH = 256;

    public String description = "";
    public List<String> tags = new ArrayList<>();
    /** Path relative to the world's train_maps/map_vote directory. Blank uses the placeholder. */
    public String previewPath = "";

    public static MapVoteProfileSettings createDefault() {
        return new MapVoteProfileSettings();
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("description", trim(description, MAX_DESCRIPTION_LENGTH));
        JsonArray tagArray = new JsonArray();
        for (String tag : normalizedTags(tags)) {
            tagArray.add(tag);
        }
        out.add("tags", tagArray);
        out.addProperty("preview", trim(previewPath, MAX_PREVIEW_PATH_LENGTH));
        return out;
    }

    public static MapVoteProfileSettings fromJson(JsonObject json) {
        MapVoteProfileSettings settings = createDefault();
        if (json == null) return settings;
        if (json.has("description") && json.get("description").isJsonPrimitive()) {
            settings.description = trim(json.get("description").getAsString(), MAX_DESCRIPTION_LENGTH);
        }
        if (json.has("tags") && json.get("tags").isJsonArray()) {
            List<String> parsed = new ArrayList<>();
            for (var element : json.getAsJsonArray("tags")) {
                if (element.isJsonPrimitive()) parsed.add(element.getAsString());
            }
            settings.tags = normalizedTags(parsed);
        }
        if (json.has("preview") && json.get("preview").isJsonPrimitive()) {
            settings.previewPath = trim(json.get("preview").getAsString(), MAX_PREVIEW_PATH_LENGTH);
        }
        return settings;
    }

    public static List<String> normalizedTags(Iterable<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String value : values) {
            String normalized = trim(value, MAX_TAG_LENGTH).trim();
            if (!normalized.isEmpty() && !out.contains(normalized)) {
                out.add(normalized);
                if (out.size() >= MAX_TAGS) break;
            }
        }
        return out;
    }

    private static String trim(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
