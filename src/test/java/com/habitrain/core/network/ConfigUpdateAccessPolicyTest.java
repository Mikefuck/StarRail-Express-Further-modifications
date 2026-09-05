package com.habitrain.core.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ConfigUpdateAccessPolicyTest {

    private static final String COMPLETE_CONFIG = """
            {
              "global": {"knifeDurabilityEnabled": true},
              "tasks": {"example:task": {"enabled": false}},
              "minigames": {"globalEnabled": false},
              "modeMapVote": {"enabled": true},
              "environment": {"lobby": {"weather": "rain"}}
            }
            """;

    @Test
    void backpackTaskScopeKeepsOnlyTaskSettings() {
        JsonObject filtered = parse(ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.BACKPACK_TASKS, COMPLETE_CONFIG));

        assertTrue(filtered.has("tasks"));
        assertFalse(filtered.has("global"));
        assertFalse(filtered.has("modeMapVote"));
        assertFalse(filtered.has("environment"));
    }

    @Test
    void backpackMapVoteScopeKeepsOnlyMapRotationAndVoteSettings() {
        JsonObject filtered = parse(ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.BACKPACK_MAP_VOTE, COMPLETE_CONFIG));

        assertTrue(filtered.has("modeMapVote"));
        assertFalse(filtered.has("global"));
        assertFalse(filtered.has("tasks"));
        assertFalse(filtered.has("minigames"));
        assertFalse(filtered.has("environment"));
    }

    @Test
    void fullModMenuRequiresOp2AndDedicatedServerGrant() {
        assertFalse(ConfigUpdateAccessPolicy.isAllowed(
                ConfigUpdateScope.FULL_MOD_MENU, false, true, true, true));
        assertFalse(ConfigUpdateAccessPolicy.isAllowed(
                ConfigUpdateScope.FULL_MOD_MENU, true, true, true, false));
        assertTrue(ConfigUpdateAccessPolicy.isAllowed(
                ConfigUpdateScope.FULL_MOD_MENU, true, true, true, true));
    }

    @Test
    void backpackScopesRequireOnlyOp2EvenWhenModMenuGrantIsMissing() {
        assertTrue(ConfigUpdateAccessPolicy.isAllowed(
                ConfigUpdateScope.BACKPACK_TASKS, true, true, true, false));
        assertTrue(ConfigUpdateAccessPolicy.isAllowed(
                ConfigUpdateScope.BACKPACK_MAP_VOTE, true, true, true, false));
        assertFalse(ConfigUpdateAccessPolicy.isAllowed(
                ConfigUpdateScope.BACKPACK_MAP_VOTE, false, true, true, true));
    }

    @Test
    void mapPreviewUploadRequiresOnlyOp2ForConfiguredMap() {
        assertEquals(ConfigUpdateAccessPolicy.MapPreviewUploadAccess.ALLOWED,
                ConfigUpdateAccessPolicy.mapPreviewUploadAccess(
                        true, "map_a", java.util.Set.of("map_a")));
        assertEquals(ConfigUpdateAccessPolicy.MapPreviewUploadAccess.OP2_REQUIRED,
                ConfigUpdateAccessPolicy.mapPreviewUploadAccess(
                        false, "map_a", java.util.Set.of("map_a")));
    }

    @Test
    void mapPreviewUploadRejectsUnknownOrBlankTarget() {
        assertEquals(ConfigUpdateAccessPolicy.MapPreviewUploadAccess.MAP_NOT_CONFIGURED,
                ConfigUpdateAccessPolicy.mapPreviewUploadAccess(
                        true, "map_b", java.util.Set.of("map_a")));
        assertEquals(ConfigUpdateAccessPolicy.MapPreviewUploadAccess.MAP_NOT_CONFIGURED,
                ConfigUpdateAccessPolicy.mapPreviewUploadAccess(
                        true, "", java.util.Set.of("map_a")));
    }

    @Test
    void payloadInspectionRequiresOperatorBeforeScopeJsonIsParsed() {
        assertFalse(ConfigUpdateAccessPolicy.mayInspectPayload(false));
        assertTrue(ConfigUpdateAccessPolicy.mayInspectPayload(true));
    }

    @Test
    void adminSceneToolCanOnlyReplaceCurrentMapProfile() {
        JsonObject authoritative = parse("""
                {"enabled":true,"profiles":{"map_a":{"enabled":false},"map_b":{"enabled":false}}}
                """);
        String submitted = """
                {"sceneMotion":{"enabled":false,"profiles":{
                  "map_a":{"enabled":true,"dimension":"minecraft:overworld","direction":[-1,0,0],
                    "speedBlocksPerSecond":18,"loop":{"distanceBlocks":512,"copies":2},
                    "render":{"maxDistanceBlocks":192},
                    "outsideSound":{"soundId":"habitrain_core:moving_scene_outside","volume":0.7,"pitch":1,"fadeTicks":20},
                    "shake":{"translationAmplitude":0.02,"rotationAmplitudeDegrees":0.1,"frequencyHz":1.8}},
                  "map_b":{"enabled":true}
                }}}
                """;
        JsonObject filtered = parse(ConfigUpdateAccessPolicy.filterAdminSceneProfile(
                submitted, "map_a", authoritative));
        JsonObject scene = filtered.getAsJsonObject("sceneMotion");
        assertTrue(scene.get("enabled").getAsBoolean());
        assertTrue(scene.getAsJsonObject("profiles").getAsJsonObject("map_a").get("enabled").getAsBoolean());
        assertFalse(scene.getAsJsonObject("profiles").getAsJsonObject("map_b").get("enabled").getAsBoolean());
    }

    @Test
    void adminSceneToolCanReplaceOnlyCurrentMapsNamedBackgroundList() {
        JsonObject authoritative = parse("""
                {"schemaVersion":3,"profiles":{"map_a":{},"map_b":{}},"backgrounds":{
                  "map_a":{"old":{"name":"旧背景","profile":{}}},
                  "map_b":{"kept":{"name":"保留背景","profile":{}}}}}
                """);
        String submitted = """
                {"sceneMotion":{"schemaVersion":3,"profiles":{"map_a":{}},"backgrounds":{
                  "map_a":{"new":{"name":"新背景","profile":{"enabled":true}}},
                  "map_b":{}}}}
                """;
        JsonObject filtered = parse(ConfigUpdateAccessPolicy.filterAdminSceneProfile(
                submitted, "map_a", authoritative)).getAsJsonObject("sceneMotion");
        assertTrue(filtered.getAsJsonObject("backgrounds").getAsJsonObject("map_a").has("new"));
        assertFalse(filtered.getAsJsonObject("backgrounds").getAsJsonObject("map_a").has("old"));
        assertTrue(filtered.getAsJsonObject("backgrounds").getAsJsonObject("map_b").has("kept"));
    }

    @Test
    void fullMenuRejectsMoreThanFiveBackgroundsIncludingDefault() {
        String json = """
                {"sceneMotion":{"schemaVersion":3,"profiles":{"map":{}},"backgrounds":{"map":{
                  "a":{"name":"A","profile":{}},"b":{"name":"B","profile":{}},
                  "c":{"name":"C","profile":{}},"d":{"name":"D","profile":{}},
                  "e":{"name":"E","profile":{}}}}}}
                """;
        assertThrows(IllegalArgumentException.class, () ->
                ConfigUpdateAccessPolicy.filterConfigJson(ConfigUpdateScope.FULL_MOD_MENU, json));
    }

    @Test
    void adminSceneToolRejectsZeroDirectionAndWrongLoopCopies() {
        JsonObject authoritative = parse("{\"profiles\":{}}");
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterAdminSceneProfile(
                "{\"sceneMotion\":{\"profiles\":{\"map\":{\"direction\":[0,0,0]}}}}", "map", authoritative));
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterAdminSceneProfile(
                "{\"sceneMotion\":{\"profiles\":{\"map\":{\"direction\":[1,0,0],\"loop\":{\"copies\":3}}}}}",
                "map", authoritative));
    }

    @Test
    void fullMenuAlsoRejectsInvalidSceneValuesInsteadOfClampingThem() {
        String invalid = """
                {"sceneMotion":{"schemaVersion":1,"profiles":{"map":{
                  "direction":[1,0,0],"speedBlocksPerSecond":999,
                  "loop":{"distanceBlocks":512,"copies":2}
                }}}}
                """;
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, invalid));
    }

    @Test
    void sceneLoopDistanceModeAcceptsSchemaTwoAndRejectsUnknownValues() {
        String valid = """
                {"sceneMotion":{"schemaVersion":2,"profiles":{"map":{
                  "direction":[1,0,0],"loop":{"distanceMode":"AUTO","distanceBlocks":80,"copies":2}
                }}}}
                """;
        assertDoesNotThrow(() -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, valid));

        String invalid = valid.replace("AUTO", "MAGIC");
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, invalid));
    }

    @Test
    void sceneBoundsAcceptThirtyTwoChunksButRejectOneExtraBlock() {
        JsonObject authoritative = parse("{\"profiles\":{}}");
        String accepted = "{\"sceneMotion\":{\"profiles\":{\"map\":{"
                + "\"sourceBounds\":{\"min\":[0,0,0],\"maxExclusive\":[512,16,16]}}}}}";
        assertDoesNotThrow(() -> ConfigUpdateAccessPolicy.filterAdminSceneProfile(
                accepted, "map", authoritative));

        String rejected = "{\"sceneMotion\":{\"profiles\":{\"map\":{"
                + "\"sourceBounds\":{\"min\":[0,0,0],\"maxExclusive\":[513,16,16]}}}}}";
        assertThrows(IllegalArgumentException.class, () ->
                ConfigUpdateAccessPolicy.filterAdminSceneProfile(rejected, "map", authoritative));
    }

    @Test
    void adminSceneTargetMustBeCurrentDefaultOrConfigured() {
        assertEquals("map_b", ConfigUpdateAccessPolicy.resolveAdminSceneTargetMap(
                "map_b", "__default__", java.util.Set.of("map_a", "map_b")));
        assertEquals("__default__", ConfigUpdateAccessPolicy.resolveAdminSceneTargetMap(
                "", "__default__", java.util.Set.of("map_a")));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigUpdateAccessPolicy.resolveAdminSceneTargetMap(
                        "forged_map", "__default__", java.util.Set.of("map_a")));
    }

    @Test
    void sceneMapMetadataRoundTripsWithoutChangingWireFormat() {
        String json = ConfigUpdateScope.attachToConfigJson(
                "{\"sceneMotion\":{}}", ConfigUpdateScope.ADMIN_SCENE_TOOL, "map_b");
        assertEquals(ConfigUpdateScope.ADMIN_SCENE_TOOL, ConfigUpdateScope.fromConfigJson(json));
        assertEquals("map_b", ConfigUpdateScope.sceneMapKeyFromConfigJson(json));
    }

    @Test
    void sceneOrbitSettingsAcceptValidAndRejectOutOfRangeOrMalformedValues() {
        String valid = """
                {"sceneMotion":{"schemaVersion":2,"profiles":{"map":{
                  "motionMode":"ORBIT",
                  "orbit":{
                    "centerMode":"WORLD_BLOCK",
                    "centerWorld":[120.5, 64.5, -30.5],
                    "axis":"Y",
                    "startAngleDegrees":0.0,
                    "sweepDegrees":180.0,
                    "clockwise":true,
                    "angularSpeedDegreesPerSecond":30.0,
                    "verticalBobAmplitudeBlocks":0.5,
                    "radialBobAmplitudeBlocks":0.25,
                    "bobCyclesPerSecond":0.2,
                    "instanceCount":4,
                    "instanceSpreadDegrees":360.0,
                    "rotateModelWithOrbit":true
                  }
                }}}}
                """;
        assertDoesNotThrow(() -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, valid));

        // Invalid motionMode
        String badMotionMode = valid.replace("\"ORBIT\"", "\"WARP\"");
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, badMotionMode));

        // Invalid axis
        String badAxis = valid.replace("\"axis\":\"Y\"", "\"axis\":\"W\"");
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, badAxis));

        // Invalid instanceCount (e.g. 0 or 17)
        String badInstances = valid.replace("\"instanceCount\":4", "\"instanceCount\":0");
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, badInstances));

        // Non-finite value in centerWorld
        String badCoord = valid.replace("120.5", "1e40");
        assertThrows(IllegalArgumentException.class, () -> ConfigUpdateAccessPolicy.filterConfigJson(
                ConfigUpdateScope.FULL_MOD_MENU, badCoord));
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
