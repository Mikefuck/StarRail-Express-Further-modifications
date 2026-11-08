package com.habitrain.core.vote;

import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.MapVoteProfileSettings;
import com.habitrain.core.network.MapVoteProfilePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MapVoteProfilePacketCacheTest {
    @TempDir Path directory;

    @AfterEach void clearCache() {
        MapVoteProfileStore.clearCache();
    }

    @Test void savedProfileRevisionImmediatelyReplacesCachedMetadata() {
        Object server = new Object();
        MapVoteEntry entry = entry();
        entry.profile.description = "old";
        var entries = Map.of("station", entry);
        var first = load(server, directory, entries, 4, 0);
        assertSame(first, load(server, directory, entries, 4, 1));

        entry.profile.description = "updated";
        entry.profile.tags = List.of("indoor");
        entry.minPlayers = 5;
        entry.maxPlayers = 12;
        var updated = load(server, directory, entries, 5, 2);
        assertNotSame(first, updated);
        var profile = updated.getFirst().profiles().get("station");
        assertEquals("updated", profile.description());
        assertEquals(List.of("indoor"), profile.tags());
        assertEquals(5, profile.minPlayers());
        assertEquals(12, profile.maxPlayers());
    }

    @Test void replacingImageAtSamePathInvalidatesPacketsWithoutAConfigRevisionChange() throws Exception {
        Object server = new Object();
        MapVoteEntry entry = entry();
        entry.profile.previewPath = "previews/" + MapVoteProfileStore.escapeId("station") + ".png";
        var entries = Map.of("station", entry);
        var context = new MapVoteProfileStore.UploadContext(directory, true, Set.of());
        byte[] red = png(0xffff0000);
        byte[] blue = png(0xff0000ff);
        assertTrue(MapVoteProfileStore.saveUploadedPreview(context, "station", "", red).success());
        assertArrayEquals(red, load(server, directory, entries, 4, 0).getFirst().profiles().get("station").previewBytes());

        assertTrue(MapVoteProfileStore.saveUploadedPreview(context, "station", "", blue).success());
        assertArrayEquals(blue, load(server, directory, entries, 4, 1).getFirst().profiles().get("station").previewBytes());
    }

    @Test void anotherServerCannotReusePacketsEvenWithSamePathIdsAndRevision() {
        Object firstServer = new Object();
        Object nextServer = new Object();
        MapVoteEntry entry = entry();
        entry.profile.description = "first world";
        var entries = Map.of("station", entry);
        assertEquals("first world", load(firstServer, directory, entries, 4, 0).getFirst().profiles().get("station").description());
        entry.profile.description = "reopened world";
        assertEquals("reopened world", load(nextServer, directory, entries, 4, 1).getFirst().profiles().get("station").description());
    }

    @Test void directoryAndMapSetChangesAndTtlStillRefreshPackets() {
        Object server = new Object();
        MapVoteEntry entry = entry();
        entry.profile.description = "first";
        var entries = Map.of("station", entry);
        var first = load(server, directory, entries, 4, 0);
        entry.profile.description = "second";
        assertEquals("second", load(server, directory.resolve("other"), entries, 4, 1).getFirst().profiles().get("station").description());
        var expanded = load(server, directory, Map.of("station", entry, "depot", entry()), 4, 2);
        assertEquals(Set.of("station", "depot"), expanded.getFirst().profiles().keySet());
        var cached = load(server, directory, entries, 4, 3);
        assertNotSame(first, cached);
        assertSame(cached, load(server, directory, entries, 4, 4));
        entry.profile.description = "external update";
        var expired = load(server, directory, entries, 4, TimeUnit.SECONDS.toNanos(30) + 3);
        assertEquals("external update", expired.getFirst().profiles().get("station").description());
    }

    private static MapVoteEntry entry() {
        MapVoteEntry entry = MapVoteEntry.createDefault();
        entry.profile = new MapVoteProfileSettings();
        return entry;
    }

    private static List<MapVoteProfilePayload> load(Object owner, Path base,
            Map<String, MapVoteEntry> entries, long revision, long now) {
        return MapVoteProfileStore.loadProfileFragments(owner, base, entries, revision, now);
    }

    private static byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }
}
