package com.habitrain.core.config;

import com.google.gson.JsonObject;

/** Time either as SRE-aligned preset or raw dayTime tick 0..23999. */
public final class EnvTimeSpec {
    public enum Mode { PRESET, TICK }
    public enum Preset {
        DAY(1000), NOON(6000), NIGHT(13000), MIDNIGHT(18000), SUNDOWN(12800);
        public final int time;
        Preset(int time) { this.time = time; }
        public static Preset fromName(String s) {
            if (s == null) return DAY;
            try { return Preset.valueOf(s.trim().toUpperCase()); }
            catch (Exception e) { return DAY; }
        }
        public static Preset nearest(int tick) {
            Preset best = DAY;
            int bestDist = Integer.MAX_VALUE;
            int t = clampTick(tick);
            for (Preset p : values()) {
                int d = Math.abs(p.time - t);
                if (d < bestDist) { bestDist = d; best = p; }
            }
            return best;
        }
    }

    public Mode mode = Mode.PRESET;
    public Preset preset = Preset.DAY;
    public int tick = 1000;

    public static EnvTimeSpec createDefault() { return new EnvTimeSpec(); }

    public static int clampTick(int t) {
        if (t < 0) return 0;
        if (t > 23999) return 23999;
        return t;
    }

    public long resolveDayTime() {
        if (mode == Mode.TICK) return clampTick(tick);
        return (preset != null ? preset : Preset.DAY).time;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("preset", (preset != null ? preset : Preset.DAY).name());
        o.addProperty("tick", clampTick(tick));
        return o;
    }

    public static EnvTimeSpec fromJson(JsonObject o) {
        EnvTimeSpec s = createDefault();
        if (o == null) return s;
        if (o.has("mode")) {
            try { s.mode = Mode.valueOf(o.get("mode").getAsString().trim().toUpperCase()); }
            catch (Exception ignored) { s.mode = Mode.PRESET; }
        }
        if (o.has("preset")) s.preset = Preset.fromName(o.get("preset").getAsString());
        if (o.has("tick")) s.tick = clampTick(o.get("tick").getAsInt());
        return s;
    }
}
