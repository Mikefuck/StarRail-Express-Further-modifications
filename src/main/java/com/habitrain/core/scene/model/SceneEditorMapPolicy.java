package com.habitrain.core.scene.model;

import com.habitrain.core.config.SceneMotionSettings;

import java.util.Collection;

/** Chooses the editor's initial map without conflating it with runtime or selection state. */
public final class SceneEditorMapPolicy {
    private SceneEditorMapPolicy() {}

    public static String resolve(String rememberedEditorMapKey, String activeRuntimeMapKey,
                                 String contextMapKey, Collection<String> configuredMapKeys) {
        String editor = normalize(rememberedEditorMapKey);
        if (isConfigured(editor, configuredMapKeys)) return editor;

        String runtime = normalize(activeRuntimeMapKey);
        if (isConfigured(runtime, configuredMapKeys)) return runtime;

        String context = normalize(contextMapKey);
        if (isConfigured(context, configuredMapKeys)) return context;

        return SceneMotionSettings.DEFAULT_MAP_KEY;
    }

    public static boolean selectionBelongsToEditor(String editorMapKey, String selectionMapKey) {
        String editor = normalize(editorMapKey);
        String selection = normalize(selectionMapKey);
        return !editor.isEmpty() && editor.equals(selection);
    }

    private static boolean isConfigured(String mapKey, Collection<String> configuredMapKeys) {
        return !mapKey.isEmpty() && (SceneMotionSettings.DEFAULT_MAP_KEY.equals(mapKey)
                || configuredMapKeys != null && configuredMapKeys.contains(mapKey));
    }

    private static String normalize(String mapKey) {
        return mapKey == null ? "" : mapKey.trim();
    }
}
