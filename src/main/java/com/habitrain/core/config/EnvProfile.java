package com.habitrain.core.config;

import com.google.gson.JsonObject;

public final class EnvProfile {
    public enum Weather { CLEAR, RAIN, THUNDER }

    public boolean enabled = true;
    public EnvTimeSpec time = EnvTimeSpec.createDefault();
    public Weather weather = Weather.CLEAR;
    public boolean snow = true;
    public boolean sand = true;
    public boolean fog = true;
    public float fogEnd = 192f;
    public boolean daylightCycle = false;
    public boolean weatherCycle = false;

    public static EnvProfile createLobbyDefault() {
        EnvProfile p = new EnvProfile();
        p.enabled = true;
        return p;
    }

    /** Match default is opt-in so map-native AreasSettings win until configured. */
    public static EnvProfile createMatchDefault() {
        EnvProfile p = createLobbyDefault();
        p.enabled = false;
        return p;
    }

    public EnvProfile copy() {
        return fromJson(toJson());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.add("time", (time != null ? time : EnvTimeSpec.createDefault()).toJson());
        o.addProperty("weather", (weather != null ? weather : Weather.CLEAR).name());
        o.addProperty("snow", snow);
        o.addProperty("sand", sand);
        o.addProperty("fog", fog);
        o.addProperty("fogEnd", fogEnd);
        o.addProperty("daylightCycle", daylightCycle);
        o.addProperty("weatherCycle", weatherCycle);
        return o;
    }

    public static EnvProfile fromJson(JsonObject o) {
        EnvProfile p = createLobbyDefault();
        if (o == null) return p;
        if (o.has("enabled")) p.enabled = o.get("enabled").getAsBoolean();
        if (o.has("time") && o.get("time").isJsonObject()) {
            p.time = EnvTimeSpec.fromJson(o.getAsJsonObject("time"));
        }
        if (o.has("weather")) {
            try { p.weather = Weather.valueOf(o.get("weather").getAsString().trim().toUpperCase()); }
            catch (Exception ignored) { p.weather = Weather.CLEAR; }
        }
        if (o.has("snow")) p.snow = o.get("snow").getAsBoolean();
        if (o.has("sand")) p.sand = o.get("sand").getAsBoolean();
        if (o.has("fog")) p.fog = o.get("fog").getAsBoolean();
        if (o.has("fogEnd")) p.fogEnd = o.get("fogEnd").getAsFloat();
        if (o.has("daylightCycle")) p.daylightCycle = o.get("daylightCycle").getAsBoolean();
        if (o.has("weatherCycle")) p.weatherCycle = o.get("weatherCycle").getAsBoolean();
        return p;
    }
}
