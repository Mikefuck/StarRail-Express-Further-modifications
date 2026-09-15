package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemovedModeConfigMigrationTest {
    private static JsonObject legacy() {
        return JsonParser.parseString("""
                {
                  "global":{"tempPowerPrice":150,"blackoutGlobalCooldownSeconds":73,
                            "blackoutEffectEnhancementEnabled":true,"knifeDurabilityEnabled":false},
                  "tasks":{
                    "habitrain_core:add_coal":{"shopPrice":45},
                    "habitrain_core:blackout_pet_cat":{},
                    "habitrain_core:blackout_eat":{"enabled":false,"goldReward":7},
                    "habitrain_core:pet_cat":{"enabled":false,"shopPrice":22},
                    "example:add_coal":{"enabled":true}
                  },
                  "gameModes":{"habitrain_core:habitrain:blackout":{},"habitrain_core:sre:murder":{}},
                  "modeMapVote":{"modes":{"habitrain_core:habitrain:blackout":{},"example:blackout":{}},
                                 "maps":{"station":{"enabled":false}}}
                }
                """).getAsJsonObject();
    }

    @Test
    void removesOnlyRetiredCoreSettingsAndIsIdempotent() {
        JsonObject root = legacy();
        assertTrue(RemovedModeConfigMigration.prune(root));
        assertFalse(RemovedModeConfigMigration.prune(root));
        assertFalse(root.getAsJsonObject("global").has("tempPowerPrice"));
        assertEquals(73, root.getAsJsonObject("global").get("blackoutGlobalCooldownSeconds").getAsInt());
        assertTrue(root.getAsJsonObject("global").get("blackoutEffectEnhancementEnabled").getAsBoolean());
        JsonObject tasks = root.getAsJsonObject("tasks");
        assertFalse(tasks.has("habitrain_core:add_coal"));
        assertFalse(tasks.has("habitrain_core:blackout_pet_cat"));
        assertTrue(tasks.has("example:add_coal"));
        assertTrue(tasks.has("habitrain_core:blackout_eat"));
        assertFalse(tasks.getAsJsonObject("habitrain_core:pet_cat").get("enabled").getAsBoolean());
        assertFalse(tasks.getAsJsonObject("habitrain_core:pet_cat").has("shopPrice"));
        assertTrue(root.getAsJsonObject("gameModes").has("habitrain_core:sre:murder"));
        assertEquals(1, root.getAsJsonObject("gameModes").size());
        assertTrue(root.getAsJsonObject("modeMapVote").getAsJsonObject("modes").has("example:blackout"));
        assertTrue(root.getAsJsonObject("modeMapVote").getAsJsonObject("maps").has("station"));
    }

    @Test
    void fullSyncAndPatchCannotRestoreRetiredSettings() {
        ConfigRepository repo = new ConfigRepository();
        ConfigSync sync = new ConfigSync(null);
        sync.loadFromJsonString(repo, legacy().toString());
        assertFalse(repo.getAllConfigs().containsKey("habitrain_core:add_coal"));
        assertFalse(repo.getMutableGameModeConfigs().containsKey("habitrain_core:habitrain:blackout"));
        assertFalse(repo.getModeMapVote().modes.containsKey("habitrain_core:habitrain:blackout"));
        assertEquals(73, repo.getBlackoutGlobalCooldownSeconds());
        assertTrue(sync.mergeFromJsonString(repo, legacy().toString()));
        assertFalse(repo.getAllConfigs().containsKey("habitrain_core:blackout_pet_cat"));
        assertFalse(repo.getModeMapVote().modes.containsKey("habitrain_core:habitrain:blackout"));
    }
}
