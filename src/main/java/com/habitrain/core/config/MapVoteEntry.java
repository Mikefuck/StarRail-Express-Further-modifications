package com.habitrain.core.config;

import com.google.gson.JsonObject;

public final class MapVoteEntry {
    public boolean enabled = true;
    public String displayName = "";

    public static MapVoteEntry createDefault() {
        return new MapVoteEntry();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("displayName", displayName != null ? displayName : "");
        return o;
    }

    public static MapVoteEntry fromJson(JsonObject o) {
        MapVoteEntry e = new MapVoteEntry();
        if (o.has("enabled")) e.enabled = o.get("enabled").getAsBoolean();
        if (o.has("displayName")) e.displayName = o.get("displayName").getAsString();
        return e;
    }
}
