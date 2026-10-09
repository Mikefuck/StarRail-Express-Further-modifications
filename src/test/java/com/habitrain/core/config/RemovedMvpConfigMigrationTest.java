package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RemovedMvpConfigMigrationTest {
    @TempDir
    Path tmp;

    @Test
    void legacySettingsAreRemovedOnCommitWithoutResettingCurrentSettings() throws Exception {
        Path file = tmp.resolve("habitrain_core.json");
        Files.writeString(file, """
                {
                  "global": {"knifeDurabilityEnabled": true, "blackoutGlobalCooldownSeconds": 73},
                  "modeMapVote": {"modeDurationSeconds": 45},
                  "mvpAnimations": {"enabled": false, "speed": 1.5, "animations": {"victory_bow": false}}
                }
                """);

        ConfigRepository repo = new ConfigRepository();
        ConfigStore store = new ConfigStore(file.toFile());
        store.load(repo);

        assertTrue(repo.isKnifeDurabilityEnabled());
        assertEquals(73, repo.getBlackoutGlobalCooldownSeconds());
        assertEquals(45, repo.getModeMapVote().modeDurationSeconds);
        assertTrue(store.commit(repo), "loading the retired section should mark the file dirty");
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertFalse(saved.has("mvpAnimations"));
        assertTrue(saved.getAsJsonObject("global").get("knifeDurabilityEnabled").getAsBoolean());
        assertEquals(73, saved.getAsJsonObject("global").get("blackoutGlobalCooldownSeconds").getAsInt());
        assertEquals(45, saved.getAsJsonObject("modeMapVote").get("modeDurationSeconds").getAsInt());

        store.load(repo);
        assertFalse(store.commit(repo), "the migration should only rewrite the config once");
    }

    @Test
    void malformedRetiredSettingsDoNotTriggerConfigRecovery() throws Exception {
        Path file = tmp.resolve("habitrain_core.json");
        Files.writeString(file, """
                {"global": {"blackoutGlobalCooldownSeconds": 81},
                 "mvpAnimations": {"speed": "invalid", "animations": 123}}
                """);

        ConfigRepository repo = new ConfigRepository();
        ConfigStore store = new ConfigStore(file.toFile());
        store.load(repo);

        assertEquals(81, repo.getBlackoutGlobalCooldownSeconds());
        assertTrue(store.commit(repo));
        assertFalse(JsonParser.parseString(Files.readString(file)).getAsJsonObject().has("mvpAnimations"));
    }

    @Test
    void fullSyncAndPatchIgnoreRetiredSettingsAndNeverSerializeThem() {
        ConfigRepository repo = new ConfigRepository();
        ConfigSync sync = new ConfigSync(null);
        ConfigStore store = new ConfigStore(tmp.resolve("habitrain_core.json").toFile());

        sync.loadFromJsonString(repo, """
                {"global": {"blackoutGlobalCooldownSeconds": 73},
                 "mvpAnimations": {"speed": "invalid"}}
                """);
        assertEquals(73, repo.getBlackoutGlobalCooldownSeconds());
        assertFalse(JsonParser.parseString(store.toJsonString(repo)).getAsJsonObject().has("mvpAnimations"));

        assertTrue(sync.mergeFromJsonString(repo, """
                {"global": {"blackoutGlobalCooldownSeconds": 91},
                 "mvpAnimations": {"animations": 123}}
                """));
        assertEquals(91, repo.getBlackoutGlobalCooldownSeconds());
        assertFalse(store.buildJsonRoot(repo, true).has("mvpAnimations"));
    }
}
