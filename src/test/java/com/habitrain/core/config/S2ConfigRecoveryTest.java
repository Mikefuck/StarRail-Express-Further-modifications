package com.habitrain.core.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.habitrain.core.persist.AtomicJsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class S2ConfigRecoveryTest {
    @TempDir Path directory;
    @Test void schemaInvalidExternalEditCannotReplaceTheGoodBackup() throws Exception {
        Path file = directory.resolve("schema.json");
        String good = "{\"global\":{\"sheriffCountDivisor\":9}}";
        Files.writeString(AtomicJsonFiles.bakPath(file), good);
        Files.writeString(file, "{\"global\":{\"sheriffCountDivisor\":[]}}");
        ConfigRepository repo = new ConfigRepository();
        repo.setSheriffCountDivisor(12);
        assertTrue(new ConfigStore(file.toFile()).save(repo));
        assertEquals(good, Files.readString(AtomicJsonFiles.bakPath(file)));
    }
    @Test void externalCorruptionAfterLoadDoesNotReplaceTheOnlyGoodBackup() throws Exception {
        Path file = directory.resolve("config.json");
        Gson gson = new Gson();
        String good = "{\"version\":1}";
        Files.writeString(file, good);
        assertTrue(AtomicJsonFiles.readJson(file, JsonObject.class, gson).ok());
        Files.writeString(AtomicJsonFiles.bakPath(file), good);
        Files.writeString(file, "{truncated");
        JsonObject next = new JsonObject(); next.addProperty("version", 2);
        assertTrue(AtomicJsonFiles.writeJson(file, next, gson, true));
        assertEquals(good, Files.readString(AtomicJsonFiles.bakPath(file)));
        assertEquals(2, AtomicJsonFiles.readJson(file, JsonObject.class, gson).value().get("version").getAsInt());
    }
    @Test void malformedUtf8UsesBackupAndPreservesTheBadBytesAsEvidence() throws Exception {
        Path file = directory.resolve("utf8.json");
        Files.write(file, new byte[]{'{', '"', (byte) 0xc3, (byte) 0x28});
        Files.writeString(AtomicJsonFiles.bakPath(file), "{\"ok\":true}", StandardCharsets.UTF_8);
        var loaded = AtomicJsonFiles.readJson(file, JsonObject.class, new Gson());
        assertTrue(loaded.usedBackup());
        assertEquals(AtomicJsonFiles.SourceState.INVALID_CONTENT, loaded.primaryState());
        assertNotNull(loaded.quarantined());
        assertArrayEquals(new byte[]{'{', '"', (byte) 0xc3, (byte) 0x28}, Files.readAllBytes(loaded.quarantined()));
    }
    @Test void damagedFieldDoesNotDiscardLegalGlobalFieldsOrTasks() throws Exception {
        Path file = directory.resolve("fields.json");
        String backup = "{\"global\":{\"sheriffCountDivisor\":9,\"knifeDurabilityEnabled\":false}}";
        Files.writeString(AtomicJsonFiles.bakPath(file), backup);
        Files.writeString(file, """
                {"global":{"sheriffCountDivisor":[],"knifeDurabilityEnabled":true},
                 "tasks":{"test/task":{"enabled":false,"goldReward":13}}}
                """);
        ConfigRepository repo = new ConfigRepository(); new ConfigStore(file.toFile()).load(repo);
        assertEquals(9, repo.getSheriffCountDivisor());
        assertTrue(repo.isKnifeDurabilityEnabled());
        assertNotNull(repo.getMutableTaskConfigs().get("test/task"));
        assertEquals(13, repo.getMutableTaskConfigs().get("test/task").goldReward);
        assertEquals(backup, Files.readString(AtomicJsonFiles.bakPath(file)));
        try (var files = Files.list(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("fields.json.corrupt-")));
        }
    }
    @Test void legalFieldsAreRecoveredWithoutABackup() throws Exception {
        Path file = directory.resolve("no-backup.json");
        Files.writeString(file, "{\"global\":{\"sheriffCountDivisor\":[],\"knifeDurabilityEnabled\":true}}");
        ConfigRepository repo = new ConfigRepository(); new ConfigStore(file.toFile()).load(repo);
        assertTrue(repo.isKnifeDurabilityEnabled());
        assertTrue(AtomicJsonFiles.readJson(file, JsonObject.class, new Gson()).ok());
    }
}
