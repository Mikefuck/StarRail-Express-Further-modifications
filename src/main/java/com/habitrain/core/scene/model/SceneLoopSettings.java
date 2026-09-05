package com.habitrain.core.scene.model;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 场景循环平铺设置。
 */
public final class SceneLoopSettings {
    public static final double MIN_DISTANCE = 1.0;
    public static final double MAX_DISTANCE = 4096.0;
    public static final double DEFAULT_DISTANCE = 512.0;
    public static final int DEFAULT_COPIES = 2;

    private final boolean enabled;
    private final SceneLoopDistanceMode distanceMode;
    private final double distanceBlocks;
    private final int copies;

    /**
     * Source-compatible constructor for existing integrations. An explicitly supplied distance
     * keeps the historical CUSTOM semantics.
     */
    public SceneLoopSettings(boolean enabled, double distanceBlocks, int copies) {
        this(enabled, SceneLoopDistanceMode.CUSTOM, distanceBlocks, copies);
    }

    public SceneLoopSettings(boolean enabled, SceneLoopDistanceMode distanceMode,
                             double distanceBlocks, int copies) {
        this.enabled = enabled;
        this.distanceMode = distanceMode != null ? distanceMode : SceneLoopDistanceMode.AUTO;
        double dist = distanceBlocks;
        if (Double.isNaN(dist) || Double.isInfinite(dist)) {
            dist = DEFAULT_DISTANCE;
        }
        this.distanceBlocks = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, dist));
        // 第一版固定为 2 份副本
        this.copies = DEFAULT_COPIES;
    }

    public static SceneLoopSettings createDefault() {
        return new SceneLoopSettings(true, SceneLoopDistanceMode.AUTO,
                DEFAULT_DISTANCE, DEFAULT_COPIES);
    }

    /** Historical schema-1 default: the stored 512 blocks must keep controlling runtime. */
    public static SceneLoopSettings createLegacyDefault() {
        return new SceneLoopSettings(true, SceneLoopDistanceMode.CUSTOM,
                DEFAULT_DISTANCE, DEFAULT_COPIES);
    }

    public boolean isEnabled() { return enabled; }
    public SceneLoopDistanceMode distanceMode() { return distanceMode; }
    public SceneLoopDistanceMode getDistanceMode() { return distanceMode; }
    public double distanceBlocks() { return distanceBlocks; }
    public double getDistanceBlocks() { return distanceBlocks; }
    public int copies() { return copies; }
    public int getCopies() { return copies; }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("distanceMode", distanceMode.name());
        json.addProperty("distanceBlocks", distanceBlocks);
        json.addProperty("copies", copies);
        return json;
    }

    public static SceneLoopSettings fromJson(JsonObject json) {
        if (json == null) return createDefault();
        boolean enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
        SceneLoopDistanceMode mode = json.has("distanceMode")
                ? SceneLoopDistanceMode.fromSerialized(json.get("distanceMode").getAsString())
                : SceneLoopDistanceMode.CUSTOM;
        double distance = json.has("distanceBlocks") ? json.get("distanceBlocks").getAsDouble() : DEFAULT_DISTANCE;
        int copies = json.has("copies") ? json.get("copies").getAsInt() : DEFAULT_COPIES;
        return new SceneLoopSettings(enabled, mode, distance, copies);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneLoopSettings that)) return false;
        return enabled == that.enabled && distanceMode == that.distanceMode
                && Double.compare(distanceBlocks, that.distanceBlocks) == 0 && copies == that.copies;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, distanceMode, distanceBlocks, copies);
    }

    @Override
    public String toString() {
        return "SceneLoopSettings[enabled=" + enabled + ", mode=" + distanceMode
                + ", distance=" + distanceBlocks + ", copies=" + copies + "]";
    }
}
