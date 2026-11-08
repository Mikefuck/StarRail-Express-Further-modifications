package com.habitrain.core.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigPreviewPathPersistenceTest {
    @TempDir Path directory;

    @Test void cleanConfigWithBlankPreviewPersistsUploadAndRefreshesAuthoritativeRevision() throws Exception {
        verifyPersistence("");
    }

    @Test void customPreviewPathIsReplacedWithoutLosingOtherProfileFields() throws Exception {
        verifyPersistence("previews/legacy.png");
    }

    @Test void missingProfileIsCreatedAndPersisted() {
        Path file = directory.resolve("config.json");
        ConfigManager manager = manager(file, null);
        assertEquals(ConfigSaveResult.Status.SAVED, manager.saveMapVotePreviewPath("station", "previews/new.png").status());
        ConfigRepository reloaded = new ConfigRepository();
        new ConfigStore(file.toFile()).load(reloaded);
        assertEquals("previews/new.png", reloaded.getModeMapVote().maps.get("station").profile.previewPath);
    }

    @Test void failedWriteRestoresExistingProfileAndAuthoritativeJson() throws Exception {
        verifyFailedWrite(new MapVoteProfileSettings());
    }

    @Test void failedWriteDoesNotLeaveNewProfileInLiveConfig() throws Exception {
        verifyFailedWrite(null);
    }

    private void verifyPersistence(String oldPath) throws Exception {
        Path file = directory.resolve("config.json");
        MapVoteProfileSettings profile = new MapVoteProfileSettings();
        profile.description = "keep this description";
        profile.previewPath = oldPath;
        ConfigManager manager = manager(file, profile);
        assertEquals(ConfigSaveResult.Status.NO_CHANGE, manager.save().status());
        long oldRevision = manager.revision();

        assertEquals(ConfigSaveResult.Status.SAVED, manager.saveMapVotePreviewPath("station", "previews/new.png").status());
        assertTrue(manager.revision() > oldRevision);
        var authoritative = JsonParser.parseString(manager.authoritativeSyncJson()).getAsJsonObject();
        assertEquals("previews/new.png", authoritative.getAsJsonObject("modeMapVote").getAsJsonObject("maps")
                .getAsJsonObject("station").getAsJsonObject("profile").get("preview").getAsString());
        ConfigRepository reloaded = new ConfigRepository();
        new ConfigStore(file.toFile()).load(reloaded);
        assertEquals("previews/new.png", reloaded.getModeMapVote().maps.get("station").profile.previewPath);
        assertEquals("keep this description", reloaded.getModeMapVote().maps.get("station").profile.description);
        String saved = Files.readString(file);
        assertEquals(ConfigSaveResult.Status.NO_CHANGE, manager.saveMapVotePreviewPath("station", "previews/new.png").status());
        assertEquals(saved, Files.readString(file));
    }

    private void verifyFailedWrite(MapVoteProfileSettings original) throws Exception {
        Path file = directory.resolve("config.json");
        if (original != null) original.previewPath = "previews/old.png";
        ConfigManager manager = manager(file, original);
        long revision = manager.revision();
        String authoritative = manager.authoritativeSyncJson();
        Files.delete(file);
        Files.createDirectory(file);
        assertEquals(ConfigSaveResult.Status.FAILED, manager.saveMapVotePreviewPath("station", "previews/new.png").status());
        assertSame(original, manager.getModeMapVoteSettings().maps.get("station").profile);
        if (original != null) assertEquals("previews/old.png", original.previewPath);
        assertEquals(revision, manager.revision());
        assertEquals(authoritative, manager.authoritativeSyncJson());
    }

    private ConfigManager manager(Path file, MapVoteProfileSettings profile) {
        ConfigManager manager = new ConfigManager(new ConfigStore(file.toFile()));
        MapVoteEntry entry = MapVoteEntry.createDefault();
        entry.profile = profile;
        manager.getModeMapVoteSettings().maps.put("station", entry);
        manager.setModeMapVoteSettings(manager.getModeMapVoteSettings());
        assertEquals(ConfigSaveResult.Status.SAVED, manager.save().status());
        return manager;
    }
}
