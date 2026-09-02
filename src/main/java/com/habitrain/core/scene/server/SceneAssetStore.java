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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final SceneAssetStore INSTANCE = new SceneAssetStore();

    public static SceneAssetStore getInstance() {
        return INSTANCE;
    }

    private File storageDir;
    private final Map<String, SceneAssetDescriptor> mapIndex = new ConcurrentHashMap<>();

    private SceneAssetStore() {}

    /**
     * 绑定世界存档目录。
     */
    public synchronized void bindWorld(File worldDirectory) {
        if (worldDirectory == null) {
            this.storageDir = null;
            this.mapIndex.clear();
            return;
        }
        this.storageDir = new File(worldDirectory, DIR_NAME);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        loadIndex();
    }

    public File getStorageDir() {
        return storageDir;
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
