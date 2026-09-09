package com.habitrain.core.scene.model;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 场景车外音设置。
 */
public final class SceneSoundSettings {
    public static final String DEFAULT_SOUND_ID = "habitrain_core:moving_scene_outside";
    private static final String LEGACY_TRUNCATED_DEFAULT_SOUND_ID = "habitrain_core:moving_scene_outs";
    public static final float DEFAULT_VOLUME = 0.7f;
    public static final float DEFAULT_PITCH = 1.0f;
    public static final int DEFAULT_FADE_TICKS = 20;

    private final boolean enabled;
    private final String soundId;
    private final float volume;
    private final float pitch;
    private final int fadeTicks;

    public SceneSoundSettings(boolean enabled, String soundId, float volume, float pitch, int fadeTicks) {
        this.enabled = enabled;
        String normalizedSoundId = (soundId == null || soundId.isBlank()) ? DEFAULT_SOUND_ID : soundId.trim();
        // Older scene UI fields used EditBox's 32-character default and persisted
        // this exact truncated value. Repair it on load so existing configs recover.
        if (LEGACY_TRUNCATED_DEFAULT_SOUND_ID.equals(normalizedSoundId)) {
            normalizedSoundId = DEFAULT_SOUND_ID;
        }
        this.soundId = normalizedSoundId;
        float vol = volume;
        if (Float.isNaN(vol) || Float.isInfinite(vol)) vol = DEFAULT_VOLUME;
        this.volume = Math.max(0.0f, Math.min(1.0f, vol));

        float p = pitch;
        if (Float.isNaN(p) || Float.isInfinite(p)) p = DEFAULT_PITCH;
        this.pitch = Math.max(0.5f, Math.min(2.0f, p));

        this.fadeTicks = Math.max(0, Math.min(200, fadeTicks));
    }

    public SceneSoundSettings(String soundId, double volume, double pitch, int fadeTicks) {
        this(true, soundId, (float) volume, (float) pitch, fadeTicks);
    }

    public static SceneSoundSettings createDefault() {
        return new SceneSoundSettings(false, DEFAULT_SOUND_ID, DEFAULT_VOLUME, DEFAULT_PITCH, DEFAULT_FADE_TICKS);
    }

    public boolean isEnabled() { return enabled; }
    public String soundId() { return soundId; }
    public String getSoundId() { return soundId; }
    public float volume() { return volume; }
    public double getVolume() { return volume; }
    public float pitch() { return pitch; }
    public double getPitch() { return pitch; }
    public int fadeTicks() { return fadeTicks; }
    public int getFadeTicks() { return fadeTicks; }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("soundId", soundId);
        json.addProperty("volume", volume);
        json.addProperty("pitch", pitch);
        json.addProperty("fadeTicks", fadeTicks);
        return json;
    }

    public static SceneSoundSettings fromJson(JsonObject json) {
        if (json == null) return createDefault();
        boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
        String soundId = json.has("soundId") ? json.get("soundId").getAsString() : DEFAULT_SOUND_ID;
        float volume = json.has("volume") ? json.get("volume").getAsFloat() : DEFAULT_VOLUME;
        float pitch = json.has("pitch") ? json.get("pitch").getAsFloat() : DEFAULT_PITCH;
        int fadeTicks = json.has("fadeTicks") ? json.get("fadeTicks").getAsInt() : DEFAULT_FADE_TICKS;
        return new SceneSoundSettings(enabled, soundId, volume, pitch, fadeTicks);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneSoundSettings that)) return false;
        return enabled == that.enabled && fadeTicks == that.fadeTicks && Float.compare(volume, that.volume) == 0 && Float.compare(pitch, that.pitch) == 0 && Objects.equals(soundId, that.soundId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, soundId, volume, pitch, fadeTicks);
    }

    @Override
    public String toString() {
        return "SceneSoundSettings[enabled=" + enabled + ", soundId=" + soundId + ", volume=" + volume + ", pitch=" + pitch + ", fadeTicks=" + fadeTicks + "]";
    }
}
