package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.api.scene.SceneMotionSettings;
import com.habitrain.core.api.scene.model.SceneBackgroundConfig;
import com.habitrain.core.api.scene.model.SceneProfile;
import com.habitrain.core.api.scene.model.SceneProfileDraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the editable scene profiles and their background variants.
 * The page only coordinates selection and widgets; persistence data stays in
 * this small model so a screen rebuild cannot accidentally lose drafts.
 */
final class SceneMotionEditorState {
    private final Map<String, SceneProfileDraft> drafts = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, SceneProfileDraft>> backgroundDrafts = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, String>> backgroundNames = new LinkedHashMap<>();
    private final Map<String, SceneProfile> inheritedBaselines = new LinkedHashMap<>();

    void loadFromConfig(SceneMotionSettings settings) {
        drafts.clear();
        inheritedBaselines.clear();
        backgroundDrafts.clear();
        backgroundNames.clear();
        for (Map.Entry<String, SceneProfile> entry : settings.profiles.entrySet()) {
            drafts.put(entry.getKey(), new SceneProfileDraft(entry.getValue()));
        }
        for (Map.Entry<String, LinkedHashMap<String, SceneBackgroundConfig>> mapEntry
                : settings.backgrounds.entrySet()) {
            LinkedHashMap<String, SceneProfileDraft> mapDrafts = new LinkedHashMap<>();
            LinkedHashMap<String, String> mapNames = new LinkedHashMap<>();
            mapEntry.getValue().forEach((id, config) -> {
                mapDrafts.put(id, new SceneProfileDraft(config.getProfile()));
                mapNames.put(id, config.getName());
            });
            backgroundDrafts.put(mapEntry.getKey(), mapDrafts);
            backgroundNames.put(mapEntry.getKey(), mapNames);
        }
        for (String key : settings.configuredEditorMapKeys()) {
            ensureInheritedDraft(key, settings.getProfile(key));
        }
    }

    boolean shouldPersist(String key, SceneProfile profile) {
        return !java.util.Objects.equals(inheritedBaselines.get(key), profile);
    }

    void addMap(String mapKey, SceneProfile initialProfile) {
        if (mapKey == null || mapKey.isBlank() || initialProfile == null) return;
        inheritedBaselines.remove(mapKey);
        drafts.put(mapKey, new SceneProfileDraft(initialProfile));
    }

    boolean removeMap(String mapKey) {
        if (mapKey == null || SceneMotionSettings.isBuiltInMapKey(mapKey)) return false;
        boolean removed = drafts.remove(mapKey) != null;
        removed |= backgroundDrafts.remove(mapKey) != null;
        backgroundNames.remove(mapKey);
        inheritedBaselines.remove(mapKey);
        return removed;
    }

    boolean hasAdditionalBackgrounds(String mapKey) {
        LinkedHashMap<String, SceneProfileDraft> mapDrafts = backgroundDrafts.get(mapKey);
        return mapDrafts != null && !mapDrafts.isEmpty();
    }

    void ensureInheritedDraft(String key, SceneProfile profile) {
        if (!drafts.containsKey(key)) {
            SceneProfileDraft draft = new SceneProfileDraft(profile);
            drafts.put(key, draft);
            inheritedBaselines.put(key, draft.toProfile().copy());
        }
    }

    Map<String, SceneProfileDraft> drafts() {
        return drafts;
    }

    Map<String, LinkedHashMap<String, SceneProfileDraft>> backgroundDrafts() {
        return backgroundDrafts;
    }

    Map<String, LinkedHashMap<String, String>> backgroundNames() {
        return backgroundNames;
    }
}
