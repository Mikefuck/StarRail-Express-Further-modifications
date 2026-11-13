package com.habitrain.core.scene.server;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SceneAssetStagingStoreTest {
    @TempDir Path tempDir;

    @org.junit.jupiter.api.BeforeEach
    void startService() {
        SceneTransferService.getInstance().start();
    }

    @AfterEach
    void resetSingletons() {
        SceneTransferService.getInstance().shutdown();
        SceneAssetStore.getInstance().bindWorld(null);
    }

    @Test
    void removingSceneConfigKeepsRunningAssetDownloadableUntilTheLastReferenceEnds() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset running = asset("minecraft:stone", 100L);
        Asset replacement = asset("minecraft:glass", 200L);
        assertTrue(store.saveAsset("map1", running.bytes, running.descriptor));
        String hash = running.descriptor.sha256();

        store.deleteConfiguredAsset("map1");
        assertNull(store.getDescriptor("map1"), "new scenes must not resolve the removed map asset");
        assertTrue(store.saveAsset("map1", replacement.bytes, replacement.descriptor));

        store.collectRetiredAssets(java.util.Set.of(hash));
        assertEquals(running.descriptor, store.getRuntimeDescriptor("map1", hash));
        assertNotNull(store.getAssetFile(hash), "late joiners must still be able to download the old scene");

        store.collectRetiredAssets(java.util.Set.of());
        assertNull(store.getRuntimeDescriptor("map1", hash));
        assertNull(store.getAssetFile(hash));
        assertEquals(replacement.descriptor, store.getDescriptor("map1"));
    }

    @Test
    void stagingDoesNotChangeLiveDescriptorUntilAtomicPromotion() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset oldAsset = asset("minecraft:stone", 100L);
        Asset stagedAsset = asset("minecraft:glass", 200L);
        assertTrue(store.saveAsset("map1", oldAsset.bytes, oldAsset.descriptor));

        String stagingId = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(stagingId, stagedAsset.bytes, stagedAsset.descriptor));
        assertEquals(oldAsset.descriptor, store.getDescriptor("map1"));

        assertTrue(store.promoteStagedAsset(stagingId, "map1", stagedAsset.descriptor));
        assertEquals(stagedAsset.descriptor, store.getDescriptor("map1"));
        assertNull(store.getStagingAssetFile(stagingId));
        assertNotNull(store.getAssetFile(stagedAsset.descriptor.sha256()));
    }

    @Test
    void invalidHashCannotReplacePreviousLiveAsset() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset oldAsset = asset("minecraft:stone", 100L);
        Asset stagedAsset = asset("minecraft:glass", 200L);
        assertTrue(store.saveAsset("map1", oldAsset.bytes, oldAsset.descriptor));
        String stagingId = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(stagingId, stagedAsset.bytes, stagedAsset.descriptor));

        SceneAssetDescriptor tampered = new SceneAssetDescriptor("0".repeat(64),
                stagedAsset.descriptor.uncompressedSize(), stagedAsset.descriptor.compressedSize(),
                stagedAsset.descriptor.sectionCount(), stagedAsset.descriptor.dataVersion(),
                stagedAsset.descriptor.fingerprint(), stagedAsset.descriptor.createdAt());
        assertFalse(store.promoteStagedAsset(stagingId, "map1", tampered));
        assertEquals(oldAsset.descriptor, store.getDescriptor("map1"));
        assertNotNull(store.getStagingAssetFile(stagingId));
    }

    @Test
    void restartBindingDeletesOrphanStagingFiles() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset stagedAsset = asset("minecraft:glass", 200L);
        String stagingId = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(stagingId, stagedAsset.bytes, stagedAsset.descriptor));
        assertNotNull(store.getStagingAssetFile(stagingId));

        store.bindWorld(tempDir.toFile());
        assertNull(store.getStagingAssetFile(stagingId));
    }

    @Test
    void stagingTransferAuthorizationIsPrivateToRequester() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset stagedAsset = asset("minecraft:glass", 200L);
        String stagingId = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(stagingId, stagedAsset.bytes, stagedAsset.descriptor));
        UUID requester = UUID.randomUUID();
        UUID ordinaryPlayer = UUID.randomUUID();

        SceneTransferService transfers = SceneTransferService.getInstance();
        transfers.authorizeStaging(requester, stagingId, stagedAsset.descriptor.sha256(),
                stagedAsset.descriptor.compressedSize());
        assertTrue(transfers.hasStagingAuthorization(requester, stagingId,
                stagedAsset.descriptor.sha256()));
        assertFalse(transfers.hasStagingAuthorization(ordinaryPlayer, stagingId,
                stagedAsset.descriptor.sha256()));

        transfers.revokeStaging(stagingId);
        assertFalse(transfers.hasStagingAuthorization(requester, stagingId,
                stagedAsset.descriptor.sha256()));
    }

    @Test void preparedFilesDoNotPublishUntilCommitAndBackupContainsPreviousIndex() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset old = asset("minecraft:stone", 1L), next = asset("minecraft:glass", 2L);
        assertTrue(store.saveAsset("map1", old.bytes, old.descriptor));
        String id = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(id, next.bytes, next.descriptor));
        try (var prepared = store.preparePromotion(id, "map1", next.descriptor)) {
            assertNotNull(prepared);
            assertEquals(old.descriptor, store.getDescriptor("map1"));
            assertTrue(store.commitPromotion(prepared));
            assertEquals(next.descriptor, store.getDescriptor("map1"));
            String backup = java.nio.file.Files.readString(store.getStorageDir().toPath().resolve("index.json.bak"));
            assertTrue(backup.contains(old.descriptor.sha256()));
            assertFalse(backup.contains(next.descriptor.sha256()));
            store.finishPromotion(prepared);
        }
        assertNull(store.getStagingAssetFile(id));
    }

    @Test void preparedPromotionCannotOverwriteAnInterveningIndexChange() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset next = asset("minecraft:glass", 2L), concurrent = asset("minecraft:stone", 3L);
        String id = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(id, next.bytes, next.descriptor));
        try (var prepared = store.preparePromotion(id, "map1", next.descriptor)) {
            assertNotNull(prepared);
            assertTrue(store.saveAsset("map2", concurrent.bytes, concurrent.descriptor));
            assertFalse(store.commitPromotion(prepared));
            assertNull(store.getDescriptor("map1"));
            assertEquals(concurrent.descriptor, store.getDescriptor("map2"));
        }
    }

    @Test void preparedPromotionIsRejectedAfterSameDirectoryRebind() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset next = asset("minecraft:glass", 2L);
        String id = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(id, next.bytes, next.descriptor));
        try (var prepared = store.preparePromotion(id, "map1", next.descriptor)) {
            assertNotNull(prepared);
            store.bindWorld(tempDir.toFile());
            assertFalse(store.commitPromotion(prepared));
            assertNull(store.getDescriptor("map1"));
        }
    }

    @Test void capturedStagingLocationCannotWriteOrDeleteAnotherWorld() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.resolve("old").toFile());
        var oldLocation = store.currentLocation();
        Asset next = asset("minecraft:glass", 2L);
        String id = UUID.randomUUID().toString();
        store.bindWorld(tempDir.resolve("new").toFile());
        assertTrue(store.stageAsset(id, next.bytes, next.descriptor));
        assertTrue(store.stageAsset(oldLocation, id, next.bytes, next.descriptor));
        assertTrue(store.discardStagedAsset(oldLocation, id));
        assertNotNull(store.getStagingAssetFile(id), "old cleanup must use its captured directory");
    }

    @Test void indexCommitFailureKeepsPreviousDescriptorAndPrimary() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset old = asset("minecraft:stone", 1L), next = asset("minecraft:glass", 2L);
        assertTrue(store.saveAsset("map1", old.bytes, old.descriptor));
        String id = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(id, next.bytes, next.descriptor));
        Path primary = store.getStorageDir().toPath().resolve("index.json");
        String previous = java.nio.file.Files.readString(primary);
        try (var prepared = store.preparePromotion(id, "map1", next.descriptor)) {
            Path backup = primary.resolveSibling("index.json.bak");
            java.nio.file.Files.deleteIfExists(backup);
            java.nio.file.Files.createDirectory(backup);
            assertFalse(store.commitPromotion(prepared));
            assertEquals(old.descriptor, store.getDescriptor("map1"));
            assertEquals(previous, java.nio.file.Files.readString(primary));
        }
    }

    @Test void deltaPreparationAndRemovalFollowTheCommittedAssetTarget() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        SceneDeltaStore deltas = SceneDeltaStore.getInstance();
        store.bindWorld(tempDir.toFile());
        Asset old = asset("minecraft:stone", 1L), next = asset("minecraft:glass", 2L);
        assertTrue(store.saveAsset("map1", old.bytes, old.descriptor));
        String id = UUID.randomUUID().toString();
        assertTrue(store.stageAsset(id, next.bytes, next.descriptor));
        byte[] patch = com.habitrain.core.api.scene.asset.SceneAssetDelta.encode(
                com.habitrain.core.api.scene.asset.SceneAssetDelta.build(SceneAssetCodec.decode(old.bytes),
                        old.descriptor.sha256(), SceneAssetCodec.decode(next.bytes), next.descriptor.sha256()));
        String sha = SceneAssetCodec.calculateSha256(patch);
        var blob = new SceneDeltaStore.PatchBlob(old.descriptor.sha256(), next.descriptor.sha256(), sha, patch);
        assertTrue(deltas.stageDelta(id, blob));
        var info = new SceneDeltaStore.DeltaInfo(old.descriptor.sha256(), next.descriptor.sha256(), sha, patch.length, 1L);
        try (var asset = store.preparePromotion(id, "map1", next.descriptor);
             var delta = deltas.preparePublication(store.currentLocation(), "map1", info,
                     deltas.stagedPatchFile(id), next.descriptor.sha256())) {
            assertNotNull(delta);
            assertFalse(deltas.commitPublication(delta), "target asset has not been published yet");
            assertTrue(store.commitPromotion(asset));
            assertTrue(deltas.commitPublication(delta));
            assertEquals(info, deltas.get("map1"));
        }
        try (var removal = deltas.preparePublication(store.currentLocation(), "map1", null, null, next.descriptor.sha256())) {
            assertTrue(deltas.commitPublication(removal));
            assertNull(deltas.get("map1"));
        }
    }

    @Test void preparedDeltaCannotCommitAfterWorldReplacement() throws Exception {
        SceneAssetStore store = SceneAssetStore.getInstance();
        SceneDeltaStore deltas = SceneDeltaStore.getInstance();
        store.bindWorld(tempDir.resolve("old").toFile());
        Asset old = asset("minecraft:stone", 1L);
        assertTrue(store.saveAsset("map1", old.bytes, old.descriptor));
        try (var prepared = deltas.preparePublication(store.currentLocation(), "map1", null, null, old.descriptor.sha256())) {
            assertNotNull(prepared);
            store.bindWorld(tempDir.resolve("new").toFile());
            assertFalse(deltas.commitPublication(prepared));
            assertFalse(java.nio.file.Files.exists(store.getStorageDir().toPath().resolve("deltas/index.json")));
        }
    }

    private static Asset asset(String blockId, long createdAt) throws Exception {
        short[] indices = new short[4096];
        indices[0] = 1;
        SceneAssetCodec.SectionData section = new SceneAssetCodec.SectionData(0, 0, 0,
                List.of("minecraft:air", blockId), indices, new byte[2048], new byte[2048]);
        SceneAssetCodec.AssetData data = new SceneAssetCodec.AssetData(1, "minecraft:overworld",
                new SceneBounds(0, 0, 0, 16, 16, 16), "fingerprint", List.of(section));
        byte[] bytes = SceneAssetCodec.encode(data);
        SceneAssetDescriptor descriptor = new SceneAssetDescriptor(
                SceneAssetCodec.calculateSha256(bytes), SceneAssetCodec.estimateUncompressedSize(data),
                bytes.length, 1, 1, "fingerprint", createdAt);
        return new Asset(bytes, descriptor);
    }

    @Test void exportedSeedNamesAreDistinctStableAndManifestErrorsAreFailures() throws Exception {
        var store = SceneAssetStore.getInstance(); store.bindWorld(tempDir.toFile());
        var first = asset("minecraft:stone", 1);
        var second = asset("minecraft:glass", 2);
        assertTrue(store.saveAsset("a:b", first.bytes, first.descriptor));
        assertTrue(store.saveAsset("a/b", second.bytes, second.descriptor));
        assertTrue(store.saveAsset("CON", first.bytes, first.descriptor));
        var result = SceneExportService.exportAll();
        assertTrue(result.ok()); assertEquals(3, result.assets());
        var manifest = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                result.directory().toPath().resolve("manifest.json"))).getAsJsonObject();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var entry : manifest.entrySet()) {
            var value = entry.getValue().getAsJsonObject(); String file = value.get("file").getAsString();
            assertTrue(names.add(file.toLowerCase(java.util.Locale.ROOT)));
            assertEquals(value.get("sha256").getAsString(), SceneAssetCodec.calculateSha256(java.nio.file.Files.readAllBytes(result.directory().toPath().resolve(file))));
        }
        assertFalse(SceneExportService.export(SceneExportService.snapshot(), () -> false).ok());
        java.nio.file.Files.delete(result.directory().toPath().resolve("manifest.json"));
        java.nio.file.Files.createDirectory(result.directory().toPath().resolve("manifest.json"));
        assertFalse(SceneExportService.exportAll().ok());
    }

    private record Asset(byte[] bytes, SceneAssetDescriptor descriptor) {}
}
