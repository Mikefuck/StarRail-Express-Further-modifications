package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 整合包预置目录的索引语义：**只认内容哈希，不认文件名**。
 */
class SceneSeedIndexTest {

    @Test
    void oversizedSeedIsSkippedWithoutReadingItIntoMemory(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("oversized.hscene");
        try (java.io.RandomAccessFile oversized = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            oversized.setLength((long) SceneAssetCodec.MAX_COMPRESSED_BYTES + 1);
        }
        SceneSeedIndex index = new SceneSeedIndex(dir.toFile());
        awaitReady(index);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] zeros = new byte[1024 * 1024];
        for (int i = 0; i < 64; i++) digest.update(zeros);
        digest.update((byte) 0);
        assertNull(index.find(java.util.HexFormat.of().formatHex(digest.digest())),
                "超限种子不能被扫描器登记");
        assertTrue(Files.exists(file), "种子文件必须保留");
    }

    private static void awaitReady(SceneSeedIndex index) throws InterruptedException {
        index.ensureScannedAsync();
        for (int i = 0; i < 100 && !index.isReady(); i++) {
            Thread.sleep(10);
        }
        assertTrue(index.isReady(), "扫描应当很快结束");
    }

    @Test
    void findsFileByContentHash(@TempDir Path dir) throws Exception {
        byte[] payload = "scene-bytes".getBytes();
        String hash = SceneAssetCodec.calculateSha256(payload);
        Files.write(dir.resolve("map1.hscene"), payload);

        SceneSeedIndex index = new SceneSeedIndex(dir.toFile());
        assertNull(index.find(hash), "扫描完成前不许命中——宁可去下载也不要卡住加载");
        awaitReady(index);

        File found = index.find(hash);
        assertNotNull(found);
        assertEquals("map1.hscene", found.getName());
        assertEquals(hash, SceneAssetCodec.calculateSha256(Files.readAllBytes(found.toPath())));
    }

    @Test
    void ignoresFileNameEvenWhenItLooksLikeAHash(@TempDir Path dir) throws Exception {
        String liedName = "a".repeat(64) + ".hscene";
        Files.write(dir.resolve(liedName), "not-that-hash".getBytes());

        SceneSeedIndex index = new SceneSeedIndex(dir.toFile());
        awaitReady(index);

        assertNull(index.find("a".repeat(64)), "文件名永远不作数");
        assertNotNull(index.find(SceneAssetCodec.calculateSha256("not-that-hash".getBytes())),
                "但它的真实内容哈希应当被登记");
    }

    @Test
    void rejectsNonSceneFilesAndEmptyDirectories(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("readme.txt"), "hello".getBytes());
        Files.write(dir.resolve("map.hscene"), "real".getBytes());

        SceneSeedIndex index = new SceneSeedIndex(dir.toFile());
        awaitReady(index);

        assertNull(index.find(SceneAssetCodec.calculateSha256("hello".getBytes())),
                "只登记 .hscene");
        assertNotNull(index.find(SceneAssetCodec.calculateSha256("real".getBytes())));
    }

    @Test
    void missingDirectoryIsNotAnError(@TempDir Path dir) throws Exception {
        SceneSeedIndex index = new SceneSeedIndex(new File(dir.toFile(), "nope"));
        awaitReady(index);
        assertNull(index.find("b".repeat(64)));
    }

    @Test
    void rejectedHashStaysRejected(@TempDir Path dir) throws Exception {
        byte[] payload = "seed".getBytes();
        String hash = SceneAssetCodec.calculateSha256(payload);
        Files.write(dir.resolve("bg.hscene"), payload);

        SceneSeedIndex index = new SceneSeedIndex(dir.toFile());
        awaitReady(index);
        assertNotNull(index.find(hash));

        index.reject(hash);
        assertNull(index.find(hash), "内容对不上的文件不该每次加载都再读一遍");
    }
}
