package com.habitrain.core.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicJsonFilesTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @TempDir
    Path tmp;

    @Test
    void writeJsonReplacesViaTmpAndKeepsBak() throws Exception {
        Path file = tmp.resolve("habitrain_menu_gate.json");
        Files.writeString(file, "{\"enabled\":true}", StandardCharsets.UTF_8);

        JsonObject next = new JsonObject();
        next.addProperty("enabled", false);
        assertTrue(AtomicJsonFiles.writeJson(file, next, GSON, true));

        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"enabled\": false") || body.contains("\"enabled\":false"));
        assertTrue(Files.readString(AtomicJsonFiles.bakPath(file), StandardCharsets.UTF_8).contains("true"));
        assertFalse(Files.exists(file.resolveSibling("habitrain_menu_gate.json.tmp")));
    }

    @Test
    void bakCopyFailureAbortsReplace() throws Exception {
        Path file = tmp.resolve("gate.json");
        Files.writeString(file, "{\"enabled\":true}", StandardCharsets.UTF_8);
        Files.createDirectory(AtomicJsonFiles.bakPath(file));

        JsonObject next = new JsonObject();
        next.addProperty("enabled", false);
        assertFalse(AtomicJsonFiles.writeJson(file, next, GSON, true));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("true"));
    }

    @Test
    void truncatedPrimaryRestoresBakAndQuarantines() throws Exception {
        Path file = tmp.resolve("gate.json");
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), "{\"enabled\":false}", StandardCharsets.UTF_8);

        AtomicJsonFiles.JsonLoad<JsonObject> load = AtomicJsonFiles.readJson(file, JsonObject.class, GSON);
        assertTrue(load.ok());
        assertTrue(load.usedBackup());
        assertFalse(load.value().get("enabled").getAsBoolean());
        assertFalse(Files.isRegularFile(file));
        assertNotNull(load.quarantined());
    }

    @Test
    void bothUnreadableIsCorrupt() throws Exception {
        Path file = tmp.resolve("gate.json");
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), "{", StandardCharsets.UTF_8);

        AtomicJsonFiles.JsonLoad<JsonObject> load = AtomicJsonFiles.readJson(file, JsonObject.class, GSON);
        assertTrue(load.corrupt());
        assertNull(load.value());
    }

    @Test
    void neitherFileIsMissing() {
        Path file = tmp.resolve("absent.json");
        AtomicJsonFiles.JsonLoad<JsonObject> load = AtomicJsonFiles.readJson(file, JsonObject.class, GSON);
        assertTrue(load.isMissing());
        assertFalse(load.corrupt());
    }
}
