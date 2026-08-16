package com.habitrain.core.config;

import com.google.gson.JsonObject;

/**
 * Player-count based map draw under {@link ModeMapVoteSettings} (replaces the old
 * fixed pool rotation). When {@link #enabled}, each round's map candidates are
 * randomly drawn from maps matching the current player count, up to {@link #drawCount};
 * if fewer maps match, the remaining slots are filled from non-matching maps (marked as
 * "not recommended" in the vote UI).
 */
public final class MapPlayerCountSettings {
    public static final int MIN_DRAW_COUNT = 1;
    public static final int MAX_DRAW_COUNT = 8;
    public static final int DEFAULT_DRAW_COUNT = 4;

    public boolean enabled = false;
    public int drawCount = DEFAULT_DRAW_COUNT;

    public MapPlayerCountSettings() {}

    public static MapPlayerCountSettings createDefault() {
        return new MapPlayerCountSettings();
    }

    public int clampDrawCount(int value) {
        return Math.max(MIN_DRAW_COUNT, Math.min(MAX_DRAW_COUNT, value));
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("drawCount", clampDrawCount(drawCount));
        return o;
    }

    public static MapPlayerCountSettings fromJson(JsonObject o) {
        MapPlayerCountSettings s = createDefault();
        if (o == null) return s;
        if (o.has("enabled")) s.enabled = o.get("enabled").getAsBoolean();
        if (o.has("drawCount")) s.drawCount = s.clampDrawCount(o.get("drawCount").getAsInt());
        return s;
    }
}
