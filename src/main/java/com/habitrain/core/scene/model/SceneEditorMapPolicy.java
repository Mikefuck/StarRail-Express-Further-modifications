package com.habitrain.core.scene.model;

import com.habitrain.core.config.SceneMotionSettings;

import java.util.Collection;

/** Chooses the editor's initial map without trusting stale or unknown client map keys. */
public final class SceneEditorMapPolicy {
    private SceneEditorMapPolicy() {}

    public static String resolve(String serverMapKey, String activeRuntimeMapKey,
                                 String hudMapKey, Collection<String> configuredMapKeys) {
        String server = normalize(serverMapKey);
        if (!SceneMotionSettings.DEFAULT_MAP_KEY.equals(server)) return server;

        String runtime = normalize(activeRuntimeMapKey);
        if (isConfigured(runtime, configuredMapKeys)) return runtime;

        String hud = normalize(hudMapKey);
        if (isConfigured(hud, configuredMapKeys)) return hud;

        return SceneMotionSettings.DEFAULT_MAP_KEY;
    }

    private static boolean isConfigured(String mapKey, Collection<String> configuredMapKeys) {
        return !SceneMotionSettings.DEFAULT_MAP_KEY.equals(mapKey)
                && configuredMapKeys != null && configuredMapKeys.contains(mapKey);
    }

    private static String normalize(String mapKey) {
        return mapKey == null || mapKey.isBlank() ? SceneMotionSettings.DEFAULT_MAP_KEY : mapKey.trim();
    }
}
