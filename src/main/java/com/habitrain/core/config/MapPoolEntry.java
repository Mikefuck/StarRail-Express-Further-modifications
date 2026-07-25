package com.habitrain.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** One of the fixed 5 daily map-pool slots. */
public final class MapPoolEntry {
    public String displayName = "";
    public List<String> mapIds = new ArrayList<>();
    public boolean enabled = true;

    public static MapPoolEntry createDefault(int index1Based) {
        MapPoolEntry e = new MapPoolEntry();
        e.displayName = "池" + index1Based;
        e.mapIds = new ArrayList<>();
        e.enabled = true;
        return e;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("displayName", displayName != null ? displayName : "");
        o.addProperty("enabled", enabled);
        JsonArray arr = new JsonArray();
        if (mapIds != null) {
            for (String id : mapIds) {
                if (id != null && !id.isEmpty()) arr.add(id);
            }
        }
        o.add("mapIds", arr);
        return o;
    }

    public static MapPoolEntry fromJson(JsonObject o) {
        MapPoolEntry e = new MapPoolEntry();
        if (o.has("displayName")) e.displayName = o.get("displayName").getAsString();
        if (o.has("enabled")) e.enabled = o.get("enabled").getAsBoolean();
        e.mapIds = new ArrayList<>();
        if (o.has("mapIds") && o.get("mapIds").isJsonArray()) {
            for (var el : o.getAsJsonArray("mapIds")) {
                String s = el.getAsString();
                if (s != null && !s.isEmpty()) e.mapIds.add(s);
            }
        }
        return e;
    }
}
