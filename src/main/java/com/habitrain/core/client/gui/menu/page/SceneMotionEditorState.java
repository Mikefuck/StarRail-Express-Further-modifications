package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.api.scene.SceneMotionSettings;
import com.habitrain.core.api.scene.model.SceneBackgroundConfig;
import com.habitrain.core.api.scene.model.SceneProfile;
import com.habitrain.core.api.scene.model.SceneProfileDraft;
import com.habitrain.core.config.ConfigManager;

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

    void loadFromConfig() {
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
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
        if (!drafts.containsKey(SceneMotionSettings.DEFAULT_MAP_KEY)) {
            drafts.put(SceneMotionSettings.DEFAULT_MAP_KEY,
                    new SceneProfileDraft(settings.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY)));
        }
        drafts.putIfAbsent(SceneMotionSettings.LOBBY_MAP_KEY,
                new SceneProfileDraft(settings.getProfile(SceneMotionSettings.LOBBY_MAP_KEY)));

        SceneProfile defaultProfile = settings.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY);
        for (String mapId : ConfigManager.getInstance().getModeMapVoteSettings().maps.keySet()) {
            if (mapId != null && !mapId.isBlank()) {
                drafts.computeIfAbsent(mapId,
                        key -> new SceneProfileDraft(defaultProfile.copyForNewProfile()));
            }
        }
        drafts.forEach((key, draft) -> {
            if (!settings.profiles.containsKey(key) && !SceneMotionSettings.DEFAULT_MAP_KEY.equals(key)) {
                inheritedBaselines.put(key, draft.toProfile());
            }
        });
    }

    boolean shouldPersist(String key, SceneProfile profile) {
        return !java.util.Objects.equals(inheritedBaselines.get(key), profile);
    }

    void ensureInheritedDraft(String key, SceneProfile profile) {
        if (!drafts.containsKey(key)) {
            SceneProfileDraft draft = new SceneProfileDraft(profile);
            drafts.put(key, draft);
            inheritedBaselines.put(key, draft.toProfile());
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
