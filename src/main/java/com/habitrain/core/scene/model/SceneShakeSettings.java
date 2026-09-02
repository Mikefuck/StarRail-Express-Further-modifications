package com.habitrain.core.scene.model;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 车辆抖动设置（纯渲染端）。
 */
public final class SceneShakeSettings {
    public static final double DEFAULT_TRANSLATION_AMP = 0.025; // 0..0.15 blocks
    public static final double DEFAULT_ROTATION_AMP_DEG = 0.12;  // 0..1.5 degrees
    public static final double DEFAULT_FREQUENCY_HZ = 1.8;       // 0.1..8.0 Hz

    private final boolean enabled;
    private final double translationAmplitude;
    private final double rotationAmplitudeDegrees;
    private final double frequencyHz;

    public SceneShakeSettings(boolean enabled, double translationAmplitude, double rotationAmplitudeDegrees, double frequencyHz) {
        this.enabled = enabled;
        double tAmp = translationAmplitude;
        if (Double.isNaN(tAmp) || Double.isInfinite(tAmp)) tAmp = DEFAULT_TRANSLATION_AMP;
        this.translationAmplitude = Math.max(0.0, Math.min(0.15, tAmp));

        double rAmp = rotationAmplitudeDegrees;
        if (Double.isNaN(rAmp) || Double.isInfinite(rAmp)) rAmp = DEFAULT_ROTATION_AMP_DEG;
        this.rotationAmplitudeDegrees = Math.max(0.0, Math.min(1.5, rAmp));

        double freq = frequencyHz;
        if (Double.isNaN(freq) || Double.isInfinite(freq)) freq = DEFAULT_FREQUENCY_HZ;
        this.frequencyHz = Math.max(0.1, Math.min(8.0, freq));
    }

    public static SceneShakeSettings createDefault() {
        return new SceneShakeSettings(true, DEFAULT_TRANSLATION_AMP, DEFAULT_ROTATION_AMP_DEG, DEFAULT_FREQUENCY_HZ);
    }

    public static SceneShakeSettings createDisabled() {
        return new SceneShakeSettings(false, 0.0, 0.0, DEFAULT_FREQUENCY_HZ);
    }

    public static SceneShakeSettings createSubtlePreset() {
        return new SceneShakeSettings(true, 0.015, 0.06, 1.2);
    }

    public static SceneShakeSettings createStandardPreset() {
        return new SceneShakeSettings(true, DEFAULT_TRANSLATION_AMP, DEFAULT_ROTATION_AMP_DEG, DEFAULT_FREQUENCY_HZ);
    }

    public static SceneShakeSettings createIntensePreset() {
        return new SceneShakeSettings(true, 0.05, 0.35, 2.5);
    }

    public boolean isEnabled() { return enabled; }
    public double translationAmplitude() { return translationAmplitude; }
    public double getTranslationAmplitudeBlocks() { return translationAmplitude; }
    public double getTranslationAmplitude() { return translationAmplitude; }
    public double rotationAmplitudeDegrees() { return rotationAmplitudeDegrees; }
    public double getRotationAmplitudeDegrees() { return rotationAmplitudeDegrees; }
    public double frequencyHz() { return frequencyHz; }
    public double getFrequencyHz() { return frequencyHz; }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("translationAmplitude", translationAmplitude);
        json.addProperty("rotationAmplitudeDegrees", rotationAmplitudeDegrees);
        json.addProperty("frequencyHz", frequencyHz);
        return json;
    }

    public static SceneShakeSettings fromJson(JsonObject json) {
        if (json == null) return createDefault();
        boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
        double trans = json.has("translationAmplitude") ? json.get("translationAmplitude").getAsDouble() : DEFAULT_TRANSLATION_AMP;
        double rot = json.has("rotationAmplitudeDegrees") ? json.get("rotationAmplitudeDegrees").getAsDouble() : DEFAULT_ROTATION_AMP_DEG;
        double freq = json.has("frequencyHz") ? json.get("frequencyHz").getAsDouble() : DEFAULT_FREQUENCY_HZ;
        return new SceneShakeSettings(enabled, trans, rot, freq);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneShakeSettings that)) return false;
        return enabled == that.enabled && Double.compare(translationAmplitude, that.translationAmplitude) == 0 && Double.compare(rotationAmplitudeDegrees, that.rotationAmplitudeDegrees) == 0 && Double.compare(frequencyHz, that.frequencyHz) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, translationAmplitude, rotationAmplitudeDegrees, frequencyHz);
    }

    @Override
    public String toString() {
        return "SceneShakeSettings[enabled=" + enabled + ", trans=" + translationAmplitude + ", rot=" + rotationAmplitudeDegrees + ", freq=" + frequencyHz + "]";
    }
}
