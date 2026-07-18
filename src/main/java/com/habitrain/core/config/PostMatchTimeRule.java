package com.habitrain.core.config;

import com.google.gson.JsonObject;

public final class PostMatchTimeRule {
    public boolean enabled = false;
    public EnvTimeSpec time = EnvTimeSpec.createDefault();

    public static PostMatchTimeRule createDefault() { return new PostMatchTimeRule(); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.add("time", (time != null ? time : EnvTimeSpec.createDefault()).toJson());
        return o;
    }

    public static PostMatchTimeRule fromJson(JsonObject o) {
        PostMatchTimeRule r = createDefault();
        if (o == null) return r;
        if (o.has("enabled")) r.enabled = o.get("enabled").getAsBoolean();
        if (o.has("time") && o.get("time").isJsonObject()) {
            r.time = EnvTimeSpec.fromJson(o.getAsJsonObject("time"));
        }
        return r;
    }
}
