package com.habitrain.core.scene.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.persist.AtomicJsonFiles;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
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
     * @param baseSha256  补丁的底——客户端必须持有这份资产才能应用
     * @param patchSha256 补丁文件自身的 SHA-256（同时也是分片传输的授权键与文件名）
     * @param patchBytes  补丁压缩后字节数
     */
    public record DeltaInfo(String baseSha256, String patchSha256, long patchBytes, long createdAt) {
        public DeltaInfo {
            baseSha256 = normalize(baseSha256);
            patchSha256 = normalize(patchSha256);
        }

        public boolean isValid() {
            return baseSha256.matches(HASH_PATTERN) && patchSha256.matches(HASH_PATTERN) && patchBytes > 0;
        }

        public String shortPatch() {
            return patchSha256.length() >= 12 ? patchSha256.substring(0, 12) : patchSha256;
        }
    }

    /** 暂存期产出的补丁：先落在 {@code staging/<stagingId>.hpatch}，发布时才搬进 deltas/。 */
    public record PatchBlob(String baseSha256, String patchSha256, byte[] bytes) {
        public boolean isValid() {
            return baseSha256 != null && baseSha256.matches(HASH_PATTERN)
                    && patchSha256 != null && patchSha256.matches(HASH_PATTERN)
                    && bytes != null && bytes.length > 0;
        }
    }

    private File boundRoot;
    private final Map<String, DeltaInfo> index = new ConcurrentHashMap<>();

    private SceneDeltaStore() {}

    private static String normalize(String hash) {
        return hash != null ? hash.trim().toLowerCase() : "";
    }

    /** 补丁目录跟随 {@link SceneAssetStore} 的世界绑定，避免再多一处初始化顺序。 */
    private File deltaDir() {
        File root = SceneAssetStore.getInstance().getStorageDir();
        if (root == null) return null;
        if (!root.equals(boundRoot)) {
            boundRoot = root;
            index.clear();
            loadIndex(new File(root, DIR_NAME));
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
            String base = entry.has("baseSha256") ? entry.get("baseSha256").getAsString() : "";
            String patch = entry.has("patchSha256") ? entry.get("patchSha256").getAsString() : "";
            long bytes = entry.has("patchBytes") ? entry.get("patchBytes").getAsLong() : 0L;
            long created = entry.has("createdAt") ? entry.get("createdAt").getAsLong() : 0L;
            DeltaInfo info = new DeltaInfo(base, patch, bytes, created);
            if (info.isValid() && patchFile(info.patchSha256()).isFile()) {
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
        if (!patchFile(info.patchSha256()).isFile()) {
            index.remove(mapKey);
            return null;
        }
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
    public synchronized boolean publish(String mapKey, DeltaInfo info, File stagedPatch) {
        if (mapKey == null || mapKey.isBlank() || info == null || !info.isValid()) return false;
        File dir = deltaDir();
        if (dir == null || stagedPatch == null || !stagedPatch.isFile()) return false;
        if (stagedPatch.length() != info.patchBytes()) {
            LOGGER.warn("拒绝发布增量补丁：暂存文件长度不符 mapKey={}, 期望={}, 实际={}",
                    mapKey, info.patchBytes(), stagedPatch.length());
            return false;
        }
        try {
            if (!dir.exists()) Files.createDirectories(dir.toPath());
            byte[] bytes = Files.readAllBytes(stagedPatch.toPath());
            String actual = SceneAssetCodec.calculateSha256(bytes);
            if (!actual.equalsIgnoreCase(info.patchSha256())) {
                LOGGER.warn("拒绝发布增量补丁：SHA-256 不符 mapKey={}, 期望={}, 实际={}",
                        mapKey, info.shortPatch(), actual);
                return false;
            }

            DeltaInfo previous = index.get(mapKey);
            Path target = patchFile(info.patchSha256()).toPath();
            Files.move(stagedPatch.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

            index.put(mapKey, info);
            if (!saveIndex()) {
                LOGGER.warn("增量补丁索引写入失败，回退为不提供增量: mapKey={}", mapKey);
                if (previous != null) {
                    index.put(mapKey, previous);
                } else {
                    index.remove(mapKey);
                }
                return false;
            }
            // 上一枚补丁连同它的文件一起清掉：一个 mapKey 只保留最新一版，客户端也只可能用到它。
            if (previous != null && !previous.patchSha256().equalsIgnoreCase(info.patchSha256())) {
                deletePatchFile(previous.patchSha256());
            }
            LOGGER.info("已发布场景增量补丁: mapKey={}, base={}, patch={}, 补丁 {} 字节",
                    mapKey, shortHash(info.baseSha256()), info.shortPatch(), info.patchBytes());
            return true;
        } catch (Exception e) {
            LOGGER.error("发布场景增量补丁失败: mapKey=" + mapKey, e);
            return false;
        }
    }

    /** 暂存期的补丁文件：与暂存资产同目录，随暂存会话一起被丢弃。 */
    public File stagedPatchFile(String stagingId) {
        File stagingDir = SceneAssetStore.getInstance().getStagingDir();
        if (stagingDir == null || stagingId == null || stagingId.isBlank()) return null;
        return new File(stagingDir, stagingId + PATCH_SUFFIX);
    }

    /** 把刚生成的补丁写进暂存目录（带 SHA-256 自校验）。 */
    public boolean stageDelta(String stagingId, PatchBlob blob) {
        File target = stagedPatchFile(stagingId);
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
        if (previous != null) {
            saveIndex();
            deletePatchFile(previous.patchSha256());
        }
    }

    private void deletePatchFile(String patchSha256) {
        File file = patchFile(patchSha256);
        if (file == null || !file.isFile()) return;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            LOGGER.warn("删除过期增量补丁失败: {}", file, e);
        }
    }

    private static String shortHash(String hash) {
        return hash != null && hash.length() >= 12 ? hash.substring(0, 12) : String.valueOf(hash);
    }
}
