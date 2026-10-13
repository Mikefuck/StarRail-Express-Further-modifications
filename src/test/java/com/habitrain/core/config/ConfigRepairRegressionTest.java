package com.habitrain.core.config;

import com.google.gson.*;
import com.habitrain.core.network.ConfigUpdateScope;
import com.habitrain.core.persist.AtomicJsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigRepairRegressionTest {
    @TempDir Path directory;
    @Test void schemaInvalidPrimaryUsesBackupWithoutOverwritingIt() throws Exception {
        Path file = directory.resolve("config.json");
        String backup = "{\"global\":{\"sheriffCountDivisor\":9,\"knifeDurabilityEnabled\":true}}";
        Files.writeString(file, "{\"global\":{\"sheriffCountDivisor\":[]}}"); Files.writeString(AtomicJsonFiles.bakPath(file), backup);
        ConfigRepository repository = new ConfigRepository(); new ConfigStore(file.toFile()).load(repository);
        assertEquals(9, repository.getSheriffCountDivisor()); assertTrue(repository.isKnifeDurabilityEnabled());
        assertEquals(backup, Files.readString(AtomicJsonFiles.bakPath(file)));
    }
    @Test void missingPrimaryAndInvalidBackupIsCorrupt() throws Exception {
        Path file = directory.resolve("missing.json"); Files.writeString(AtomicJsonFiles.bakPath(file), "{");
        assertTrue(AtomicJsonFiles.readJson(file, JsonObject.class, new Gson()).corrupt());
        Files.delete(AtomicJsonFiles.bakPath(file)); assertTrue(AtomicJsonFiles.readJson(file, JsonObject.class, new Gson()).isMissing());
        Files.createDirectory(file); assertTrue(AtomicJsonFiles.readJson(file, JsonObject.class, new Gson()).corrupt());
        assertEquals(AtomicJsonFiles.SourceState.INVALID_TYPE, AtomicJsonFiles.readJson(file, JsonObject.class, new Gson()).primaryState());
    }
    @Test void invalidTaskNumbersHaveNoPartialMutationAndZeroIsValid() {
        TaskConfigEntry entry = new TaskConfigEntry(); entry.hasGoldReward = true; entry.goldReward = 7;
        for (String input : new String[]{"abc", "NaN", "Infinity", "-1"}) {
            assertThrows(IllegalArgumentException.class, () -> TaskNumericDraft.parse("99", "2", input).apply(entry));
            assertEquals(7, entry.goldReward); assertTrue(entry.hasGoldReward); assertFalse(entry.hasRefreshWeight);
        }
        assertThrows(IllegalArgumentException.class, () -> TaskNumericDraft.parse("2147483648", "", ""));
        TaskNumericDraft.parse("", "-2.5", "0").apply(entry);
        assertFalse(entry.hasGoldReward); assertTrue(entry.hasRefreshWeight); assertEquals(0, entry.refreshWeight);
        assertThrows(IllegalArgumentException.class, () -> TaskConfigEntry.fromJson(JsonParser.parseString("{\"refreshWeight\":\"NaN\"}").getAsJsonObject()));
    }
    @Test void deltaPreservesUnchangedFieldsAndSupportsInheritanceAndDeletion() {
        JsonObject old = JsonParser.parseString("{\"tasks\":{\"a/b\":{\"goldReward\":5,\"enabled\":true},\"old\":{\"enabled\":true}},\"global\":{\"sheriffCountDivisor\":6}}").getAsJsonObject();
        JsonObject next = old.deepCopy(); next.getAsJsonObject("tasks").getAsJsonObject("a/b").remove("goldReward"); next.getAsJsonObject("tasks").remove("old");
        var patch = ConfigPatch.diff(old, next);
        assertEquals(next, ConfigPatch.apply(old, patch, ConfigUpdateScope.BACKPACK_TASKS, ""));
        JsonObject attack = ConfigPatch.diff(old, JsonParser.parseString("{\"global\":{\"sheriffCountDivisor\":10}}").getAsJsonObject());
        assertThrows(IllegalArgumentException.class, () -> ConfigPatch.apply(old, attack, ConfigUpdateScope.BACKPACK_TASKS, ""));
    }
    @Test void remoteSaveDoesNotWriteDiskOrChangeIntegratedServerAuthority() throws Exception {
        Path file = directory.resolve("config.json"); ConfigManager manager = new ConfigManager(new ConfigStore(file.toFile()));
        manager.setSheriffCountDivisor(6); assertEquals(ConfigSaveResult.Status.SAVED, manager.save().status()); String local = Files.readString(file);
        manager.installClientSession(); manager.markClientConnected(); manager.applySyncFromJson("{\"global\":{\"sheriffCountDivisor\":9},\"_habitrain_config_revision\":7}"); manager.beginClientEditing();
        manager.setSheriffCountDivisor(12);
        ConfigManager.setClientSaveHandler((base, draft, revision) -> { assertEquals(7, revision); return new ConfigSaveResult(ConfigSaveResult.Status.SUBMITTING, "pending"); });
        try {
            assertEquals(ConfigSaveResult.Status.SUBMITTING, manager.save().status()); assertEquals(local, Files.readString(file));
            CompletableFuture<Integer> serverValue = CompletableFuture.supplyAsync(manager::getSheriffCountDivisor);
            assertEquals(6, serverValue.get(5, TimeUnit.SECONDS));
            manager.clearClientSession(); manager.load(); assertEquals(6, manager.getSheriffCountDivisor());
        } finally { ConfigManager.setClientSaveHandler(null); }
    }
    @Test void diskFailureKeepsAuthorityAndDraftAndConflictsCannotOverwrite() throws Exception {
        Path file = directory.resolve("config.json"); ConfigManager manager = new ConfigManager(new ConfigStore(file.toFile())); manager.setSheriffCountDivisor(6); manager.save();
        long base = manager.revision(); var old = JsonParser.parseString(manager.toJsonString()).getAsJsonObject(); var next = old.deepCopy(); next.getAsJsonObject("global").addProperty("sheriffCountDivisor", 9); var patch = ConfigPatch.diff(old, next);
        Files.createDirectory(AtomicJsonFiles.bakPath(file));
        assertEquals(ConfigSaveResult.Status.FAILED, manager.submitPatch(patch, base, ConfigUpdateScope.FULL_MOD_MENU, "").status()); assertEquals(6, manager.getSheriffCountDivisor()); assertEquals(base, manager.revision());
        Files.delete(AtomicJsonFiles.bakPath(file)); manager.setSheriffCountDivisor(7);
        assertEquals(ConfigSaveResult.Status.CONFLICT, manager.submitPatch(patch, base, ConfigUpdateScope.FULL_MOD_MENU, "").status()); assertEquals(7, manager.getSheriffCountDivisor());
    }
    @Test void acknowledgementRebasesOnlyEditsMadeAfterSubmission() {
        ConfigManager manager = new ConfigManager(new ConfigStore(directory.resolve("config.json").toFile()));
        manager.installClientSession(); manager.markClientConnected();
        manager.applySyncFromJson("{\"global\":{\"sheriffCountDivisor\":6,\"lobbyVoiceGroupEnabled\":true},\"_habitrain_config_revision\":1}");
        manager.beginClientEditing(); manager.setSheriffCountDivisor(9); String submitted = manager.toJsonString();
        manager.setSheriffCountDivisor(12);
        manager.acknowledgeClient("{\"global\":{\"sheriffCountDivisor\":9,\"lobbyVoiceGroupEnabled\":false},\"_habitrain_config_revision\":2}", 2, true, submitted);
        assertEquals(12, manager.getSheriffCountDivisor()); assertFalse(manager.isLobbyVoiceGroupEnabled());
    }
    @Test void conflictReloadAndConnectionBoundaryDiscardOldDraft() {
        ConfigManager manager = new ConfigManager(new ConfigStore(directory.resolve("config.json").toFile()));
        manager.setSheriffCountDivisor(8); manager.save(); manager.installClientSession(); manager.markClientConnected();
        manager.applySyncFromJson("{\"global\":{\"sheriffCountDivisor\":9},\"_habitrain_config_revision\":1}");
        manager.beginClientEditing(); manager.setSheriffCountDivisor(12);
        manager.acknowledgeClient("{\"global\":{\"sheriffCountDivisor\":10},\"_habitrain_config_revision\":2}", 2, false, manager.toJsonString());
        manager.markClientConfigConflict(); assertTrue(manager.hasClientConfigConflict()); assertEquals(12, manager.getSheriffCountDivisor());
        manager.reloadClientDraft(); assertEquals(10, manager.getSheriffCountDivisor()); assertFalse(manager.hasClientConfigConflict());
        manager.setSheriffCountDivisor(15); manager.markClientConnected();
        manager.applySyncFromJson("{\"global\":{\"sheriffCountDivisor\":11},\"_habitrain_config_revision\":1}"); assertEquals(11, manager.getSheriffCountDivisor());
        manager.clearClientSession(); assertEquals(8, manager.getSheriffCountDivisor(), "integrated disconnect restores saved local snapshot even without reload");
    }
    @Test void orderedModeChangesSurviveDeltaAndNoChangeComparison() {
        var before = JsonParser.parseString("{\"modeMapVote\":{\"modes\":{\"a\":{\"enabled\":true},\"b\":{\"enabled\":true}}}}").getAsJsonObject();
        var after = JsonParser.parseString("{\"modeMapVote\":{\"modes\":{\"b\":{\"enabled\":true},\"a\":{\"enabled\":true}}}}").getAsJsonObject();
        var patch = ConfigPatch.diff(before, after); assertFalse(ConfigPatch.empty(patch));
        var restored = ConfigPatch.apply(before, patch, ConfigUpdateScope.BACKPACK_MAP_VOTE, "");
        assertEquals(after.toString(), restored.toString());
    }
    @Test void reopeningCleanEditorUsesLatestMirrorRevision() {
        ConfigManager manager = new ConfigManager(new ConfigStore(directory.resolve("config.json").toFile()));
        manager.installClientSession(); manager.markClientConnected();
        manager.applySyncFromJson("{\"_habitrain_config_revision\":1}"); manager.beginClientEditing();
        manager.applySyncFromJson("{\"_habitrain_config_revision\":2}"); manager.endClientEditing(); manager.beginClientEditing(); manager.setSheriffCountDivisor(10);
        ConfigManager.setClientSaveHandler((base, draft, revision) -> { assertEquals(2, revision); return ConfigSaveResult.unchanged(); });
        try { assertTrue(manager.save().successful()); } finally { ConfigManager.setClientSaveHandler(null); }
    }
}
