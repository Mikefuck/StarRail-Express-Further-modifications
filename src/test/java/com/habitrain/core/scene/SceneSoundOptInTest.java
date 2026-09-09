package com.habitrain.core.scene;

import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.model.SceneBackgroundConfig;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneSoundSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneSoundOptInTest {
    private SceneMotionSettings legacySettings() {
        var settings = new SceneMotionSettings();
        settings.schemaVersion = 3;
        settings.getOrCreateProfile("map").setOutsideSound(new SceneSoundSettings(true,
                SceneSoundSettings.DEFAULT_SOUND_ID, 0.7f, 1.0f, 20));
        return settings;
    }

    @Test
    void ordinaryMapDoesNotGetAnAutomaticTrainLoop() {
        var settings = new SceneMotionSettings();
        assertFalse(settings.getProfile("ordinary_murder_map").getOutsideSound().isEnabled());
        assertFalse(settings.getOrCreateProfile("another_map").getOutsideSound().isEnabled());
    }

    @Test
    void oldDisabledBackgroundLosesAccidentalDefaultLoopWithoutMutatingInput() {
        var json = legacySettings().toJson();
        var migrated = SceneMotionSettings.fromJson(json);
        assertFalse(migrated.getProfile("map").getOutsideSound().isEnabled());
        assertTrue(json.getAsJsonObject("profiles").getAsJsonObject("map")
                .getAsJsonObject("outsideSound").get("enabled").getAsBoolean());
        assertEquals(migrated, SceneMotionSettings.fromJson(migrated.toJson()));
    }

    @Test
    void enabledMainOrAdditionalBackgroundRetainsItsSound() {
        var main = legacySettings();
        main.getOrCreateProfile("map").setEnabled(true);
        assertTrue(SceneMotionSettings.fromJson(main.toJson()).getProfile("map").getOutsideSound().isEnabled());
        var additional = legacySettings();
        var profile = new SceneProfile();
        profile.setEnabled(true);
        additional.putBackground("map", "mountains", new SceneBackgroundConfig("Mountains", profile));
        assertTrue(SceneMotionSettings.fromJson(additional.toJson()).getProfile("map").getOutsideSound().isEnabled());
    }

    @Test
    void customizedSoundIsPreservedEvenWithoutVisualBackground() {
        var settings = legacySettings();
        var custom = new SceneSoundSettings(true, "test:wind", 0.5f, 1.2f, 40);
        settings.getOrCreateProfile("map").setOutsideSound(custom);
        assertEquals(custom, SceneMotionSettings.fromJson(settings.toJson()).getProfile("map").getOutsideSound());
    }

    @Test
    void userCanReenableDefaultSoundAfterOneTimeMigration() {
        var migrated = SceneMotionSettings.fromJson(legacySettings().toJson());
        migrated.getOrCreateProfile("map").setOutsideSound(new SceneSoundSettings(true,
                SceneSoundSettings.DEFAULT_SOUND_ID, 0.7f, 1.0f, 20));
        assertTrue(SceneMotionSettings.fromJson(migrated.toJson()).getProfile("map").getOutsideSound().isEnabled());
    }
}
