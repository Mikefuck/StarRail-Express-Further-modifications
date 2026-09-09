package com.habitrain.core.scene;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.model.SceneLoopDistanceMode;
import com.habitrain.core.scene.model.SceneLoopSettings;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRotation;
import com.habitrain.core.scene.model.SceneSoundSettings;
import com.habitrain.core.scene.model.SceneBackgroundConfig;
import com.habitrain.core.scene.model.SceneBackgroundKey;
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

    @Test
    public void schemaOneDistanceMigratesToCustomWithoutVisualChange() {
        JsonObject legacy = JsonParser.parseString("""
                {"schemaVersion":1,"profiles":{"legacy_map":{"enabled":true,
                  "sourceBounds":{"min":[0,0,0],"maxExclusive":[40,10,20]},
                  "direction":[1,0,0],
                  "loop":{"enabled":true,"distanceBlocks":80.0,"copies":2}}}}
                """).getAsJsonObject();

        SceneMotionSettings migrated = SceneMotionSettings.fromJson(legacy);
        SceneProfile profile = migrated.profiles.get("legacy_map");

        assertEquals(SceneMotionSettings.CURRENT_SCHEMA_VERSION, migrated.schemaVersion);
        assertNotNull(profile);
        assertEquals(SceneLoopDistanceMode.CUSTOM, profile.getLoop().getDistanceMode());
        assertEquals(80.0, profile.getLoop().getDistanceBlocks(), 0.001);
        assertEquals(80.0, com.habitrain.core.scene.model.SceneMotionMath.effectiveLoopDistance(
                profile.getSourceBounds(), profile.getDirection(), profile.getLoop()), 0.001);

        JsonObject saved = migrated.toJson();
        assertEquals("CUSTOM", saved.getAsJsonObject("profiles")
                .getAsJsonObject("legacy_map").getAsJsonObject("loop")
                .get("distanceMode").getAsString());
        assertEquals(migrated, SceneMotionSettings.fromJson(saved));
    }

    @Test
    public void newProfilesUseAutoEvenWhenTemplateWasMigratedCustom() {
        SceneMotionSettings settings = SceneMotionSettings.fromJson(JsonParser.parseString("""
                {"schemaVersion":1,"profiles":{"__default__":{
                  "loop":{"enabled":true,"distanceBlocks":73.0,"copies":2}}}}
                """).getAsJsonObject());

        assertEquals(SceneLoopDistanceMode.CUSTOM,
                settings.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY).getLoop().getDistanceMode());
        SceneProfile created = settings.getOrCreateProfile("new_map");
        assertEquals(SceneLoopDistanceMode.AUTO, created.getLoop().getDistanceMode());
        assertEquals(73.0, created.getLoop().getDistanceBlocks(), 0.001);
    }

    @Test
    public void loopModeSurvivesProfileCopyDraftAndJsonRoundtrip() {
        SceneProfile profile = new SceneProfile();
        profile.setLoop(new SceneLoopSettings(true, SceneLoopDistanceMode.CUSTOM, 91.5, 2));

        SceneProfile copy = profile.copy();
        SceneProfile fromDraft = new com.habitrain.core.scene.model.SceneProfileDraft(profile).toProfile();
        SceneProfile fromJson = SceneProfile.fromJson(profile.toJson());

        assertEquals(profile, copy);
        assertEquals(profile.hashCode(), copy.hashCode());
        assertEquals(profile, fromDraft);
        assertEquals(profile, fromJson);
        assertEquals(SceneLoopDistanceMode.CUSTOM, fromJson.getLoop().getDistanceMode());
    }

    @Test
    public void orbitSettingsSurviveProfileCopyDraftAndJsonRoundtrip() {
        SceneProfile profile = new SceneProfile();
        profile.setMotionMode(com.habitrain.core.scene.model.SceneMotionMode.ORBIT);
        var orbit = profile.getOrbit();
        orbit.setCenterMode(com.habitrain.core.scene.model.SceneOrbitCenterMode.WORLD_BLOCK);
        orbit.setCenterWorld(120.5, 64.5, -30.5);
        orbit.setAxis(com.habitrain.core.scene.model.SceneOrbitAxis.Y);
        orbit.setStartAngleDegrees(45.0);
        orbit.setSweepDegrees(180.0);
        orbit.setClockwise(false);
        orbit.setAngularSpeedDegreesPerSecond(45.0);
        orbit.setVerticalBobAmplitudeBlocks(1.5);
        orbit.setRadialBobAmplitudeBlocks(0.75);
        orbit.setBobCyclesPerSecond(0.5);
        orbit.setInstanceCount(4);
        orbit.setInstanceSpreadDegrees(180.0);
        orbit.setRotateModelWithOrbit(true);

        SceneProfile copy = profile.copy();
        SceneProfile fromDraft = new com.habitrain.core.scene.model.SceneProfileDraft(profile).toProfile();
        SceneProfile fromJson = SceneProfile.fromJson(profile.toJson());

        assertEquals(profile, copy);
        assertEquals(profile.hashCode(), copy.hashCode());
        assertEquals(profile, fromDraft);
        assertEquals(profile, fromJson);
        assertEquals(com.habitrain.core.scene.model.SceneMotionMode.ORBIT, fromJson.getMotionMode());
        assertEquals(180.0, fromJson.getOrbit().getSweepDegrees(), 0.001);
        assertEquals(4, fromJson.getOrbit().getInstanceCount());
        assertFalse(fromJson.getOrbit().isClockwise());
    }

    @Test
    public void schemaOneMigrationIncludesLinearAndDefaultOrbit() {
        JsonObject legacy = JsonParser.parseString("""
                {"schemaVersion":1,"profiles":{"legacy_map":{"enabled":true,
                  "sourceBounds":{"min":[0,0,0],"maxExclusive":[40,10,20]},
                  "direction":[1,0,0],
                  "loop":{"enabled":true,"distanceBlocks":80.0,"copies":2}}}}
                """).getAsJsonObject();

        SceneMotionSettings migrated = SceneMotionSettings.fromJson(legacy);
        SceneProfile profile = migrated.profiles.get("legacy_map");
        assertNotNull(profile);
        assertEquals(com.habitrain.core.scene.model.SceneMotionMode.LINEAR, profile.getMotionMode());
        assertNotNull(profile.getOrbit());
        assertEquals(com.habitrain.core.scene.model.SceneOrbitCenterMode.WORLD_BLOCK, profile.getOrbit().getCenterMode());
    }

    @Test
    public void namedBackgroundsRoundTripAndDefaultOwnsSharedEffects() {
        SceneMotionSettings settings = new SceneMotionSettings();
        SceneProfile shared = settings.getOrCreateProfile("map_a");
        shared.setOutsideSound(new SceneSoundSettings(true, SceneSoundSettings.DEFAULT_SOUND_ID, 0.7f, 1.0f, 20));

        SceneProfile mountains = new SceneProfile();
        mountains.setEnabled(true);
        mountains.setSpeedBlocksPerSecond(12.0);
        assertTrue(settings.putBackground("map_a", "mountains",
                new SceneBackgroundConfig("窗外群山", mountains)));

        SceneMotionSettings loaded = SceneMotionSettings.fromJson(settings.toJson());
        assertEquals(SceneMotionSettings.CURRENT_SCHEMA_VERSION, loaded.schemaVersion);
        assertEquals(2, loaded.getResolvedBackgrounds("map_a").size());
        assertEquals("窗外群山", loaded.getBackgroundName("map_a", "mountains"));
        assertEquals(12.0, loaded.getBackgroundProfile("map_a", "mountains")
                .getSpeedBlocksPerSecond(), 0.001);
        assertTrue(loaded.getProfile("map_a").getOutsideSound().isEnabled());
    }

    @Test
    public void aMapIsLimitedToFiveBackgroundsIncludingDefault() {
        SceneMotionSettings settings = new SceneMotionSettings();
        for (int i = 1; i <= 4; i++) {
            assertTrue(settings.putBackground("map_a", "background_" + i,
                    new SceneBackgroundConfig("背景 " + i, new SceneProfile())));
        }
        assertFalse(settings.putBackground("map_a", "background_5",
                new SceneBackgroundConfig("超额背景", new SceneProfile())));
        assertEquals(5, settings.getResolvedBackgrounds("map_a").size());
        assertFalse(settings.removeBackground("map_a", SceneBackgroundKey.DEFAULT_ID));
        assertTrue(settings.removeBackground("map_a", "background_1"));
    }

    @Test
    public void legacySchemaTwoGetsEmptyBackgroundCollectionWithoutChangingProfile() {
        JsonObject schemaTwo = JsonParser.parseString("""
                {"schemaVersion":2,"profiles":{"map_a":{"enabled":true,
                  "speedBlocksPerSecond":23.0,"loop":{"enabled":true,
                  "distanceMode":"CUSTOM","distanceBlocks":80.0,"copies":2}}}}
                """).getAsJsonObject();
        SceneMotionSettings loaded = SceneMotionSettings.fromJson(schemaTwo);
        assertEquals(SceneMotionSettings.CURRENT_SCHEMA_VERSION, loaded.schemaVersion);
        assertTrue(loaded.backgrounds.isEmpty());
        assertEquals(23.0, loaded.getProfile("map_a").getSpeedBlocksPerSecond(), 0.001);
    }

    @Test
    public void additionalAssetKeysRoundTripWithoutChangingMapKeys() {
        String key = SceneBackgroundKey.assetKey("habitrain:night_train", "mountains");
        assertEquals("habitrain:night_train", SceneBackgroundKey.mapKeyFromAssetKey(key));
        assertEquals("mountains", SceneBackgroundKey.backgroundIdFromAssetKey(key));
        assertEquals("habitrain:night_train",
                SceneBackgroundKey.assetKey("habitrain:night_train", SceneBackgroundKey.DEFAULT_ID));
    }
}
