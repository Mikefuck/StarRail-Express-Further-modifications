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

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
