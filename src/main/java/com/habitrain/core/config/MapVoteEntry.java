package com.habitrain.core.config;

import com.google.gson.JsonObject;

public final class MapVoteEntry {
    public boolean enabled = true;
    public String displayName = "";
    /** Recommended minimum players for this map; 0 = no minimum (any count matches). */
    public int minPlayers = 0;
    /** Recommended maximum players for this map; 0 = unlimited. */
    public int maxPlayers = 0;

    public static MapVoteEntry createDefault() {
        return new MapVoteEntry();
    }

    /** True when {@code playerCount} falls inside this map's recommended range (0 = open). */
    public boolean matchesPlayerCount(int playerCount) {
        if (minPlayers > 0 && playerCount < minPlayers) return false;
        if (maxPlayers > 0 && playerCount > maxPlayers) return false;
        return true;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("displayName", displayName != null ? displayName : "");
        o.addProperty("minPlayers", Math.max(0, minPlayers));
        o.addProperty("maxPlayers", Math.max(0, maxPlayers));
        return o;
    }

    public static MapVoteEntry fromJson(JsonObject o) {
        MapVoteEntry e = new MapVoteEntry();
        if (o.has("enabled")) e.enabled = o.get("enabled").getAsBoolean();
        if (o.has("displayName")) e.displayName = o.get("displayName").getAsString();
        if (o.has("minPlayers")) e.minPlayers = Math.max(0, o.get("minPlayers").getAsInt());
        if (o.has("maxPlayers")) e.maxPlayers = Math.max(0, o.get("maxPlayers").getAsInt());
        return e;
    }
}
