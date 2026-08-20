package com.habitrain.core.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
