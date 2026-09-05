package com.habitrain.core.scene.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.persist.AtomicJsonFiles;
import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端场景资产存储管理器（管理 world/habitrain_scene_assets/ 下的 .hscene 与 index.json）。
 */
public final class SceneAssetStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAssetStore.class.getSimpleName());
    private static final String DIR_NAME = "habitrain_scene_assets";
    private static final String INDEX_FILE_NAME = "index.json";
    private static final String STAGING_DIR_NAME = "staging";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final SceneAssetStore INSTANCE = new SceneAssetStore();

    public static SceneAssetStore getInstance() {
        return INSTANCE;
    }

    private File storageDir;
    private File stagingDir;
    private final Map<String, SceneAssetDescriptor> mapIndex = new ConcurrentHashMap<>();

    private SceneAssetStore() {}

    /**
     * 绑定世界存档目录。
     */
    public synchronized void bindWorld(File worldDirectory) {
        if (worldDirectory == null) {
            this.storageDir = null;
            this.stagingDir = null;
            this.mapIndex.clear();
            return;
        }
        this.storageDir = new File(worldDirectory, DIR_NAME);
        this.stagingDir = new File(storageDir, STAGING_DIR_NAME);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        if (!stagingDir.exists()) {
            stagingDir.mkdirs();
        }
        // Staging sessions are intentionally memory-only. A server restart can never
        // promote an unauthenticated leftover from the previous process.
        discardAllStagingFiles();
        loadIndex();
    }

    public File getStorageDir() {
        return storageDir;
    }

    public File getStagingDir() {
        return stagingDir;
    }

    private synchronized void loadIndex() {
        mapIndex.clear();
        if (storageDir == null || !storageDir.exists()) return;

        File indexFile = new File(storageDir, INDEX_FILE_NAME);
        if (!indexFile.exists()) return;

        try {
            AtomicJsonFiles.JsonLoad<JsonObject> load = AtomicJsonFiles.readJson(indexFile.toPath(), JsonObject.class, GSON);
            if (load.ok() && load.value() != null) {
                JsonObject root = load.value();
                for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        SceneAssetDescriptor desc = SceneAssetDescriptor.fromJson(e.getValue().getAsJsonObject());
                        if (desc.isValid()) {
                            mapIndex.put(e.getKey(), desc);
                        }
                    }
                }
            }
            LOGGER.info("已加载移动场景资产索引: {} 个地图资产", mapIndex.size());
        } catch (Exception e) {
            LOGGER.error("读取场景资产 index.json 失败", e);
        }
    }

    private synchronized boolean saveIndex() {
        if (storageDir == null) return false;
        File indexFile = new File(storageDir, INDEX_FILE_NAME);
        JsonObject root = new JsonObject();
        for (Map.Entry<String, SceneAssetDescriptor> e : mapIndex.entrySet()) {
            root.add(e.getKey(), e.getValue().toJson());
        }
        return AtomicJsonFiles.writeJson(indexFile.toPath(), root, GSON, true);
    }

    /**
     * 原子保存新生成的资产文件并更新索引。
     */
    public synchronized boolean saveAsset(String mapKey, byte[] compressedBytes, SceneAssetDescriptor descriptor) {
        if (storageDir == null || compressedBytes == null || descriptor == null || !descriptor.isValid()) {
            return false;
        }
        if (mapKey == null || mapKey.isBlank()) {
            mapKey = "__default__";
        }

        try {
            if (!storageDir.exists()) storageDir.mkdirs();

            String sha256 = descriptor.sha256();
            File targetFile = new File(storageDir, sha256 + ".hscene");
            File tempFile = new File(storageDir, sha256 + ".tmp." + System.currentTimeMillis());

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(compressedBytes);
                fos.flush();
            }

            // 校验刚写盘的文件 SHA-256
            String verifyHash = SceneAssetCodec.calculateSha256(compressedBytes);
            if (!verifyHash.equalsIgnoreCase(sha256)) {
                tempFile.delete();
                LOGGER.error("资产写盘校验失败: 期望={}, 实际={}", sha256, verifyHash);
                return false;
            }

            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            mapIndex.put(mapKey, descriptor);
            saveIndex();
            LOGGER.info("成功发布移动场景资产: mapKey={}, sha256={}, 大小={} bytes", mapKey, descriptor.shortHash(), compressedBytes.length);
            return true;
        } catch (Exception e) {
            LOGGER.error("保存场景资产失败: mapKey=" + mapKey, e);
            return false;
        }
    }

    /** Writes a diagnostic asset without touching the live descriptor index. */
    public synchronized boolean stageAsset(String stagingId, byte[] compressedBytes,
                                           SceneAssetDescriptor descriptor) {
        if (stagingDir == null || !isValidStagingId(stagingId) || compressedBytes == null
                || descriptor == null || !descriptor.isValid()
                || compressedBytes.length != descriptor.compressedSize()) {
            return false;
        }
        try {
            if (!stagingDir.exists()) Files.createDirectories(stagingDir.toPath());
            String verifyHash = SceneAssetCodec.calculateSha256(compressedBytes);
            if (!verifyHash.equalsIgnoreCase(descriptor.sha256())) {
                LOGGER.error("暂存资产哈希校验失败: stagingId={}, expected={}, actual={}",
                        stagingId, descriptor.sha256(), verifyHash);
                return false;
            }
            Path target = stagingPath(stagingId);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.deleteIfExists(temp);
            try (FileOutputStream fos = new FileOutputStream(temp.toFile())) {
                fos.write(compressedBytes);
                fos.flush();
                fos.getFD().sync();
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("写入暂存场景资产失败: stagingId=" + stagingId, e);
            return false;
        }
    }

    /**
     * Promotes a verified staging file and changes the live descriptor only after the
     * content-addressed file is durable and index.json was atomically replaced.
     */
    public synchronized boolean promoteStagedAsset(String stagingId, String mapKey,
                                                   SceneAssetDescriptor descriptor) {
        if (storageDir == null || !isValidStagingId(stagingId) || descriptor == null
                || !descriptor.isValid()) return false;
        String normalizedMapKey = normalizeMapKey(mapKey);
        Path staged = stagingPath(stagingId);
        if (!Files.isRegularFile(staged)) return false;
        try {
            byte[] bytes = Files.readAllBytes(staged);
            if (bytes.length != descriptor.compressedSize()
                    || !SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(descriptor.sha256())) {
                LOGGER.error("拒绝提升损坏的暂存资产: stagingId={}", stagingId);
                return false;
            }

            Path live = storageDir.toPath().resolve(descriptor.sha256() + ".hscene");
            if (!Files.isRegularFile(live)) {
                Path temp = live.resolveSibling(live.getFileName() + ".promote.tmp");
                Files.deleteIfExists(temp);
                Files.copy(staged, temp, StandardCopyOption.REPLACE_EXISTING);
                try (var channel = java.nio.channels.FileChannel.open(temp,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(temp, live, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, live, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                byte[] liveBytes = Files.readAllBytes(live);
                if (!SceneAssetCodec.calculateSha256(liveBytes).equalsIgnoreCase(descriptor.sha256())) {
                    LOGGER.error("内容寻址资产文件与文件名哈希不符，拒绝提升: {}", live);
                    return false;
                }
            }

            SceneAssetDescriptor previous = mapIndex.put(normalizedMapKey, descriptor);
            if (!saveIndex()) {
                if (previous == null) mapIndex.remove(normalizedMapKey);
                else mapIndex.put(normalizedMapKey, previous);
                LOGGER.error("正式资产索引原子替换失败，已恢复旧 descriptor: mapKey={}", normalizedMapKey);
                return false;
            }
            Files.deleteIfExists(staged);
            LOGGER.info("暂存场景资产已原子提升: mapKey={}, stagingId={}, sha256={}",
                    normalizedMapKey, stagingId, descriptor.shortHash());
            return true;
        } catch (Exception e) {
            LOGGER.error("提升暂存场景资产失败: stagingId=" + stagingId, e);
            return false;
        }
    }

    public synchronized boolean discardStagedAsset(String stagingId) {
        if (stagingDir == null || !isValidStagingId(stagingId)) return false;
        try {
            return Files.deleteIfExists(stagingPath(stagingId));
        } catch (IOException e) {
            LOGGER.warn("删除暂存场景资产失败: stagingId={}", stagingId, e);
            return false;
        }
    }

    public synchronized File getStagingAssetFile(String stagingId) {
        if (stagingDir == null || !isValidStagingId(stagingId)) return null;
        File file = stagingPath(stagingId).toFile();
        return file.isFile() ? file : null;
    }

    /** Deletes orphan staging files. Never traverses outside the dedicated staging directory. */
    public synchronized int discardAllStagingFiles() {
        if (stagingDir == null || !stagingDir.isDirectory()) return 0;
        File[] files = stagingDir.listFiles(File::isFile);
        if (files == null) return 0;
        int removed = 0;
        for (File file : files) {
            try {
                if (Files.deleteIfExists(file.toPath())) removed++;
            } catch (IOException e) {
                LOGGER.warn("清理孤立暂存资产失败: {}", file, e);
            }
        }
        if (removed > 0) LOGGER.info("已清理 {} 个孤立暂存场景文件", removed);
        return removed;
    }

    /**
     * 获取指定 SHA-256 对应的资产文件。
     */
    public File getAssetFile(String sha256) {
        if (storageDir == null || sha256 == null || sha256.length() != 64) return null;
        // 防路径穿越
        if (sha256.contains("/") || sha256.contains("\\") || sha256.contains("..")) return null;
        File file = new File(storageDir, sha256.toLowerCase() + ".hscene");
        return file.exists() ? file : null;
    }

    private Path stagingPath(String stagingId) {
        return stagingDir.toPath().resolve(stagingId.toLowerCase(Locale.ROOT) + ".hscene");
    }

    private static boolean isValidStagingId(String stagingId) {
        if (stagingId == null) return false;
        try {
            return UUID.fromString(stagingId).toString().equalsIgnoreCase(stagingId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String normalizeMapKey(String mapKey) {
        return mapKey == null || mapKey.isBlank() ? "__default__" : mapKey.trim();
    }

    public SceneAssetDescriptor getDescriptor(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) mapKey = "__default__";
        return mapIndex.get(mapKey);
    }

    public Map<String, SceneAssetDescriptor> getAllDescriptors() {
        return Collections.unmodifiableMap(mapIndex);
    }

    public synchronized void deleteAsset(String mapKey) {
        if (mapKey == null) return;
        SceneAssetDescriptor removed = mapIndex.remove(mapKey);
        if (removed != null) {
            saveIndex();
        }
    }
}
