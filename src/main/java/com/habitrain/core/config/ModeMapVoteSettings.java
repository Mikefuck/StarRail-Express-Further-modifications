package com.habitrain.core.config;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModeMapVoteSettings {
    public boolean enabled = true;
    public int modeDurationSeconds = 15;
    public int mapDurationSeconds = 15;
    public final Map<String, ModeVoteEntry> modes = new LinkedHashMap<>();
    public final Map<String, MapVoteEntry> maps = new LinkedHashMap<>();

    public static ModeMapVoteSettings createDefault() {
        return new ModeMapVoteSettings();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("modeDurationSeconds", modeDurationSeconds);
        o.addProperty("mapDurationSeconds", mapDurationSeconds);

        JsonObject modesObj = new JsonObject();
        for (Map.Entry<String, ModeVoteEntry> e : modes.entrySet()) {
            modesObj.add(e.getKey(), e.getValue().toJson());
        }
        o.add("modes", modesObj);

        JsonObject mapsObj = new JsonObject();
        for (Map.Entry<String, MapVoteEntry> e : maps.entrySet()) {
            mapsObj.add(e.getKey(), e.getValue().toJson());
        }
        o.add("maps", mapsObj);
        return o;
    }

    public static ModeMapVoteSettings fromJson(JsonObject o) {
        ModeMapVoteSettings s = createDefault();
        if (o.has("enabled")) s.enabled = o.get("enabled").getAsBoolean();
        if (o.has("modeDurationSeconds")) {
            s.modeDurationSeconds = clampDuration(o.get("modeDurationSeconds").getAsInt());
        }
        if (o.has("mapDurationSeconds")) {
            s.mapDurationSeconds = clampDuration(o.get("mapDurationSeconds").getAsInt());
        }
        if (o.has("modes") && o.get("modes").isJsonObject()) {
            JsonObject modesObj = o.getAsJsonObject("modes");
            for (var e : modesObj.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    s.modes.put(e.getKey(), ModeVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        if (o.has("maps") && o.get("maps").isJsonObject()) {
            JsonObject mapsObj = o.getAsJsonObject("maps");
            for (var e : mapsObj.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    s.maps.put(e.getKey(), MapVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        return s;
    }

    /** Insert missing keys only; never overwrite existing entries. */
    public void ensureDefaults(Iterable<String> modeIds, Iterable<String> mapIds) {
        for (String id : modeIds) {
            modes.computeIfAbsent(id, k -> ModeVoteEntry.createDefault());
        }
        for (String id : mapIds) {
            maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
        }
    }

    private static int clampDuration(int seconds) {
        return Math.max(5, Math.min(120, seconds));
    }
}
