package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 续传记录的磁盘契约：只有"对齐的、盘上确实存在的、且校验值齐备的"前缀才允许被续传。
 * 手工改过的元数据不能驱动任何分配或请求。
 */
class ScenePartialStoreTest {
    private static final String HASH = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String PREFIX_HASH = "ab".repeat(32);
    private static final long MIB = 1024L * 1024L;

    private static ScenePartialStore.PendingPart part(long total, long resumable) {
        return new ScenePartialStore.PendingPart(HASH, total, resumable, PREFIX_HASH, "fp", 1_000L);
    }

    private static void writeData(Path dir, String hash, int length) throws IOException {
        Files.write(dir.resolve(hash + ScenePartialStore.PART_SUFFIX), new byte[length]);
    }

    @Test
    void storeAndLoadRoundTrip(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 4 * 65536);

        assertTrue(store.store(part(8 * 65536, 4 * 65536)));

        ScenePartialStore.PendingPart loaded = store.load(HASH).orElseThrow();
        assertEquals(8 * 65536, loaded.totalSize());
        assertEquals(4 * 65536, loaded.resumableBytes());
        assertEquals(PREFIX_HASH, loaded.prefixSha256());
        assertEquals("fp", loaded.fingerprint());
    }

    @Test
    void missingMetadataMeansNotResumable(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 4096);
        assertTrue(store.load(HASH).isEmpty());
    }

    @Test
    void corruptOrWrongVersionMetadataIsIgnored(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        Files.writeString(store.metaFile(HASH), "{ this is not json", StandardCharsets.UTF_8);
        assertTrue(store.load(HASH).isEmpty(), "损坏的元数据只能退化，不能抛出去");

        Files.writeString(store.metaFile(HASH),
                "{\"version\":99,\"sha256\":\"" + HASH + "\",\"totalSize\":65536,"
                        + "\"resumableBytes\":65536,\"prefixSha256\":\"" + PREFIX_HASH + "\","
                        + "\"updatedAtMillis\":1}", StandardCharsets.UTF_8);
        assertTrue(store.load(HASH).isEmpty(), "版本不符一律作废");
    }

    @Test
    void misalignedResumePointIsRefused(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 4096);
        // 服务端只接受 65536 对齐的偏移，未对齐的值绝不能发出去换一条 OUT_OF_RANGE
        assertTrue(store.store(part(8 * 65536, 4096)));
        assertTrue(store.load(HASH).isEmpty());
    }

    @Test
    void totalSizeBeyondTheTransferCapIsRefused(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        assertTrue(store.store(new ScenePartialStore.PendingPart(
                HASH, 128L * MIB, 65536, PREFIX_HASH, "fp", 1L)));
        assertTrue(store.load(HASH).isEmpty(), "被改过的元数据不能驱动巨额分配");
    }

    @Test
    void dataFileLongerThanTheDeclaredTotalIsRefused(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 8 * 65536);
        assertTrue(store.store(part(4 * 65536, 65536)));
        assertTrue(store.load(HASH).isEmpty(), "孤儿/被篡改的文件不能当成续传来源");
    }

    @Test
    void dataFileShorterThanThePrefixIsRefused(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        assertTrue(store.store(part(8 * 65536, 4 * 65536)));
        assertTrue(store.load(HASH).isEmpty(), "连前缀都读不出来就无法校验");
    }

    @Test
    void zeroResumableBytesIsNotWorthResuming(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        assertTrue(store.store(part(8 * 65536, 0L)));
        assertTrue(store.load(HASH).isEmpty());
    }

    @Test
    void discardRemovesBothTheDataAndItsMetadata(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        store.store(part(8 * 65536, 65536));

        store.discard(HASH);

        assertFalse(Files.exists(store.dataFile(HASH)));
        assertFalse(Files.exists(store.metaFile(HASH)));
        assertTrue(store.load(HASH).isEmpty());
    }

    @Test
    void sweepRemovesLegacyDownloadLeftoversAndUnrecordedOrphans(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        // Phase B 之前的崩溃残留：<sha>.download.<millis>
        Files.write(dir.resolve(HASH + ".download.1700000000000"), new byte[4096]);
        // 有数据但没有元数据的孤儿
        writeData(dir, HASH_B, 4096);

        ScenePartialStore.SweepResult result = store.sweep(2_000L, 512L * MIB, 24L * 60L * 60L * 1000L);

        assertEquals(2, result.removed());
        assertFalse(Files.exists(dir.resolve(HASH + ".download.1700000000000")));
        assertFalse(Files.exists(dir.resolve(HASH_B + ScenePartialStore.PART_SUFFIX)));
        assertEquals(4096L * 2, result.freedBytes());
    }

    @Test
    void sweepExpiresOldPartials(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        store.store(part(4 * 65536, 65536));   // updatedAtMillis = 1000

        store.sweep(1_000L + 60L * 60L * 1000L + 1L, 512L * MIB, 60L * 60L * 1000L);

        assertFalse(Files.exists(store.dataFile(HASH)));
        assertFalse(Files.exists(store.metaFile(HASH)));
    }

    @Test
    void sweepEvictsTheOldestPartialsFirstWhenOverQuota(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        writeData(dir, HASH_B, 65536);
        store.store(new ScenePartialStore.PendingPart(HASH, 4L * 65536, 65536, PREFIX_HASH, "fp", 1_000L));
        store.store(new ScenePartialStore.PendingPart(HASH_B, 4L * 65536, 65536, PREFIX_HASH, "fp", 9_000L));

        // 配额只够放一份：先走的必须是最旧的那份
        ScenePartialStore.SweepResult result = store.sweep(10_000L, 65536L, 24L * 60L * 60L * 1000L);

        assertEquals(1, result.removed());
        assertTrue(store.load(HASH).isEmpty(), "最旧的先淘汰");
        assertTrue(store.load(HASH_B).isPresent());
    }

    @Test
    void sweepKeepsValidPartialsUnderQuota(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 65536);
        store.store(part(4L * 65536, 65536));

        ScenePartialStore.SweepResult result = store.sweep(2_000L, 512L * MIB, 24L * 60L * 60L * 1000L);

        assertEquals(0, result.removed());
        Optional<ScenePartialStore.PendingPart> kept = store.load(HASH);
        assertTrue(kept.isPresent(), "有效且未超配额的续传记录必须保留");
    }

    @Test
    void totalBytesAndHashesSeeTheDataFiles(@TempDir Path dir) throws IOException {
        ScenePartialStore store = new ScenePartialStore(dir);
        writeData(dir, HASH, 4096);
        writeData(dir, HASH_B, 2048);

        assertEquals(6144L, store.totalBytes());
        assertEquals(2, store.hashes().size());
    }
}
