package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MvpAnimationSettingsTest {

    @Test
    void defaultSettingsEnableAllThirtyBuiltIns() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        assertTrue(settings.enabled);
        assertTrue(settings.randomSelection);
        assertTrue(settings.avoidDuplicates);
        assertFalse(settings.showRoleItems);
        assertEquals(1.0f, settings.speed, 0.001f);
        assertEquals(30, settings.getEnabledCount());

        for (String id : MvpAnimationSettings.DEFAULT_ANIMATION_IDS) {
            assertTrue(settings.isAnimationEnabled(id), "Animation " + id + " should be enabled by default");
        }
    }

    @Test
    void jsonSerializationRoundTrip() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        settings.enabled = false;
        settings.randomSelection = false;
        settings.avoidDuplicates = false;
        settings.showRoleItems = true;
        settings.setSpeed(1.25f);
        settings.setAnimationEnabled("victory_bow", false);
        settings.setAnimationEnabled("penguin_dance", false);

        JsonObject json = settings.toJson();
        MvpAnimationSettings loaded = MvpAnimationSettings.fromJson(json);

        assertEquals(settings, loaded);
        assertFalse(loaded.enabled);
        assertFalse(loaded.randomSelection);
        assertFalse(loaded.avoidDuplicates);
        assertTrue(loaded.showRoleItems);
        assertEquals(1.25f, loaded.speed, 0.001f);
        assertFalse(loaded.isAnimationEnabled("victory_bow"));
        assertFalse(loaded.isAnimationEnabled("penguin_dance"));
        assertTrue(loaded.isAnimationEnabled("cool_sit"));
        assertEquals(28, loaded.getEnabledCount());
    }

    @Test
    void missingAnimationIdDefaultsToEnabled() {
        String jsonStr = """
                {
                  "enabled": true,
                  "randomSelection": true,
                  "avoidDuplicates": true,
                  "showRoleItems": false,
                  "speed": 1.0,
                  "animations": {
                    "victory_bow": false
                  }
                }
                """;
        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
        MvpAnimationSettings settings = MvpAnimationSettings.fromJson(json);

        assertFalse(settings.isAnimationEnabled("victory_bow"));
        assertTrue(settings.isAnimationEnabled("penguin_dance"));
        assertTrue(settings.isAnimationEnabled("champion_tpose"));
        assertTrue(settings.isAnimationEnabled("future_new_animation_11"));
    }

    @Test
    void speedIsClampedBetweenHalfAndOneAndHalf() {
        MvpAnimationSettings settings = new MvpAnimationSettings();

        settings.setSpeed(-1.0f);
        assertEquals(0.5f, settings.speed, 0.001f);

        settings.setSpeed(0.2f);
        assertEquals(0.5f, settings.speed, 0.001f);

        settings.setSpeed(3.0f);
        assertEquals(1.5f, settings.speed, 0.001f);

        settings.setSpeed(Float.NaN);
        assertEquals(1.0f, settings.speed, 0.001f);

        settings.setSpeed(0.85f);
        assertEquals(0.85f, settings.speed, 0.001f);
    }

    @Test
    void nullAndEmptyJsonHandledGracefully() {
        MvpAnimationSettings fromNull = MvpAnimationSettings.fromJson(null);
        assertNotNull(fromNull);
        assertTrue(fromNull.enabled);

        MvpAnimationSettings fromEmpty = MvpAnimationSettings.fromJson(new JsonObject());
        assertNotNull(fromEmpty);
        assertTrue(fromEmpty.enabled);
        assertEquals(30, fromEmpty.getEnabledCount());
    }

    @Test
    void fullConfigLoadWithoutMvpSectionResetsMvpSettingsToDefaults() {
        ConfigRepository repository = new ConfigRepository();
        MvpAnimationSettings customized = MvpAnimationSettings.createDefault();
        customized.enabled = false;
        customized.setAnimationEnabled("victory_bow", false);
        repository.setMvpAnimations(customized);

        new ConfigSync(null).loadFromJsonString(repository, "{}");

        assertEquals(MvpAnimationSettings.createDefault(), repository.getMvpAnimations());
    }
}
