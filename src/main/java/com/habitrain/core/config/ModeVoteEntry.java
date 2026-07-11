package com.habitrain.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class ModeVoteEntry {
    public boolean enabled = true;
    public String displayName = "";
    public List<String> allowedMaps = new ArrayList<>();

    public static ModeVoteEntry createDefault() {
        return new ModeVoteEntry();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("displayName", displayName != null ? displayName : "");
        JsonArray arr = new JsonArray();
        if (allowedMaps != null) {
            for (String id : allowedMaps) arr.add(id);
        }
        o.add("allowedMaps", arr);
        return o;
    }

    public static ModeVoteEntry fromJson(JsonObject o) {
        ModeVoteEntry e = new ModeVoteEntry();
        if (o.has("enabled")) e.enabled = o.get("enabled").getAsBoolean();
        if (o.has("displayName")) e.displayName = o.get("displayName").getAsString();
        e.allowedMaps = new ArrayList<>();
        if (o.has("allowedMaps") && o.get("allowedMaps").isJsonArray()) {
            for (var el : o.getAsJsonArray("allowedMaps")) {
                String s = el.getAsString();
                if (s != null && !s.isEmpty()) e.allowedMaps.add(s);
            }
        }
        return e;
    }
}
