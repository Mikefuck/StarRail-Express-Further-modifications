package com.habitrain.core.scene.server;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SceneAssetStagingStoreTest {
    @TempDir Path tempDir;

    @AfterEach
    void resetSingletons() {
        SceneTransferService.getInstance().shutdown();
        SceneAssetStore.getInstance().bindWorld(null);
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

    private record Asset(byte[] bytes, SceneAssetDescriptor descriptor) {}
}
