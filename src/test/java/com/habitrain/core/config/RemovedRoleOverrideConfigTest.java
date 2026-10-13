package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.network.ConfigUpdateScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemovedRoleOverrideConfigTest {
    @TempDir Path directory;

    @Test
    void oldRoleSectionIsIgnoredAndNeverSerializedWhileOtherSettingsSurvive() {
        ConfigRepository repository = new ConfigRepository();
        String oldConfig = "{\"global\":{\"sheriffCountDivisor\":9},\"roleOverrides\":{\"globalEnabled\":false,\"entries\":{\"old:role\":true}}}";
        ConfigStore store = new ConfigStore(directory.resolve("config.json").toFile());
        assertTrue(new ConfigSync(store).loadFromJsonString(repository, oldConfig));
        assertEquals(9, repository.getSheriffCountDivisor());
        JsonObject saved = JsonParser.parseString(store.toJsonString(repository)).getAsJsonObject();
        assertFalse(saved.has("roleOverrides"));
        assertEquals(9, saved.getAsJsonObject("global").get("sheriffCountDivisor").getAsInt());
    }

    @Test
    void networkPatchCannotReintroduceRemovedRoleSection() {
        JsonObject patch = new JsonObject();
        JsonObject set = new JsonObject();
        set.addProperty("/roleOverrides/globalEnabled", true);
        patch.add("set", set);
        patch.add("remove", new com.google.gson.JsonArray());
        assertThrows(IllegalArgumentException.class, () ->
                ConfigPatch.apply(new JsonObject(), patch, ConfigUpdateScope.FULL_MOD_MENU, ""));
    }
}
