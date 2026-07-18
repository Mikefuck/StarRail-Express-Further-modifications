package com.habitrain.core.config;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EnvironmentSettings {
    public EnvProfile lobby = EnvProfile.createLobbyDefault();
    public EnvProfile matchDefaultProfile = EnvProfile.createMatchDefault();
    public final Map<String, EnvProfile> matchMaps = new LinkedHashMap<>();
    public PostMatchTimeRule goodWin = PostMatchTimeRule.createDefault();
    public PostMatchTimeRule otherWin = PostMatchTimeRule.createDefault();
    public boolean lowPlayerRainEnabled = true;
    public int lowPlayerRainMinPlayers = 8;

    public static EnvironmentSettings createDefault() {
        return new EnvironmentSettings();
    }

    public int clampedMinPlayers() {
        return Math.max(1, lowPlayerRainMinPlayers);
    }

    /** map entry if present, else matchDefaultProfile (never null). */
    public EnvProfile resolveMatchProfile(String mapId) {
        if (mapId != null && !mapId.isBlank()) {
            EnvProfile p = matchMaps.get(mapId);
            if (p != null) return p;
        }
        return matchDefaultProfile != null ? matchDefaultProfile : EnvProfile.createMatchDefault();
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.add("lobby", (lobby != null ? lobby : EnvProfile.createLobbyDefault()).toJson());

        JsonObject match = new JsonObject();
        match.add("defaultProfile",
                (matchDefaultProfile != null ? matchDefaultProfile : EnvProfile.createMatchDefault()).toJson());
        JsonObject maps = new JsonObject();
        for (Map.Entry<String, EnvProfile> e : matchMaps.entrySet()) {
            maps.add(e.getKey(), e.getValue().toJson());
        }
        match.add("maps", maps);
        root.add("match", match);

        JsonObject post = new JsonObject();
        post.add("goodWin", (goodWin != null ? goodWin : PostMatchTimeRule.createDefault()).toJson());
        post.add("otherWin", (otherWin != null ? otherWin : PostMatchTimeRule.createDefault()).toJson());
        root.add("postMatch", post);

        JsonObject rain = new JsonObject();
        rain.addProperty("enabled", lowPlayerRainEnabled);
        rain.addProperty("minPlayers", clampedMinPlayers());
        root.add("lowPlayerRain", rain);
        return root;
    }

    public static EnvironmentSettings fromJson(JsonObject o) {
        EnvironmentSettings s = createDefault();
        if (o == null) return s;
        if (o.has("lobby") && o.get("lobby").isJsonObject()) {
            s.lobby = EnvProfile.fromJson(o.getAsJsonObject("lobby"));
        }
        if (o.has("match") && o.get("match").isJsonObject()) {
            JsonObject match = o.getAsJsonObject("match");
            if (match.has("defaultProfile") && match.get("defaultProfile").isJsonObject()) {
                s.matchDefaultProfile = EnvProfile.fromJson(match.getAsJsonObject("defaultProfile"));
            }
            if (match.has("maps") && match.get("maps").isJsonObject()) {
                JsonObject maps = match.getAsJsonObject("maps");
                for (var e : maps.entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        s.matchMaps.put(e.getKey(), EnvProfile.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }
        }
        if (o.has("postMatch") && o.get("postMatch").isJsonObject()) {
            JsonObject post = o.getAsJsonObject("postMatch");
            if (post.has("goodWin") && post.get("goodWin").isJsonObject()) {
                s.goodWin = PostMatchTimeRule.fromJson(post.getAsJsonObject("goodWin"));
            }
            if (post.has("otherWin") && post.get("otherWin").isJsonObject()) {
                s.otherWin = PostMatchTimeRule.fromJson(post.getAsJsonObject("otherWin"));
            }
        }
        if (o.has("lowPlayerRain") && o.get("lowPlayerRain").isJsonObject()) {
            JsonObject rain = o.getAsJsonObject("lowPlayerRain");
            if (rain.has("enabled")) s.lowPlayerRainEnabled = rain.get("enabled").getAsBoolean();
            if (rain.has("minPlayers")) {
                s.lowPlayerRainMinPlayers = Math.max(1, rain.get("minPlayers").getAsInt());
            }
        }
        return s;
    }
}
