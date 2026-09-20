package com.habitrain.core.scene;

import com.google.gson.JsonObject;
import com.habitrain.core.api.scene.SceneMotionSettings;
import com.habitrain.core.network.ConfigUpdateAccessPolicy;
import com.habitrain.core.api.scene.model.*;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SceneLobbyPolicyTest {
    private static final String LOBBY = SceneMotionSettings.LOBBY_MAP_KEY;
    private static final String OVERWORLD = SceneProfile.DEFAULT_DIMENSION;

    @Test
    void legacyDefaultCannotEnableOrPopulateLobby() {
        SceneMotionSettings old = SceneMotionSettings.createDefault();
        var fallback = old.getOrCreateProfile(SceneMotionSettings.DEFAULT_MAP_KEY);
        fallback.setEnabled(true);
        fallback.setSpeedBlocksPerSecond(37);
        fallback.setSourceBounds(new SceneBounds(1, 2, 3, 10, 12, 13));
        SceneMotionSettings loaded = SceneMotionSettings.fromJson(old.toJson());
        assertTrue(SceneLobbyPolicy.resolve(loaded, OVERWORLD, false).isEmpty());
        assertFalse(loaded.getProfile(LOBBY).isEnabled());
        assertFalse(loaded.getProfile(LOBBY).getShake().isEnabled());
        assertFalse(loaded.getProfile(LOBBY).getOutsideSound().isEnabled());
        assertTrue(loaded.getProfile(LOBBY).getSourceBounds().isEmpty());
        assertEquals(old.toJson(), loaded.toJson());
        var lobby = loaded.getOrCreateProfile(LOBBY);
        assertFalse(lobby.isEnabled());
        assertEquals(SceneProfile.DEFAULT_SPEED, lobby.getSpeedBlocksPerSecond());
        assertEquals(fallback, loaded.getProfile("unconfigured_match_map"));
    }

    @Test
    void lobbyRoundtripPreservesMatchConfigurationAndSeparateAssets() {
        var settings = SceneMotionSettings.createDefault();
        settings.getOrCreateProfile("snow").setSpeedBlocksPerSecond(24);
        JsonObject originalProfiles = settings.toJson().getAsJsonObject("profiles").deepCopy();
        settings.getOrCreateProfile(LOBBY).setEnabled(true);
        settings.getOrCreateProfile(LOBBY).setSpeedBlocksPerSecond(7);
        var extra = SceneProfile.createDefault();
        extra.setEnabled(true);
        settings.putBackground(LOBBY, "clouds", new SceneBackgroundConfig("Clouds", extra));
        var loaded = SceneMotionSettings.fromJson(settings.toJson());
        assertEquals(settings, loaded);
        assertEquals(7, loaded.getProfile(LOBBY).getSpeedBlocksPerSecond());
        for (var entry : originalProfiles.entrySet()) {
            assertEquals(entry.getValue(), loaded.toJson().getAsJsonObject("profiles").get(entry.getKey()));
        }
        String lobbyAsset = SceneBackgroundKey.assetKey(LOBBY, "clouds");
        assertEquals(LOBBY, SceneBackgroundKey.mapKeyFromAssetKey(lobbyAsset));
        assertNotEquals(SceneBackgroundKey.assetKey("snow", "clouds"), lobbyAsset);
        assertTrue(loaded.removeBackground(LOBBY, "clouds"));
        assertEquals(1, loaded.getResolvedBackgrounds(LOBBY).size());
        assertEquals(24, loaded.getProfile("snow").getSpeedBlocksPerSecond());
    }

    @Test
    void lobbyStopsForMatchAndReturnsAfterActualEnd() {
        var settings = SceneMotionSettings.createDefault();
        settings.getOrCreateProfile(LOBBY).setEnabled(true);
        assertFalse(SceneLobbyPolicy.resolve(settings, OVERWORLD, false).isEmpty());
        // The SRE resolver reports INITIATING, ACTIVE and STOPPING as matchActive.
        assertTrue(SceneLobbyPolicy.resolve(settings, OVERWORLD, true).isEmpty());
        assertFalse(SceneLobbyPolicy.resolve(settings, OVERWORLD, false).isEmpty());
        settings.getOrCreateProfile(LOBBY).setEnabled(false);
        assertTrue(SceneLobbyPolicy.resolve(settings, OVERWORLD, false).isEmpty());
    }

    @Test
    void lobbyRespectsGlobalSwitchAndConfiguredDimension() {
        var settings = SceneMotionSettings.createDefault();
        var lobby = settings.getOrCreateProfile(LOBBY);
        lobby.setEnabled(true);
        lobby.setDimension("minecraft:the_nether");
        assertTrue(SceneLobbyPolicy.resolve(settings, OVERWORLD, false).isEmpty());
        assertFalse(SceneLobbyPolicy.resolve(settings, "minecraft:the_nether", false).isEmpty());
        settings.enabled = false;
        assertTrue(SceneLobbyPolicy.resolve(settings, "minecraft:the_nether", false).isEmpty());
    }

    @Test
    void enabledAdditionalBackgroundWorksWithoutEnabledPrimary() {
        var settings = SceneMotionSettings.createDefault();
        var extra = SceneProfile.createDefault();
        extra.setEnabled(true);
        settings.putBackground(LOBBY, "clouds", new SceneBackgroundConfig("Clouds", extra));
        assertEquals(2, SceneLobbyPolicy.resolve(settings, OVERWORLD, false).size());
        assertTrue(SceneLobbyPolicy.resolve(settings, OVERWORLD, true).isEmpty());
    }

    @Test
    void lobbyRetainsExistingSoundOnlyConfigurationSemantics() {
        var settings = SceneMotionSettings.createDefault();
        settings.getOrCreateProfile(LOBBY).setOutsideSound(new SceneSoundSettings(
                true, SceneSoundSettings.DEFAULT_SOUND_ID, 0.5f, 1.0f, 20));
        assertFalse(SceneLobbyPolicy.resolve(settings, OVERWORLD, false).isEmpty());
        assertTrue(SceneLobbyPolicy.resolve(settings, OVERWORLD, true).isEmpty());
    }

    @Test
    void administratorCanSelectLobbyBeforeItHasBeenSaved() {
        assertEquals(LOBBY, ConfigUpdateAccessPolicy.resolveAdminSceneTargetMap(LOBBY, "snow", Set.of("snow")));
        assertEquals(LOBBY, SceneEditorMapPolicy.resolve(LOBBY, "", "snow", Set.of("snow")));
        assertEquals(LOBBY, SceneEditorMapPolicy.resolve("", LOBBY, "snow", Set.of("snow")));
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.resolveAdminSceneTargetMap(
                "arbitrary", "snow", Set.of("snow")));
    }

    @Test
    void lobbyToolSaveCannotOverwriteOtherMapsOrGlobalSettings() {
        var server = SceneMotionSettings.createDefault();
        server.getOrCreateProfile("snow").setSpeedBlocksPerSecond(24);
        var submitted = SceneMotionSettings.fromJson(server.toJson());
        submitted.enabled = false;
        submitted.getOrCreateProfile("snow").setSpeedBlocksPerSecond(1);
        submitted.getOrCreateProfile(LOBBY).setEnabled(true);
        JsonObject request = new JsonObject();
        request.add("sceneMotion", submitted.toJson());
        var safe = com.google.gson.JsonParser.parseString(ConfigUpdateAccessPolicy.filterAdminSceneProfile(
                request.toString(), LOBBY, server.toJson())).getAsJsonObject().getAsJsonObject("sceneMotion");
        var applied = SceneMotionSettings.fromJson(safe);
        assertTrue(applied.enabled);
        assertTrue(applied.getProfile(LOBBY).isEnabled());
        assertEquals(server.getProfile("snow"), applied.getProfile("snow"));
        assertEquals(server.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY), applied.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY));
    }
}
