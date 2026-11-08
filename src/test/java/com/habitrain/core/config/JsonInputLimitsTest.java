package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.habitrain.core.network.ConfigUpdateScope;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonInputLimitsTest {
    @Test void rejectsDeepWireJsonBeforeGsonRecursion() {
        String json = "[".repeat(2000) + "0" + "]".repeat(2000);
        assertThrows(IllegalArgumentException.class, () -> JsonInputLimits.check(json));
    }
    @Test void rejectsDeepPatchValuesBeforeDefensiveCopy() {
        JsonObject value = new JsonObject(), cursor = value;
        for (int i = 0; i < 2000; i++) { JsonObject next = new JsonObject(); cursor.add("x", next); cursor = next; }
        JsonObject patch = new JsonObject(), set = new JsonObject();
        set.add("/tasks/test", value); patch.add("set", set); patch.add("remove", new com.google.gson.JsonArray());
        assertThrows(IllegalArgumentException.class, () -> ConfigPatch.apply(new JsonObject(), patch, ConfigUpdateScope.FULL_MOD_MENU, ""));
    }
    @Test void quotedBracesAndEscapedQuotesAreData() {
        String json = "{\"value\":\"" + "[".repeat(500) + "\\\"\\\\text\"}";
        assertEquals(json, JsonInputLimits.check(json));
        assertDoesNotThrow(() -> com.google.gson.JsonParser.parseString(json));
    }
    @Test void acceptsTheDeclaredDepthBoundaryWithoutSpecialCharacters() {
        String json = "[".repeat(JsonInputLimits.MAX_DEPTH) + "0" + "]".repeat(JsonInputLimits.MAX_DEPTH);
        assertDoesNotThrow(() -> JsonInputLimits.check(json));
        assertDoesNotThrow(() -> JsonInputLimits.check(com.google.gson.JsonParser.parseString(json)));
        assertThrows(IllegalArgumentException.class, () -> JsonInputLimits.check("[" + json + "]"));
    }
}
