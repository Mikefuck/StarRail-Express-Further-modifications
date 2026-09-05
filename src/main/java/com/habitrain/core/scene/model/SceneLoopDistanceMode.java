package com.habitrain.core.scene.model;

/** Selects where the straight-loop travel interval comes from. */
public enum SceneLoopDistanceMode {
    /** Recalculate the seamless interval from source bounds and movement direction. */
    AUTO,
    /** Preserve and use the administrator-entered interval exactly. */
    CUSTOM;

    public static SceneLoopDistanceMode fromSerialized(String value) {
        if (value == null) return CUSTOM;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CUSTOM;
        }
    }
}
