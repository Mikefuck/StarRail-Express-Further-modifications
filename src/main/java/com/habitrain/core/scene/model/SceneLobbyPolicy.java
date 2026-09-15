package com.habitrain.core.scene.model;

import com.habitrain.core.config.SceneMotionSettings;

import java.util.List;

/** Lobby activation uses its own profile, never the match map or its fallback. */
public final class SceneLobbyPolicy {
    private SceneLobbyPolicy() {}

    public static List<SceneMotionSettings.ResolvedBackground> resolve(
            SceneMotionSettings settings, String dimension, boolean matchActive) {
        if (settings == null || !settings.enabled || matchActive) return List.of();
        SceneProfile profile = settings.getProfile(SceneMotionSettings.LOBBY_MAP_KEY);
        if (!profile.getDimension().equals(dimension)) return List.of();
        var backgrounds = settings.getResolvedBackgrounds(SceneMotionSettings.LOBBY_MAP_KEY);
        boolean enabled = backgrounds.stream().anyMatch(background -> background.profile().isEnabled());
        return enabled || profile.getOutsideSound().isEnabled() || profile.getShake().isEnabled()
                ? backgrounds : List.of();
    }
}
