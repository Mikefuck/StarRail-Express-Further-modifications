package com.habitrain.core.scene.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.persist.AtomicJsonFiles;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端增量补丁存储：{@code world/habitrain_scene_assets/deltas/<patchSha>.hpatch} + {@code deltas/index.json}。
 *
 * <p>与资产分开放的原因：补丁是"可选的加速件"，它的生命周期、清理与体积口径都和正式资产不同，
 * 混在 {@code <sha>.hscene} 里会让"这个文件到底是什么"变得说不清。</p>
 *
 * <p>元数据**不进** {@code SceneAssetDescriptor}：那个 descriptor 会随清单包下发、会参与
 * {@code equals}，还会被暂存/编辑器包下发的同 hash descriptor 覆盖；补丁信息挂在它身上会
 * 变成一个 last-writer-wins 的丢数据点。这里用独立的 {@code mapKey → DeltaInfo} 表。</p>
 */
public final class SceneDeltaStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneDeltaStore.class.getSimpleName());
    public static final String DIR_NAME = "deltas";
    private static final String INDEX_FILE_NAME = "index.json";
    private static final String PATCH_SUFFIX = ".hpatch";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HASH_PATTERN = "[0-9a-fA-F]{64}";

    private static final SceneDeltaStore INSTANCE = new SceneDeltaStore();

    public static SceneDeltaStore getInstance() {
        return INSTANCE;
    }

    /**
     * 一条补丁的元数据。
     *
     * @param baseSha256   补丁的底——客户端必须持有这份资产才能应用
     * @param targetSha256 补丁生成的目标资产 SHA-256（防直接覆盖资产后旧补丁与当前正式资产失配）
     * @param patchSha256  补丁文件自身的 SHA-256（同时也是分片传输的授权键与文件名）
     * @param patchBytes   补丁压缩后字节数
     */
    public record DeltaInfo(String baseSha256, String targetSha256, String patchSha256, long patchBytes, long createdAt) {
        public DeltaInfo {
            baseSha256 = normalize(baseSha256);
            targetSha256 = normalize(targetSha256);
            patchSha256 = normalize(patchSha256);
        }

        public DeltaInfo(String baseSha256, String patchSha256, long patchBytes, long createdAt) {
            this(baseSha256, "", patchSha256, patchBytes, createdAt);
        }

        public boolean isValid() {
            return baseSha256.matches(HASH_PATTERN)
                    && (targetSha256.isEmpty() || targetSha256.matches(HASH_PATTERN))
                    && patchSha256.matches(HASH_PATTERN)
                    && patchBytes > 0;
        }

        public boolean matchesTarget(String expectedTargetSha256) {
            String norm = normalize(expectedTargetSha256);
            if (!norm.matches(HASH_PATTERN)) return false;
            // Legacy entries without a target cannot prove that their patch produces this version.
            return targetSha256.equals(norm);
        }

        public String shortPatch() {
            return patchSha256.length() >= 12 ? patchSha256.substring(0, 12) : patchSha256;
        }
    }

    /** 暂存期产出的补丁：先落在 {@code staging/<stagingId>.hpatch}，发布时才搬进 deltas/。 */
    public record PatchBlob(String baseSha256, String targetSha256, String patchSha256, byte[] bytes) {
        public PatchBlob(String baseSha256, String patchSha256, byte[] bytes) {
            this(baseSha256, "", patchSha256, bytes);
        }

        public boolean isValid() {
            return baseSha256 != null && baseSha256.matches(HASH_PATTERN)
                    && (targetSha256 == null || targetSha256.isEmpty() || targetSha256.matches(HASH_PATTERN))
                    && patchSha256 != null && patchSha256.matches(HASH_PATTERN)
                    && bytes != null && bytes.length > 0;
        }
    }

    private volatile SceneAssetStore.StorageLocation boundLocation;
    private long indexRevision;
    private final Map<String, DeltaInfo> index = new ConcurrentHashMap<>();

    private SceneDeltaStore() {}

    private static String normalize(String hash) {
        return hash != null ? hash.trim().toLowerCase(Locale.ROOT) : "";
    }

    /** 补丁目录跟随 {@link SceneAssetStore} 的世界绑定，避免再多一处初始化顺序。 */
    private File deltaDir() {
        SceneAssetStore.StorageLocation owner = SceneAssetStore.getInstance().currentLocation();
        if (owner == null) return null;
        File root = owner.storage();
        if (owner != boundLocation) {
            synchronized (this) {
                if (owner != boundLocation) {
                    if (!SceneAssetStore.getInstance().isCurrent(owner)) return null;
                    boundLocation = owner;
                    indexRevision++;
                    index.clear();
                    loadIndex(new File(root, DIR_NAME));
                }
            }
        }
        return new File(root, DIR_NAME);
    }

    public File getDeltaDir() {
        return deltaDir();
    }

    private void loadIndex(File dir) {
        File indexFile = new File(dir, INDEX_FILE_NAME);
        AtomicJsonFiles.JsonLoad<JsonObject> load = AtomicJsonFiles.readJson(indexFile.toPath(), JsonObject.class, GSON);
        JsonObject root = load.value();
        if (root == null) return;
        for (String mapKey : root.keySet()) {
            if (!root.get(mapKey).isJsonObject()) continue;
            JsonObject entry = root.getAsJsonObject(mapKey);
            String base = entry.has("baseSha256") && entry.get("baseSha256").isJsonPrimitive()
                    ? entry.get("baseSha256").getAsString() : "";
            String target = entry.has("targetSha256") && entry.get("targetSha256").isJsonPrimitive()
                    ? entry.get("targetSha256").getAsString() : "";
            String patch = entry.has("patchSha256") && entry.get("patchSha256").isJsonPrimitive()
                    ? entry.get("patchSha256").getAsString() : "";
            long bytes = entry.has("patchBytes") && entry.get("patchBytes").isJsonPrimitive()
                    ? entry.get("patchBytes").getAsLong() : 0L;
            long created = entry.has("createdAt") && entry.get("createdAt").isJsonPrimitive()
                    ? entry.get("createdAt").getAsLong() : 0L;
            DeltaInfo info = new DeltaInfo(base, target, patch, bytes, created);
            SceneAssetDescriptor currentAsset = SceneAssetStore.getInstance().getDescriptor(mapKey);
            boolean matchesLive = currentAsset == null || !currentAsset.isValid()
                    || info.matchesTarget(currentAsset.sha256());
            File pFile = patchFile(info.patchSha256());
            if (info.isValid() && matchesLive && pFile != null && pFile.isFile()) {
                index.put(mapKey, info);
            }
        }
        if (!index.isEmpty()) {
            LOGGER.info("已加载场景增量补丁索引: {} 条", index.size());
        }
    }

    private synchronized boolean saveIndex() {
        File dir = deltaDir();
        if (dir == null) return false;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, DeltaInfo> e : index.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("baseSha256", e.getValue().baseSha256());
            if (!e.getValue().targetSha256().isEmpty()) {
                entry.addProperty("targetSha256", e.getValue().targetSha256());
            }
            entry.addProperty("patchSha256", e.getValue().patchSha256());
            entry.addProperty("patchBytes", e.getValue().patchBytes());
            entry.addProperty("createdAt", e.getValue().createdAt());
            root.add(e.getKey(), entry);
        }
        return AtomicJsonFiles.writeJson(new File(dir, INDEX_FILE_NAME).toPath(), root, GSON, true, true);
    }

    /** 某个 mapKey 当前可用的补丁；没有（或文件已不在）时返回 null。 */
    public DeltaInfo get(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) return null;
        File dir = deltaDir();
        if (dir == null) return null;
        DeltaInfo info = index.get(mapKey);
        if (info == null) return null;
        SceneAssetDescriptor live = SceneAssetStore.getInstance().getDescriptor(mapKey);
        if (live != null && !info.matchesTarget(live.sha256())) return null;
        return info;
    }

    /** 补丁文件路径（不做存在性检查，也不做任何 IO）。 */
    public File patchFile(String patchSha256) {
        File dir = deltaDir();
        String hash = normalize(patchSha256);
        if (dir == null || !hash.matches(HASH_PATTERN)) return null;
        return new File(dir, hash + PATCH_SUFFIX);
    }

    /**
     * 发布一枚补丁：把暂存文件搬进 deltas/、更新索引，并删掉同 mapKey 的上一枚补丁。
     *
     * <p>纯附加操作——返回 false 只意味着"这次不提供增量"，正式资产不受影响。</p>
     */
    static final class PreparedPublication implements AutoCloseable {
        final SceneAssetStore.StorageLocation owner;
        final long revision;
        final String mapKey, targetSha;
        final DeltaInfo info, previous;
        final ScenePreparedIndex preparedIndex;
        PreparedPublication(SceneAssetStore.StorageLocation owner, long revision, String mapKey, String targetSha,
                            DeltaInfo info, DeltaInfo previous, ScenePreparedIndex preparedIndex) {
            this.owner = owner; this.revision = revision; this.mapKey = mapKey;
            this.targetSha = targetSha; this.info = info; this.previous = previous; this.preparedIndex = preparedIndex;
        }
        @Override public void close() throws IOException { preparedIndex.close(); }
    }

    PreparedPublication preparePublication(SceneAssetStore.StorageLocation owner, String mapKey,
                                            DeltaInfo info, File stagedPatch, String targetSha) {
        if (owner == null || mapKey == null || mapKey.isBlank()
                || !SceneAssetStore.getInstance().isCurrent(owner)) return null;
        File dir = deltaDir();
        long revision;
        Map<String, DeltaInfo> before;
        synchronized (this) {
            if (dir == null || boundLocation != owner) return null;
            revision = indexRevision;
            before = new LinkedHashMap<>(index);
        }
        try {
            Files.createDirectories(dir.toPath());
            DeltaInfo resolved = null;
            if (info != null) {
                if (!info.isValid() || targetSha != null && !info.matchesTarget(targetSha) || stagedPatch == null) return null;
                byte[] bytes = Files.readAllBytes(stagedPatch.toPath());
                if (bytes.length != info.patchBytes()
                        || !SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(info.patchSha256())) return null;
                var patch = com.habitrain.core.api.scene.asset.SceneAssetDelta.decode(bytes);
                String targetHash = targetSha == null ? info.targetSha256() : targetSha;
                if (!patch.baseSha256().equalsIgnoreCase(info.baseSha256())
                        || !patch.targetSha256().equalsIgnoreCase(targetHash)) return null;
                resolved = new DeltaInfo(info.baseSha256(), targetSha == null ? info.targetSha256() : targetSha, info.patchSha256(), info.patchBytes(), info.createdAt());
                Path target = dir.toPath().resolve(resolved.patchSha256() + PATCH_SUFFIX);
                Path temp = Files.createTempFile(dir.toPath(), ".delta-promote-", ".tmp");
                try {
                    Files.write(temp, bytes);
                    try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    ScenePreparedIndex.move(temp, target);
                } finally { Files.deleteIfExists(temp); }
            }
            Map<String, DeltaInfo> after = new LinkedHashMap<>(before);
            if (resolved == null) after.remove(mapKey); else after.put(mapKey, resolved);
            ScenePreparedIndex prepared = ScenePreparedIndex.prepare(dir.toPath().resolve(INDEX_FILE_NAME),
                    deltaJson(before), deltaJson(after), GSON);
            return new PreparedPublication(owner, revision, mapKey, targetSha, resolved, before.get(mapKey), prepared);
        } catch (Exception failure) {
            LOGGER.warn("准备场景增量补丁失败，将使用全量下载: mapKey=" + mapKey, failure);
            return null;
        }
    }

    private static JsonObject deltaJson(Map<String, DeltaInfo> entries) {
        JsonObject root = new JsonObject();
        entries.forEach((key, info) -> {
            JsonObject value = new JsonObject();
            value.addProperty("baseSha256", info.baseSha256());
            value.addProperty("targetSha256", info.targetSha256());
            value.addProperty("patchSha256", info.patchSha256());
            value.addProperty("patchBytes", info.patchBytes());
            value.addProperty("createdAt", info.createdAt());
            root.add(key, value);
        });
        return root;
    }

    synchronized boolean commitPublication(PreparedPublication prepared) {
        if (prepared == null || boundLocation != prepared.owner || indexRevision != prepared.revision
                || !SceneAssetStore.getInstance().isCurrent(prepared.owner)) return false;
        SceneAssetDescriptor live = SceneAssetStore.getInstance().getDescriptor(prepared.mapKey);
        if (prepared.targetSha != null && (live == null || !live.sha256().equalsIgnoreCase(prepared.targetSha))) return false;
        if (prepared.targetSha == null && live != null && prepared.info != null && !prepared.info.matchesTarget(live.sha256())) return false;
        try {
            prepared.preparedIndex.commit();
            if (prepared.info == null) index.remove(prepared.mapKey); else index.put(prepared.mapKey, prepared.info);
            indexRevision++;
            return true;
        } catch (IOException failure) {
            LOGGER.warn("提交场景补丁索引失败，将使用全量下载", failure);
            return false;
        }
    }

    synchronized void finishPublication(PreparedPublication prepared) {
        if (prepared == null || prepared.previous == null || boundLocation != prepared.owner
                || !SceneAssetStore.getInstance().isCurrent(prepared.owner)) return;
        String previousSha = prepared.previous.patchSha256();
        if (index.values().stream().anyMatch(info -> previousSha.equalsIgnoreCase(info.patchSha256()))) return;
        try { Files.deleteIfExists(prepared.owner.storage().toPath().resolve(DIR_NAME).resolve(previousSha + PATCH_SUFFIX)); }
        catch (IOException failure) { LOGGER.warn("清理旧场景补丁失败", failure); }
    }

    public boolean publish(String mapKey, DeltaInfo info, File stagedPatch) {
        SceneAssetDescriptor live = SceneAssetStore.getInstance().getDescriptor(mapKey);
        if (info == null) return false;
        try (PreparedPublication prepared = preparePublication(SceneAssetStore.getInstance().currentLocation(),
                mapKey, info, stagedPatch, live == null ? null : live.sha256())) {
            if (!commitPublication(prepared)) return false;
            finishPublication(prepared);
            Files.deleteIfExists(stagedPatch.toPath());
            return true;
        } catch (IOException failure) {
            LOGGER.warn("清理场景补丁暂存文件失败", failure);
            return false;
        }
    }

    /** 暂存期的补丁文件：与暂存资产同目录，随暂存会话一起被丢弃。 */
    public File stagedPatchFile(String stagingId) {
        return stagedPatchFile(SceneAssetStore.getInstance().currentLocation(), stagingId);
    }

    File stagedPatchFile(SceneAssetStore.StorageLocation owner, String stagingId) {
        File stagingDir = owner == null ? null : owner.staging();
        if (stagingDir == null || !isValidStagingId(stagingId)) return null;
        Path dirPath = stagingDir.toPath().normalize();
        Path resolved = dirPath.resolve(stagingId.toLowerCase(Locale.ROOT) + PATCH_SUFFIX).normalize();
        if (!resolved.getParent().equals(dirPath)) return null;
        return resolved.toFile();
    }

    private static boolean isValidStagingId(String stagingId) {
        if (stagingId == null || stagingId.isBlank()) return false;
        try {
            return UUID.fromString(stagingId).toString().equalsIgnoreCase(stagingId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 把刚生成的补丁写进暂存目录（带 SHA-256 自校验）。 */
    public boolean stageDelta(String stagingId, PatchBlob blob) {
        return stageDelta(SceneAssetStore.getInstance().currentLocation(), stagingId, blob);
    }

    boolean stageDelta(SceneAssetStore.StorageLocation owner, String stagingId, PatchBlob blob) {
        File target = stagedPatchFile(owner, stagingId);
        if (target == null || blob == null || !blob.isValid()) return false;
        try {
            if (!target.getParentFile().exists()) Files.createDirectories(target.getParentFile().toPath());
            String actual = SceneAssetCodec.calculateSha256(blob.bytes());
            if (!actual.equalsIgnoreCase(blob.patchSha256())) {
                LOGGER.error("暂存补丁哈希校验失败: 期望={}, 实际={}", blob.patchSha256(), actual);
                return false;
            }
            Path temp = target.toPath().resolveSibling(target.getName() + ".tmp");
            Files.write(temp, blob.bytes());
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.error("写入暂存增量补丁失败: stagingId=" + stagingId, e);
            return false;
        }
    }

    /** 丢弃某个 mapKey 的补丁（删索引项与文件）。用于资产被删除或关闭附加背景。 */
    public synchronized void discard(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) return;
        if (deltaDir() == null) return;
        DeltaInfo previous = index.remove(mapKey);
        indexRevision++;
        if (previous != null) {
            saveIndex();
            deletePatchFileIfUnreferenced(previous.patchSha256());
        }
    }

    private void deletePatchFileIfUnreferenced(String patchSha256) {
        if (patchSha256 == null) return;
        for (DeltaInfo info : index.values()) {
            if (info != null && info.patchSha256().equalsIgnoreCase(patchSha256)) {
                return;
            }
        }
        File file = patchFile(patchSha256);
        if (file == null || !file.isFile()) return;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            LOGGER.warn("删除过期增量补丁失败: {}", file, e);
        }
    }
}
