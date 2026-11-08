package com.habitrain.core.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class S8GameModeConfigScopeTest {
    @Test void nestedSettingsHaveExplicitWriteOwnership() {
        var scope=new GameModeConfigScope("example:mode");
        var nested=new JsonObject();nested.addProperty("value",1);
        scope.setSetting("nested",nested);
        nested.addProperty("value",2);
        scope.getSetting("nested").getAsJsonObject().addProperty("value",3);
        scope.getCustomSettings().get("nested").getAsJsonObject().addProperty("value",4);
        assertEquals(1,scope.getSetting("nested").getAsJsonObject().get("value").getAsInt());
        assertThrows(UnsupportedOperationException.class,()->scope.getCustomSettings().clear());
    }
    @Test void scopeRoundTripPreservesEnabledAndCustomFields() {
        var scope=new GameModeConfigScope("example:mode");scope.setEnabled(false);
        var nested=new JsonObject();nested.addProperty("value",7);scope.setSetting("nested",nested);
        var restored=GameModeConfigScope.fromJson("example:mode",scope.toJson());
        assertFalse(restored.isEnabled());assertEquals(scope.toJson(),restored.toJson());
        restored.setSetting("nested",null);
        assertNotNull(scope.getSetting("nested"));
    }
}
