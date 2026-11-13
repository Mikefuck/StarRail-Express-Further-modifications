package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.api.scene.SceneMotionSettings;
import com.habitrain.core.api.scene.model.SceneBackgroundConfig;
import com.habitrain.core.api.scene.model.SceneProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneMotionEditorStateTest {
    @Test
    void openingBackgroundOnlyMapDoesNotEnableOrPersistItsImplicitPrimary() {
        var settings = new SceneMotionSettings();
        var fallback = settings.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY);
        fallback.setEnabled(true);
        var additional = new SceneProfile();
        additional.setEnabled(true);
        assertTrue(settings.putBackground("alpine", "clouds", new SceneBackgroundConfig("Clouds", additional)));

        var editor = new SceneMotionEditorState();
        editor.loadFromConfig(SceneMotionSettings.fromJson(settings.toJson()));
        var primary = editor.drafts().get("alpine");
        assertFalse(primary.isEnabled());
        assertFalse(editor.shouldPersist("alpine", primary.toProfile()));
        assertTrue(editor.backgroundDrafts().get("alpine").get("clouds").isEnabled());

        primary.getOrbit().setClockwise(!primary.getOrbit().isClockwise());
        assertTrue(editor.shouldPersist("alpine", primary.toProfile()));
    }
}
