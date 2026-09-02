package com.habitrain.core.scene;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRotation;
import com.habitrain.core.scene.model.SceneSoundSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneProfileConfigTest {

    @Test
    public void testProfileSerializationRoundtrip() {
        SceneProfile profile = new SceneProfile();
        profile.setEnabled(true);
        profile.setSourceBounds(new SceneBounds(100, 50, 200, 150, 70, 250));
        profile.setSpeedBlocksPerSecond(25.5);
        profile.setDirection(0.0, 0.0, -1.0);
        profile.setDisplayOrigin(10.0, 64.0, 20.0);
        profile.setPivotLocal(5.0, 2.0, 5.0);
        profile.setRotationDegrees(new SceneRotation(90.0, 0.0, 0.0));
        profile.setPhaseOffsetBlocks(12.5);

        JsonObject json = profile.toJson();
        assertNotNull(json);

        SceneProfile parsed = SceneProfile.fromJson(json);
        assertTrue(parsed.isEnabled());
        assertEquals(25.5, parsed.getSpeedBlocksPerSecond(), 0.001);
        assertEquals(0.0, parsed.getDirection()[0], 0.001);
        assertEquals(0.0, parsed.getDirection()[1], 0.001);
        assertEquals(-1.0, parsed.getDirection()[2], 0.001);
        assertEquals(10.0, parsed.getDisplayOrigin()[0], 0.001);
        assertEquals(64.0, parsed.getDisplayOrigin()[1], 0.001);
        assertEquals(20.0, parsed.getDisplayOrigin()[2], 0.001);
        assertEquals(5.0, parsed.getPivotLocal()[0], 0.001);
        assertEquals(90.0, parsed.getRotationDegrees().yawDegrees(), 0.001);
        assertEquals(12.5, parsed.getPhaseOffsetBlocks(), 0.001);
        assertEquals(100, parsed.getSourceBounds().minX());
        assertEquals(150, parsed.getSourceBounds().maxX());
    }

    @Test
    public void testGlobalSceneSettingsRoundtrip() {
        SceneMotionSettings settings = new SceneMotionSettings();
        SceneProfile p1 = new SceneProfile();
        p1.setSpeedBlocksPerSecond(15.0);
        settings.profiles.put("train_snowy", p1);

        JsonObject json = settings.toJson();
        assertNotNull(json);

        SceneMotionSettings loaded = SceneMotionSettings.fromJson(json);
        assertNotNull(loaded.profiles.get("train_snowy"));
        assertEquals(15.0, loaded.profiles.get("train_snowy").getSpeedBlocksPerSecond(), 0.001);
    }

    @Test
    public void testLegacyTruncatedDefaultSoundIdIsRepaired() {
        SceneSoundSettings settings = new SceneSoundSettings(
                true, "habitrain_core:moving_scene_outs", 0.7f, 1.0f, 20);

        assertEquals(SceneSoundSettings.DEFAULT_SOUND_ID, settings.getSoundId());
    }
}
