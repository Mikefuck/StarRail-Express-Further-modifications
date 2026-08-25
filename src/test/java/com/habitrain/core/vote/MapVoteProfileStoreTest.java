package com.habitrain.core.vote;

import com.habitrain.core.network.MapVoteProfilePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapVoteProfileStoreTest {
    @Test
    void reservedMapIdMatchesOnlyTheReservedPathSegment() {
        assertTrue(MapVoteProfileStore.isReservedMapId("map_vote"));
        assertTrue(MapVoteProfileStore.isReservedMapId("map_vote/maps"));
        assertTrue(MapVoteProfileStore.isReservedMapId("map_vote\\maps"));
        assertTrue(MapVoteProfileStore.isReservedMapId("random"));
        assertTrue(MapVoteProfileStore.isReservedMapId("RANDOM"));
        assertFalse(MapVoteProfileStore.isReservedMapId("map_vote_arena"));
        assertFalse(MapVoteProfileStore.isReservedMapId("map_voter"));
    }

    @Test
    void escapedPreviewNamesDoNotCollideAfterSanitizing() {
        String slash = MapVoteProfileStore.escapeId("station/a:b");
        String underscore = MapVoteProfileStore.escapeId("station_a_b");

        assertNotEquals(slash, underscore);
        assertTrue(slash.matches("[a-zA-Z0-9._-]+"));
        assertTrue(underscore.matches("[a-zA-Z0-9._-]+"));
    }

    @Test
    void pathOverloadsEnsureAndLoadWithoutServerLevel(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("train_maps").resolve("map_vote");
        MapVoteProfileStore.ensureProfiles(base, List.of("station_a"), Map.of());

        Path preview = base.resolve(MapVoteProfileStore.PREVIEW_DIR)
                .resolve(MapVoteProfileStore.escapeId("station_a") + ".png");
        assertTrue(Files.isRegularFile(preview));
        assertTrue(Files.size(preview) > 0);

        Map<String, MapVoteProfilePayload.MapProfile> loaded =
                MapVoteProfileStore.loadProfiles(base, List.of("station_a"), Map.of());
        assertTrue(loaded.containsKey("station_a"));
        assertNotNull(loaded.get("station_a").previewBytes());
        assertTrue(loaded.get("station_a").previewBytes().length > 0);
    }

    @Test
    void pngValidationAcceptsPlaceholderAndRejectsCorruptHeaders() {
        byte[] valid = MapVoteProfileStore.placeholderBytes();
        assertTrue(MapVoteProfileStore.isValidPng(valid));

        byte[] corrupt = valid.clone();
        corrupt[1] = 0;
        assertFalse(MapVoteProfileStore.isValidPng(corrupt));

        byte[] oversizedDimensions = valid.clone();
        oversizedDimensions[16] = 0;
        oversizedDimensions[17] = 0;
        oversizedDimensions[18] = 0x20;
        oversizedDimensions[19] = 0x01; // 8193, still over the 1024 cap
        assertFalse(MapVoteProfileStore.isValidPng(oversizedDimensions));

        byte[] justOverCap = valid.clone();
        justOverCap[16] = 0;
        justOverCap[17] = 0;
        justOverCap[18] = 0x04;
        justOverCap[19] = 0x01; // 1025×1
        assertFalse(MapVoteProfileStore.isValidPng(justOverCap));

        byte[] atCap = valid.clone();
        atCap[16] = 0;
        atCap[17] = 0;
        atCap[18] = 0x04;
        atCap[19] = 0x00; // 1024×1
        assertTrue(MapVoteProfileStore.isValidPng(atCap));
    }

    @Test
    void truncatedPreviewWithPositiveSizeIsRebuilt(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("train_maps").resolve("map_vote");
        MapVoteProfileStore.ensureProfiles(base, List.of("station_a"), Map.of());
        Path preview = base.resolve(MapVoteProfileStore.PREVIEW_DIR)
                .resolve(MapVoteProfileStore.escapeId("station_a") + ".png");
        Files.write(preview, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D});
        assertTrue(MapVoteProfileStore.previewNeedsRebuild(preview));

        MapVoteProfileStore.ensureProfiles(base, List.of("station_a"), Map.of());
        assertFalse(MapVoteProfileStore.previewNeedsRebuild(preview));
        assertTrue(MapVoteProfileStore.isValidPng(Files.readAllBytes(preview)));
        assertFalse(Files.exists(preview.resolveSibling(preview.getFileName() + ".uploading")));
    }
}
