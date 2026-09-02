package com.habitrain.core.scene.model;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 场景旋转定义（欧拉角，单位：度）。
 */
public final class SceneRotation {
    public static final SceneRotation ZERO = new SceneRotation(0.0f, 0.0f, 0.0f);

    private final float yaw;
    private final float pitch;
    private final float roll;

    public SceneRotation(float yaw, float pitch, float roll) {
        this.yaw = normalizeAngle(yaw);
        this.pitch = normalizeAngle(pitch);
        this.roll = normalizeAngle(roll);
    }

    public SceneRotation(double yaw, double pitch, double roll) {
        this((float) yaw, (float) pitch, (float) roll);
    }

    public static float normalizeAngle(float angle) {
        if (Float.isNaN(angle) || Float.isInfinite(angle)) return 0.0f;
        angle = angle % 360.0f;
        if (angle > 180.0f) angle -= 360.0f;
        else if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }

    public double yawDegrees() { return yaw; }
    public double pitchDegrees() { return pitch; }
    public double rollDegrees() { return roll; }

    public boolean isZero() {
        return Math.abs(yaw) < 1e-4f && Math.abs(pitch) < 1e-4f && Math.abs(roll) < 1e-4f;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("yaw", yaw);
        json.addProperty("pitch", pitch);
        json.addProperty("roll", roll);
        return json;
    }

    public static SceneRotation fromJson(JsonObject json) {
        if (json == null) return ZERO;
        float y = json.has("yaw") ? json.get("yaw").getAsFloat() : 0.0f;
        float p = json.has("pitch") ? json.get("pitch").getAsFloat() : 0.0f;
        float r = json.has("roll") ? json.get("roll").getAsFloat() : 0.0f;
        return new SceneRotation(y, p, r);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneRotation that)) return false;
        return Float.compare(yaw, that.yaw) == 0 && Float.compare(pitch, that.pitch) == 0 && Float.compare(roll, that.roll) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(yaw, pitch, roll);
    }

    @Override
    public String toString() {
        return "SceneRotation[yaw=" + yaw + ", pitch=" + pitch + ", roll=" + roll + "]";
    }
}
