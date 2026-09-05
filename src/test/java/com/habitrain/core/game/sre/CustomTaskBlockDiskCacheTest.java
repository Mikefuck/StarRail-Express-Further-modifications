package com.habitrain.core.game.sre;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CustomTaskBlockDiskCacheTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void cacheFileModelSerializesAndRestoresCorrectly() {
        BlockPos pos1 = new BlockPos(100, 64, -200);
        BlockPos pos2 = new BlockPos(3504, 78, 267);
        Map<BlockPos, Set<Integer>> original = Map.of(
                pos1, Set.of(13),
                pos2, Set.of(20, 21)
        );

        CustomTaskBlockDiskCache.CacheFileModel model =
                new CustomTaskBlockDiskCache.CacheFileModel("areas6", original);

        assertEquals("areas6", model.mapName);
        assertEquals(2, model.entries.size());

        String json = GSON.toJson(model);
        assertNotNull(json);

        CustomTaskBlockDiskCache.CacheFileModel restored =
                GSON.fromJson(json, CustomTaskBlockDiskCache.CacheFileModel.class);
        Map<BlockPos, Set<Integer>> restoredMap = restored.toMap();

        assertEquals(2, restoredMap.size());
        assertEquals(Set.of(13), restoredMap.get(pos1));
        assertEquals(Set.of(20, 21), restoredMap.get(pos2));
    }

    @Test
    void loadSafelyReturnsEmptyOnNullOrBlank() {
        assertTrue(CustomTaskBlockDiskCache.load(null, "areas6").isEmpty());
        assertTrue(CustomTaskBlockDiskCache.load(null, null).isEmpty());
        assertTrue(CustomTaskBlockDiskCache.load(null, "").isEmpty());
    }

    @Test
    void diskFileRoundTrip(@TempDir Path tempDir) throws Exception {
        File cacheFile = tempDir.resolve("test_map.cache.json").toFile();

        BlockPos pos = new BlockPos(12, 34, 56);
        Map<BlockPos, Set<Integer>> data = Map.of(pos, Set.of(13, 37));
        CustomTaskBlockDiskCache.CacheFileModel model =
                new CustomTaskBlockDiskCache.CacheFileModel("test_map", data);

        try (FileWriter writer = new FileWriter(cacheFile)) {
            GSON.toJson(model, writer);
        }

        assertTrue(cacheFile.exists());

        try (FileReader reader = new FileReader(cacheFile)) {
            CustomTaskBlockDiskCache.CacheFileModel loaded =
                    GSON.fromJson(reader, CustomTaskBlockDiskCache.CacheFileModel.class);
            Map<BlockPos, Set<Integer>> loadedMap = loaded.toMap();
            assertEquals(1, loadedMap.size());
            assertEquals(Set.of(13, 37), loadedMap.get(pos));
        }
    }
}
