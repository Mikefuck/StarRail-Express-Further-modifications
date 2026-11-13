package com.habitrain.core.scene.server;

import com.habitrain.core.api.spi.SceneAssetBridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.persist.AtomicJsonFiles;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
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
public final class SceneAssetStore implements SceneAssetBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAssetStore.class.getSimpleName());
    private static final String DIR_NAME = "habitrain_scene_assets";
    private static final String INDEX_FILE_NAME = "index.json";
    private static final String STAGING_DIR_NAME = "staging";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final SceneAssetStore INSTANCE = new SceneAssetStore();

    public static SceneAssetStore getInstance() {
        return INSTANCE;
    }

    record StorageLocation(File storage, File staging) {}
    private volatile StorageLocation location;
    private long indexRevision;

    StorageLocation currentLocation() { return location; }
    boolean isCurrent(StorageLocation expected) { return location == expected; }

    private volatile File storageDir;
    private volatile File stagingDir;
    private final Map<String, SceneAssetDescriptor> mapIndex = new ConcurrentHashMap<>();
    // Deleted from the published index, but still downloadable by scenes that already use this hash.
    private final Map<String, SceneAssetDescriptor> retiredAssets = new ConcurrentHashMap<>();

    private SceneAssetStore() {}

    /**
     * 绑定世界存档目录。
     */
    public synchronized void bindWorld(File worldDirectory) {
        collectRetiredAssets(Set.of());
        indexRevision++;
        if (worldDirectory == null) {
            this.location = null;
            this.storageDir = null;
            this.stagingDir = null;
            this.mapIndex.clear();
            return;
        }
        File storage = new File(worldDirectory, DIR_NAME);
        File staging = new File(storage, STAGING_DIR_NAME);
        if (!storage.exists()) {
            storage.mkdirs();
        }
        if (!staging.exists()) {
            staging.mkdirs();
        }
        this.location = new StorageLocation(storage, staging);
        this.storageDir = storage;
        this.stagingDir = staging;
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
        File storage = this.storageDir;
        if (storage == null || !storage.exists()) return;

        File indexFile = new File(storage, INDEX_FILE_NAME);
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
        File storage = this.storageDir;
        if (storage == null) return false;
        File indexFile = new File(storage, INDEX_FILE_NAME);
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
        File storage = this.storageDir;
        if (storage == null || compressedBytes == null || descriptor == null || !descriptor.isValid()
                || compressedBytes.length != descriptor.compressedSize()) {
            return false;
        }
        String normalizedMapKey = normalizeMapKey(mapKey);
        String sha256 = descriptor.sha256().toLowerCase(Locale.ROOT);
        if (!SceneAssetDescriptor.isValidSha256(sha256)) {
            return false;
        }

        // 先校验哈希再落盘，防止不合法字节写入磁盘
        String verifyHash = SceneAssetCodec.calculateSha256(compressedBytes);
        if (!verifyHash.equalsIgnoreCase(sha256)) {
            LOGGER.error("资产写盘校验失败: 期望={}, 实际={}", sha256, verifyHash);
            return false;
        }

        try {
            if (!storage.exists()) storage.mkdirs();

            File targetFile = new File(storage, sha256 + ".hscene");
            File tempFile = new File(storage, sha256 + ".tmp." + System.nanoTime());

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(compressedBytes);
                fos.flush();
                fos.getFD().sync();
            }

            try {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            SceneAssetDescriptor previous = mapIndex.put(normalizedMapKey, descriptor);
            if (!saveIndex()) {
                if (previous == null) mapIndex.remove(normalizedMapKey);
                else mapIndex.put(normalizedMapKey, previous);
                return false;
            }
            // 直接覆盖发布时废弃旧增量补丁，并回收不再被任何资产键引用的旧 .hscene 文件
            SceneDeltaStore.getInstance().discard(normalizedMapKey);
            if (previous != null && !previous.sha256().equalsIgnoreCase(sha256)) {
                deleteUnreferencedAssetFile(previous.sha256());
            }
            indexRevision++;
            LOGGER.info("成功发布移动场景资产: mapKey={}, sha256={}, 大小={} bytes",
                    normalizedMapKey, descriptor.shortHash(), compressedBytes.length);
            return true;
        } catch (Exception e) {
            LOGGER.error("保存场景资产失败: mapKey=" + normalizedMapKey, e);
            return false;
        }
    }

    /** Writes a diagnostic asset without touching the live descriptor index or holding the store monitor during IO. */
    public boolean stageAsset(String stagingId, byte[] compressedBytes,
                              SceneAssetDescriptor descriptor) {
        return stageAsset(currentLocation(), stagingId, compressedBytes, descriptor);
    }

    boolean stageAsset(StorageLocation owner, String stagingId, byte[] compressedBytes, SceneAssetDescriptor descriptor) {
        File staging = owner == null ? null : owner.staging();
        if (staging == null || !isValidStagingId(stagingId) || compressedBytes == null
                || descriptor == null || !descriptor.isValid()
                || compressedBytes.length != descriptor.compressedSize()) {
            return false;
        }
        try {
            if (!staging.exists()) Files.createDirectories(staging.toPath());
            String verifyHash = SceneAssetCodec.calculateSha256(compressedBytes);
            if (!verifyHash.equalsIgnoreCase(descriptor.sha256())) {
                LOGGER.error("暂存资产哈希校验失败: stagingId={}, expected={}, actual={}",
                        stagingId, descriptor.sha256(), verifyHash);
                return false;
            }
            Path target = stagingPath(staging, stagingId);
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

    /** Prepared files belong to one binding/revision and cannot be committed after a rebind. */
    static final class PreparedPromotion implements AutoCloseable {
        final StorageLocation owner;
        final long revision;
        final String stagingId, mapKey;
        final SceneAssetDescriptor descriptor, previous;
        final ScenePreparedIndex index;
        PreparedPromotion(StorageLocation owner, long revision, String stagingId, String mapKey,
                          SceneAssetDescriptor descriptor, SceneAssetDescriptor previous, ScenePreparedIndex index) {
            this.owner = owner; this.revision = revision; this.stagingId = stagingId; this.mapKey = mapKey;
            this.descriptor = descriptor; this.previous = previous; this.index = index;
        }
        @Override public void close() throws IOException { index.close(); }
    }

    /** Hash/copy/fsync/JSON preparation runs outside the store and staging-service monitors. */
    PreparedPromotion preparePromotion(String stagingId, String mapKey, SceneAssetDescriptor descriptor) {
        return preparePromotion(currentLocation(), stagingId, mapKey, descriptor);
    }

    PreparedPromotion preparePromotion(StorageLocation expectedOwner, String stagingId, String mapKey, SceneAssetDescriptor descriptor) {
        StorageLocation owner;
        long revision;
        Map<String, SceneAssetDescriptor> before;
        synchronized (this) {
            if (location != expectedOwner) return null;
            owner = location; revision = indexRevision; before = new LinkedHashMap<>(mapIndex);
        }
        if (owner == null || !isValidStagingId(stagingId) || descriptor == null || !descriptor.isValid()) return null;
        String key = normalizeMapKey(mapKey);
        String sha = descriptor.sha256().toLowerCase(Locale.ROOT);
        Path staged = stagingPath(owner.staging(), stagingId);
        try {
            byte[] bytes = Files.readAllBytes(staged);
            if (bytes.length != descriptor.compressedSize()
                    || !SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(sha)) return null;
            SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(bytes);
            if (decoded.sections.isEmpty() || decoded.sections.size() != descriptor.sectionCount()
                    || decoded.dataVersion != descriptor.dataVersion()
                    || !decoded.fingerprint.equals(descriptor.fingerprint())) return null;
            Path live = owner.storage().toPath().resolve(sha + ".hscene");
            if (!Files.isRegularFile(live)) {
                Path temp = Files.createTempFile(owner.storage().toPath(), ".scene-promote-", ".tmp");
                try {
                    Files.write(temp, bytes);
                    try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    ScenePreparedIndex.move(temp, live);
                } finally { Files.deleteIfExists(temp); }
            } else if (!SceneAssetCodec.calculateSha256(Files.readAllBytes(live)).equalsIgnoreCase(sha)) {
                return null;
            }
            JsonObject oldIndex = descriptorJson(before);
            Map<String, SceneAssetDescriptor> after = new LinkedHashMap<>(before);
            after.put(key, descriptor);
            ScenePreparedIndex index = ScenePreparedIndex.prepare(owner.storage().toPath().resolve(INDEX_FILE_NAME),
                    oldIndex, descriptorJson(after), GSON);
            return new PreparedPromotion(owner, revision, stagingId, key, descriptor, before.get(key), index);
        } catch (Exception failure) {
            LOGGER.error("准备暂存场景资产发布失败: stagingId=" + stagingId, failure);
            return null;
        }
    }

    private static JsonObject descriptorJson(Map<String, SceneAssetDescriptor> entries) {
        JsonObject json = new JsonObject();
        entries.forEach((key, descriptor) -> json.add(key, descriptor.toJson()));
        return json;
    }

    /** Caller may hold a lifecycle lock: only renames and in-memory publication are allowed here. */
    synchronized boolean commitPromotion(PreparedPromotion prepared) {
        if (prepared == null || location != prepared.owner || indexRevision != prepared.revision) return false;
        try {
            prepared.index.commit();
            mapIndex.put(prepared.mapKey, prepared.descriptor);
            indexRevision++;
            return true;
        } catch (IOException failure) {
            LOGGER.error("提交场景资产索引失败；旧 descriptor 保持不变", failure);
            return false;
        }
    }

    void finishPromotion(PreparedPromotion prepared) {
        discardStagedAsset(prepared.owner, prepared.stagingId);
        synchronized (this) {
            if (location == prepared.owner && prepared.previous != null
                    && !prepared.previous.sha256().equalsIgnoreCase(prepared.descriptor.sha256())) {
                deleteUnreferencedAssetFile(prepared.previous.sha256());
            }
        }
    }

    public boolean promoteStagedAsset(String stagingId, String mapKey, SceneAssetDescriptor descriptor) {
        try (PreparedPromotion prepared = preparePromotion(stagingId, mapKey, descriptor)) {
            if (!commitPromotion(prepared)) return false;
            finishPromotion(prepared);
            return true;
        } catch (IOException failure) {
            LOGGER.error("清理场景发布临时文件失败", failure);
            return false;
        }
    }

    public boolean discardStagedAsset(String stagingId) {
        return discardStagedAsset(currentLocation(), stagingId);
    }

    boolean discardStagedAsset(StorageLocation owner, String stagingId) {
        File staging = owner == null ? null : owner.staging();
        if (staging == null || !isValidStagingId(stagingId)) return false;
        try {
            Path stagedPath = stagingPath(staging, stagingId);
            Files.deleteIfExists(stagedPath.resolveSibling(stagedPath.getFileName() + ".tmp"));
            Files.deleteIfExists(stagedPath.resolveSibling(stagingId.toLowerCase(Locale.ROOT) + ".hpatch.tmp"));
            // 顺带清掉同一暂存会话可能带着的增量补丁，避免丢弃/过期后留一个孤儿文件。
            Files.deleteIfExists(stagedPath.resolveSibling(stagingId.toLowerCase(Locale.ROOT) + ".hpatch"));
            return Files.deleteIfExists(stagedPath);
        } catch (IOException e) {
            LOGGER.warn("删除暂存场景资产失败: stagingId={}", stagingId, e);
            return false;
        }
    }

    public File getStagingAssetFile(String stagingId) {
        File staging = this.stagingDir;
        if (staging == null || !isValidStagingId(stagingId)) return null;
        File file = stagingPath(staging, stagingId).toFile();
        return file.isFile() ? file : null;
    }

    /** Deletes orphan staging files. Never traverses outside the dedicated staging directory. */
    public int discardAllStagingFiles() {
        File staging = this.stagingDir;
        if (staging == null || !staging.isDirectory()) return 0;
        File[] files = staging.listFiles(File::isFile);
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
        File file = assetPath(sha256);
        if (file == null) return null;
        return file.exists() ? file : null;
    }

    /** Path resolution only; existence and size are checked by the transfer IO worker. */
    File assetPath(String sha256) {
        File storage = this.storageDir;
        if (storage == null || !SceneAssetDescriptor.isValidSha256(sha256)) return null;
        return new File(storage, sha256.toLowerCase(Locale.ROOT) + ".hscene");
    }

    private static Path stagingPath(File stagingDir, String stagingId) {
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
        return mapKey == null || mapKey.isBlank() ? com.habitrain.core.api.scene.SceneMotionSettings.DEFAULT_MAP_KEY : mapKey.trim();
    }

    private void deleteUnreferencedAssetFile(String sha256) {
        File storage = this.storageDir;
        if (storage == null || !SceneAssetDescriptor.isValidSha256(sha256)) return;
        if (retiredAssets.containsKey(sha256)) return;
        for (SceneAssetDescriptor desc : mapIndex.values()) {
            if (desc != null && desc.sha256().equalsIgnoreCase(sha256)) {
                return;
            }
        }
        File file = new File(storage, sha256.trim().toLowerCase(Locale.ROOT) + ".hscene");
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            LOGGER.warn("清理未引用的旧场景资产文件失败: {}", file, e);
        }
    }

    public SceneAssetDescriptor getDescriptor(String mapKey) {
        return mapIndex.get(normalizeMapKey(mapKey));
    }

    /** Resolve the exact asset already named in a running scene, even after configuration removal. */
    SceneAssetDescriptor getRuntimeDescriptor(String mapKey, String sha256) {
        if (!SceneAssetDescriptor.isValidSha256(sha256)) return null;
        SceneAssetDescriptor published = getDescriptor(mapKey);
        return published != null && sha256.equals(published.sha256()) ? published : retiredAssets.get(sha256);
    }

    boolean hasRetiredAssets() {
        return !retiredAssets.isEmpty();
    }

    synchronized void collectRetiredAssets(Set<String> activeHashes) {
        for (String hash : retiredAssets.keySet()) {
            if (activeHashes.contains(hash)) continue;
            retiredAssets.remove(hash);
            deleteUnreferencedAssetFile(hash);
        }
    }

    public Map<String, SceneAssetDescriptor> getAllDescriptors() {
        return Collections.unmodifiableMap(mapIndex);
    }

    public synchronized void deleteAsset(String mapKey) {
        removeAsset(mapKey, false);
    }

    public synchronized void deleteConfiguredAsset(String mapKey) {
        removeAsset(mapKey, true);
    }

    private void removeAsset(String mapKey, boolean retainForRuntime) {
        if (mapKey == null) return;
        String normalizedMapKey = normalizeMapKey(mapKey);
        SceneAssetDescriptor removed = mapIndex.remove(normalizedMapKey);
        if (removed != null) {
            if (!saveIndex()) {
                mapIndex.put(normalizedMapKey, removed);
                LOGGER.warn("无法保存场景资产删除，保留原资产: {}", normalizedMapKey);
                return;
            }
            if (retainForRuntime) {
                retiredAssets.put(removed.sha256(), removed);
            } else {
                deleteUnreferencedAssetFile(removed.sha256());
            }
        }
        indexRevision++;
        // 地图都不在了，相对它的增量补丁也就没有意义了。
        SceneDeltaStore.getInstance().discard(normalizedMapKey);
    }
}
