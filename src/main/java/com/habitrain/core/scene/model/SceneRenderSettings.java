package com.habitrain.core.scene.model;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 场景渲染设置。
 */
public final class SceneRenderSettings {
    public static final double MIN_DISTANCE = 32.0;
    public static final double MAX_DISTANCE = 512.0;
    public static final double DEFAULT_DISTANCE = 192.0;

    private final double maxDistanceBlocks;
    private final boolean renderTranslucent;

    public SceneRenderSettings(double maxDistanceBlocks, boolean renderTranslucent) {
        double dist = maxDistanceBlocks;
        if (Double.isNaN(dist) || Double.isInfinite(dist)) {
            dist = DEFAULT_DISTANCE;
        }
        this.maxDistanceBlocks = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, dist));
        this.renderTranslucent = renderTranslucent;
    }

    public static SceneRenderSettings createDefault() {
        return new SceneRenderSettings(DEFAULT_DISTANCE, true);
    }

    public double maxDistanceBlocks() { return maxDistanceBlocks; }
    public double getMaxDistanceBlocks() { return maxDistanceBlocks; }
    public boolean renderTranslucent() { return renderTranslucent; }
    public boolean isRenderTranslucent() { return renderTranslucent; }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("maxDistanceBlocks", maxDistanceBlocks);
        json.addProperty("renderTranslucent", renderTranslucent);
        return json;
    }

    public static SceneRenderSettings fromJson(JsonObject json) {
        if (json == null) return createDefault();
        double distance = json.has("maxDistanceBlocks") ? json.get("maxDistanceBlocks").getAsDouble() : DEFAULT_DISTANCE;
        boolean translucent = !json.has("renderTranslucent") || json.get("renderTranslucent").getAsBoolean();
        return new SceneRenderSettings(distance, translucent);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneRenderSettings that)) return false;
        return Double.compare(maxDistanceBlocks, that.maxDistanceBlocks) == 0 && renderTranslucent == that.renderTranslucent;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDistanceBlocks, renderTranslucent);
    }

    @Override
    public String toString() {
        return "SceneRenderSettings[maxDistance=" + maxDistanceBlocks + ", translucent=" + renderTranslucent + "]";
    }
}
