package com.habitrain.core.vote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.MapVoteProfileSettings;
import com.habitrain.core.game.sre.config.SREIntegration;
import com.habitrain.core.network.MapVoteProfilePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图投票档案存储：服务端世界目录 {@code <world>/train_maps/map_vote/}。
 *
 * <p>目录结构：</p>
 * <pre>
 * train_maps/
 * ├── map1.json …          ← SRE 地图配置（现有，不可动）
 * └── map_vote/            ← 本次新增
 *     ├── maps.json        ← {maps:{&lt;mapId&gt;:{description,tags,minPlayers,maxPlayers,preview}}}
 *     └── previews/&lt;escapedId&gt;.png
 * </pre>
 *
 * <p>地图 id 是 SRE {@code train_maps/*.json} 文件名（可含子路径）。SRE 的
 * {@code MapManager.getAvailableMaps} 会递归枚举目录下所有 {@code *.json}，因此
 * {@code map_vote/maps.json} 会被当成一张地图 id {@code map_vote/maps}——调用方必须用
 * {@link #isReservedMapId} 在候选过滤时剔除 {@code map_vote} 前缀。</p>
 *
 * <p>缺条目/缺预览图时自动写入默认数据与内置占位图，保证功能可见。</p>
 */
public final class MapVoteProfileStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MapVoteProfileStore");

    public static final String DIR_NAME = "map_vote";
    public static final String INDEX_FILE = "maps.json";
    public static final String PREVIEW_DIR = "previews";
    public static final String MAP_VOTE_PREFIX = "map_vote";

    /** 占位预览图在模组 jar 内的资源路径。 */
    public static final String PLACEHOLDER_RESOURCE =
            "/assets/habitrain_core/textures/gui/map_vote/placeholder.png";

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Object IO_LOCK = new Object();

    private record CachedPreview(long lastModifiedMillis, long size, byte[] bytes) {}
    private record CachedIndex(long lastModifiedMillis, long size, String rawJson) {}

    /** X-CUT-02 & API-TV-21：按文件路径+修改时间+文件大小缓存已读取的 PNG 字节与 maps.json，消除主线程重复磁盘 IO */
    private static final ConcurrentHashMap<Path, CachedPreview> PREVIEW_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, CachedIndex> INDEX_CACHE = new ConcurrentHashMap<>();
    private static volatile byte[] cachedPlaceholder;
    private static final long PROFILE_CACHE_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** Guarded by IO_LOCK, including invalidation by the asynchronous upload writer. */
    private static CachedProfiles cachedProfiles;
    private record CachedProfiles(WeakReference<Object> owner, Path base, long configRevision,
                                  Set<String> mapIds, long loadedAtNanos,
                                  List<MapVoteProfilePayload> fragments) {}

    private MapVoteProfileStore() {}

    public static void clearCache() {
        synchronized (IO_LOCK) {
            PREVIEW_CACHE.clear();
            INDEX_CACHE.clear();
            cachedProfiles = null;
        }
    }

    /** Packet cache belongs to one server and one configuration revision. */
    public static List<MapVoteProfilePayload> loadProfileFragments(
            ServerLevel level, Map<String, MapVoteEntry> entries, long configRevision) {
        return loadProfileFragments(level.getServer(), baseDir(level), entries, configRevision, System.nanoTime());
    }

    static List<MapVoteProfilePayload> loadProfileFragments(
            Object owner, Path base, Map<String, MapVoteEntry> entries, long configRevision, long nowNanos) {
        Path normalized = base.toAbsolutePath().normalize();
        Set<String> mapIds = Set.copyOf(entries.keySet());
        synchronized (IO_LOCK) {
            CachedProfiles cached = cachedProfiles;
            if (cached != null && cached.owner().get() == owner && cached.base().equals(normalized)
                    && cached.configRevision() == configRevision && cached.mapIds().equals(mapIds)
                    && nowNanos - cached.loadedAtNanos() < PROFILE_CACHE_TTL_NANOS) {
                return cached.fragments();
            }
            var fragments = List.copyOf(MapVoteProfilePayload.fragment(loadProfiles(normalized, mapIds, entries)));
            cachedProfiles = new CachedProfiles(new WeakReference<>(owner), normalized, configRevision,
                    mapIds, nowNanos, fragments);
            return fragments;
        }
    }

    public record UploadResult(boolean success, String message) {
        private static UploadResult ok() {
            return new UploadResult(true, "ok");
        }

        private static UploadResult error(String message) {
            return new UploadResult(false, message);
        }
    }

    /** SRE 会把 map_vote 目录自身枚举成候选地图，这里统一剔除。 */
    public static boolean isReservedMapId(String mapId) {
        if (mapId == null || mapId.isBlank()) return false;
        String normalized = mapId.replace('\\', '/');
        return normalized.equals(MAP_VOTE_PREFIX)
                || normalized.startsWith(MAP_VOTE_PREFIX + "/")
                || normalized.equalsIgnoreCase("random");
    }

    /** 地图 id → 安全文件名：子路径/非法字符统一转下划线。 */
    public static String escapeId(String mapId) {
        if (mapId == null) {
            return "";
        }
        String s = mapId.trim()
                .replace('\\', '_')
                .replace('/', '_')
                .replace(':', '_')
                .replace(' ', '_')
                .replace("..", "_");
        String readable = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        return readable + "-" + shortHash(mapId);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < 6; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** 档案根目录：{@code <world>/train_maps/map_vote}。 */
    public static Path baseDir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("train_maps")
                .resolve(DIR_NAME)
                .toAbsolutePath()
                .normalize();
    }

    private static boolean isKnownMapId(ServerLevel level, String mapId) {
        try {
            var settings = ConfigManager.getInstance().getModeMapVoteSettings();
            if (settings != null && settings.maps != null && settings.maps.containsKey(mapId)) {
                return true;
            }
            if (SREIntegration.discoverServerMaps(level).containsKey(mapId)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Stores exactly one PNG for a map. The deterministic target name plus atomic
     * replacement means a successful upload removes the previous image, while a
     * failed upload leaves the old image untouched.
     */
    public static UploadResult saveUploadedPreview(ServerLevel level, String mapId,
                                                   String previousPreviewPath, byte[] pngBytes) {
        if (level == null) return UploadResult.error("服务端世界不可用");
        return saveUploadedPreview(prepareUpload(level, mapId, isKnownMapId(level, mapId)), mapId, previousPreviewPath, pngBytes);
    }

    public record UploadContext(Path base, boolean knownMap, java.util.Set<Path> otherPreviews) {}

    /** Snapshot on the server thread; the actual upload then needs no world/config objects. */
    public static UploadContext prepareUpload(ServerLevel level, String mapId, boolean knownMap) {
        Path base = baseDir(level);
        java.util.Set<Path> references = new java.util.HashSet<>();
        for (var entry : ConfigManager.getInstance().getModeMapVoteSettings().maps.entrySet()) {
            if (entry.getKey().equals(mapId) || entry.getValue().profile == null) continue;
            String preview = entry.getValue().profile.previewPath;
            if (preview != null) references.add(base.resolve(preview).normalize());
        }
        return new UploadContext(base, knownMap, java.util.Set.copyOf(references));
    }

    public static UploadResult saveUploadedPreview(UploadContext context, String mapId,
                                                   String previousPreviewPath, byte[] pngBytes) {
        if (mapId == null || mapId.isBlank() || mapId.length() > 128
                || mapId.contains("..") || isReservedMapId(mapId)) {
            return UploadResult.error("地图 ID 无效");
        }
        // CFG-03：拒绝向不存在的伪造 mapId 上传图片，防止恶意写满服务端磁盘
        if (!context.knownMap()) {
            return UploadResult.error("未知的地图 ID: " + mapId);
        }
        if (pngBytes == null || pngBytes.length <= 0
                || pngBytes.length > MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
            return UploadResult.error("图片超过 128 KiB 限制");
        }
        if (!isValidPng(pngBytes)) {
            return UploadResult.error("文件不是有效的 PNG 图片");
        }

        Path base = context.base();
        Path previewDir = base.resolve(PREVIEW_DIR).normalize();
        String fileName = escapeId(mapId) + ".png";
        Path target = previewDir.resolve(fileName).normalize();
        if (!target.startsWith(previewDir)) {
            return UploadResult.error("预览图路径无效");
        }
        synchronized (IO_LOCK) {
            try {
                writePreviewAtomically(target, pngBytes);
                // Config override already points to this deterministic path. Keep maps.json
                // aligned as a fallback, but an index-write problem must not invalidate an
                // otherwise successful atomic image replacement.
                try {
                    pointIndexAtPreview(base, mapId, PREVIEW_DIR + "/" + fileName);
                } catch (IOException indexError) {
                    LOGGER.warn("[MapVoteProfileStore] preview stored but maps.json update failed for '{}'",
                            mapId, indexError);
                }
                deletePreviousPreviewIfUnshared(base, mapId, previousPreviewPath, target, context.otherPreviews());
                return UploadResult.ok();
            } catch (Exception e) {
                LOGGER.error("[MapVoteProfileStore] failed to store uploaded preview for '{}'", mapId, e);
                return UploadResult.error("服务器写入图片失败");
            }
        }
    }

    static boolean isValidPng(byte[] bytes) {
        if (bytes == null || bytes.length < 33) return false;
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) return false;
        }
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            return false;
        }
        int width = readIntBigEndian(bytes, 16);
        int height = readIntBigEndian(bytes, 20);
        return width > 0 && height > 0 && width <= 1024 && height <= 1024;
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24
                | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8
                | bytes[offset + 3] & 0xFF;
    }

    private static void pointIndexAtPreview(Path base, String mapId, String relativePath)
            throws IOException {
        Path index = base.resolve(INDEX_FILE);
        JsonObject root = readIndex(index);
        JsonObject maps = root.has("maps") && root.get("maps").isJsonObject()
                ? root.getAsJsonObject("maps") : new JsonObject();
        root.add("maps", maps);
        JsonObject entry = maps.has(mapId) && maps.get(mapId).isJsonObject()
                ? maps.getAsJsonObject(mapId) : new JsonObject();
        maps.add(mapId, entry);
        entry.addProperty("preview", relativePath);
        writeIndex(index, root);
    }

    private static void deletePreviousPreviewIfUnshared(Path base, String mapId,
                                                        String previousRelativePath, Path newTarget, java.util.Set<Path> otherPreviews) {
        if (previousRelativePath == null || previousRelativePath.isBlank()) return;
        try {
            Path previewDir = base.resolve(PREVIEW_DIR).normalize();
            Path previous = base.resolve(previousRelativePath).normalize();
            String prevFileName = previous.getFileName().toString();
            // CFG-03：仅允许删除属于当前 mapId 自身命名空间或未被其他地图引用的旧文件，
            // 严禁客户端通过伪造 previousPreviewPath 越权删除其他地图的默认预览图（<escapedOtherId>.png）
            if (!previous.startsWith(previewDir) || previous.equals(newTarget)
                    || !prevFileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                return;
            }
            if (!prevFileName.startsWith(escapeId(mapId))) {
                return;
            }
            if (otherPreviews.contains(previous) || isPreviewReferencedByAnotherMap(base, mapId, previous)) return;
            Files.deleteIfExists(previous);
            PREVIEW_CACHE.remove(previous);
        } catch (Exception e) {
            LOGGER.warn("[MapVoteProfileStore] uploaded preview saved but old preview cleanup failed for '{}'",
                    mapId, e);
        }
    }

    private static boolean isPreviewReferencedByAnotherMap(Path base, String mapId, Path candidate) {
        JsonObject root = readIndex(base.resolve(INDEX_FILE));
        if (!root.has("maps") || !root.get("maps").isJsonObject()) return false;
        for (Map.Entry<String, JsonElement> indexed : root.getAsJsonObject("maps").entrySet()) {
            if (indexed.getKey().equals(mapId) || !indexed.getValue().isJsonObject()) continue;
            JsonObject entry = indexed.getValue().getAsJsonObject();
            if (entry.has("preview") && entry.get("preview").isJsonPrimitive()
                    && base.resolve(entry.get("preview").getAsString()).normalize().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 投票开始前调用：对每张候选地图补齐 maps.json 条目与 previews 文件，缺则写占位。
     *
     * @param configEntries 现有投票配置（min/max 来源），可为空
     */
    public static void ensureProfiles(ServerLevel level, Collection<String> mapIds,
                                      Map<String, MapVoteEntry> configEntries) {
        if (level == null || mapIds == null || mapIds.isEmpty()) {
            return;
        }
        ensureProfiles(baseDir(level), mapIds, configEntries);
    }

    /** Path-only IO used by async loaders that must not touch {@code ServerLevel}. */
    public static void ensureProfiles(Path base, Collection<String> mapIds,
                                      Map<String, MapVoteEntry> configEntries) {
        if (base == null || mapIds == null || mapIds.isEmpty()) {
            return;
        }
        synchronized (IO_LOCK) {
            try {
                Files.createDirectories(base.resolve(PREVIEW_DIR));
                Path index = base.resolve(INDEX_FILE);
                JsonObject root = readIndex(index);
                JsonObject maps = root.has("maps") && root.get("maps").isJsonObject()
                        ? root.getAsJsonObject("maps") : new JsonObject();
                root.add("maps", maps);

                boolean changed = false;
                for (String mapId : mapIds) {
                    if (isReservedMapId(mapId)) {
                        continue;
                    }
                    if (!maps.has(mapId) || !maps.get(mapId).isJsonObject()) {
                        JsonObject entry = new JsonObject();
                        MapVoteEntry cfg = configEntries == null ? null : configEntries.get(mapId);
                        entry.addProperty("description", "");
                        entry.add("tags", new JsonArray());
                        entry.addProperty("minPlayers", cfg != null ? Math.max(0, cfg.minPlayers) : 0);
                        entry.addProperty("maxPlayers", cfg != null ? Math.max(0, cfg.maxPlayers) : 0);
                        entry.addProperty("preview", PREVIEW_DIR + "/" + escapeId(mapId) + ".png");
                        maps.add(mapId, entry);
                        changed = true;
                    }
                    JsonObject entry = maps.getAsJsonObject(mapId);
                    MapVoteEntry cfg = configEntries == null ? null : configEntries.get(mapId);
                    if (cfg != null) {
                        changed |= replaceNumber(entry, "minPlayers", Math.max(0, cfg.minPlayers));
                        changed |= replaceNumber(entry, "maxPlayers", Math.max(0, cfg.maxPlayers));
                    }
                    ensurePreviewFile(base, mapId);
                }
                if (changed) {
                    writeIndex(index, root);
                }
            } catch (Exception e) {
                LOGGER.error("[MapVoteProfileStore] ensureProfiles failed", e);
            }
        }
    }

    /** 读取候选地图的档案（含预览图字节）；缺条目/缺图/超限 → 占位。 */
    public static Map<String, MapVoteProfilePayload.MapProfile> loadProfiles(
            ServerLevel level, Collection<String> mapIds) {
        return loadProfiles(level, mapIds, Map.of());
    }

    /**
     * Loads world profiles and overlays optional Mod Menu values. Recommended player
     * counts always come from the main vote config so the draw rule and information
     * sheet cannot drift apart.
     */
    public static Map<String, MapVoteProfilePayload.MapProfile> loadProfiles(
            ServerLevel level, Collection<String> mapIds, Map<String, MapVoteEntry> configEntries) {
        if (level == null || mapIds == null || mapIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return loadProfiles(baseDir(level), mapIds, configEntries);
    }

    /** Path-only IO used by async loaders that must not touch {@code ServerLevel}. */
    public static Map<String, MapVoteProfilePayload.MapProfile> loadProfiles(
            Path base, Collection<String> mapIds, Map<String, MapVoteEntry> configEntries) {
        Map<String, MapVoteProfilePayload.MapProfile> result = new LinkedHashMap<>();
        if (base == null || mapIds == null || mapIds.isEmpty()) {
            return result;
        }
        try {
            JsonObject root = readIndex(base.resolve(INDEX_FILE));
            JsonObject maps = root.has("maps") && root.get("maps").isJsonObject()
                    ? root.getAsJsonObject("maps") : new JsonObject();
            byte[] placeholder = placeholderBytes();

            for (String mapId : mapIds) {
                if (isReservedMapId(mapId)) {
                    continue;
                }
                String description = "";
                List<String> tags = List.of();
                int minPlayers = 0;
                int maxPlayers = 0;
                String indexPreviewPath = null;

                JsonElement el = maps.get(mapId);
                if (el != null && el.isJsonObject()) {
                    JsonObject entry = el.getAsJsonObject();
                    if (entry.has("description") && entry.get("description").isJsonPrimitive()) {
                        description = entry.get("description").getAsString();
                    }
                    if (entry.has("tags") && entry.get("tags").isJsonArray()) {
                        List<String> list = new ArrayList<>();
                        for (JsonElement tag : entry.getAsJsonArray("tags")) {
                            if (tag.isJsonPrimitive() && list.size() < MapVoteProfilePayload.MAX_TAGS) {
                                String t = tag.getAsString();
                                if (t != null && !t.isBlank()) {
                                    list.add(t);
                                }
                            }
                        }
                        tags = list;
                    }
                    if (entry.has("minPlayers") && entry.get("minPlayers").isJsonPrimitive()) {
                        minPlayers = Math.max(0, entry.get("minPlayers").getAsInt());
                    }
                    if (entry.has("maxPlayers") && entry.get("maxPlayers").isJsonPrimitive()) {
                        maxPlayers = Math.max(0, entry.get("maxPlayers").getAsInt());
                    }
                    if (entry.has("preview") && entry.get("preview").isJsonPrimitive()) {
                        indexPreviewPath = entry.get("preview").getAsString();
                    }
                }
                String effectivePreviewPath = indexPreviewPath;
                MapVoteEntry cfg = configEntries == null ? null : configEntries.get(mapId);
                if (cfg != null) {
                    minPlayers = Math.max(0, cfg.minPlayers);
                    maxPlayers = Math.max(0, cfg.maxPlayers);
                    if (cfg.profile != null) {
                        description = cfg.profile.description == null ? "" : cfg.profile.description;
                        tags = MapVoteProfileSettings.normalizedTags(cfg.profile.tags);
                        String configuredPreview = cfg.profile.previewPath == null
                                ? "" : cfg.profile.previewPath.trim();
                        effectivePreviewPath = configuredPreview.isEmpty() ? null : configuredPreview;
                    }
                }
                // X-CUT-02：先确定最终生效路径再读一次（走内存缓存），消除双路径非空时的二次读取
                byte[] preview = (effectivePreviewPath == null || effectivePreviewPath.isBlank())
                        ? placeholder
                        : readPreview(base, effectivePreviewPath, placeholder);
                result.put(mapId, new MapVoteProfilePayload.MapProfile(
                        description, tags, minPlayers, maxPlayers, preview));
            }
        } catch (Exception e) {
            LOGGER.error("[MapVoteProfileStore] loadProfiles failed", e);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static JsonObject readIndex(Path index) {
        if (index != null && Files.isRegularFile(index)) {
            try {
                Path normalized = index.toAbsolutePath().normalize();
                long lastModified = Files.getLastModifiedTime(normalized).toMillis();
                long size = Files.size(normalized);
                CachedIndex cached = INDEX_CACHE.get(normalized);
                String text;
                if (cached != null && cached.lastModifiedMillis == lastModified && cached.size == size) {
                    text = cached.rawJson;
                } else {
                    text = Files.readString(normalized, StandardCharsets.UTF_8);
                    INDEX_CACHE.put(normalized, new CachedIndex(lastModified, size, text));
                }
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (Exception e) {
                LOGGER.warn("[MapVoteProfileStore] failed to read {}, rebuilding", index, e);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        return root;
    }

    private static boolean replaceNumber(JsonObject object, String key, int value) {
        if (object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsInt() == value) {
            return false;
        }
        object.addProperty(key, value);
        return true;
    }

    private static void writeIndex(Path index, JsonObject root) throws IOException {
        Path parent = index.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        // API-TV-21：使用唯一临时文件，防止多线程并发写入固定 maps.json.tmp 互相覆盖或抛 NoSuchFileException
        Path temp = Files.createTempFile(parent, index.getFileName().toString() + ".", ".tmp");
        try {
            String raw = PRETTY.toJson(root);
            Files.writeString(temp, raw, StandardCharsets.UTF_8);
            try {
                Files.move(temp, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING);
            }
            Path normalized = index.toAbsolutePath().normalize();
            cachedProfiles = null;
            INDEX_CACHE.put(normalized, new CachedIndex(
                    Files.getLastModifiedTime(normalized).toMillis(),
                    Files.size(normalized),
                    raw));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * API-TV-21：校验文件大小上限（<= 128 KiB）并通过缓存读取，防止超大异常文件无上限 readAllBytes OOM。
     */
    static boolean previewNeedsRebuild(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return true;
        }
        long size = Files.size(file);
        if (size < 33 || size > MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
            return true;
        }
        byte[] bytes = readCachedPreviewBytes(file.toAbsolutePath().normalize(), size);
        return !isValidPng(bytes);
    }

    private static void writePreviewAtomically(Path target, byte[] bytes) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        Files.createDirectories(parent);
        // API-TV-21：使用唯一临时文件，防止并发上传或 ensurePreviewFile 争用固定 .uploading 文件
        Path temp = Files.createTempFile(parent, normalizedTarget.getFileName().toString() + ".", ".uploading");
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, normalizedTarget, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            cachedProfiles = null;
            PREVIEW_CACHE.put(normalizedTarget, new CachedPreview(
                    Files.getLastModifiedTime(normalizedTarget).toMillis(),
                    bytes.length,
                    bytes));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void ensurePreviewFile(Path base, String mapId) {
        Path file = base.resolve(PREVIEW_DIR).resolve(escapeId(mapId) + ".png");
        try {
            if (!previewNeedsRebuild(file)) {
                return;
            }
            if (Files.isRegularFile(file) && Files.size(file) > 0) {
                LOGGER.warn("[MapVoteProfileStore] preview '{}' is truncated/corrupt, rebuilding placeholder",
                        file.getFileName());
            }
            writePreviewAtomically(file, placeholderBytes());
            LOGGER.info("[MapVoteProfileStore] wrote placeholder preview for map '{}'", mapId);
        } catch (Exception e) {
            LOGGER.warn("[MapVoteProfileStore] failed to write placeholder preview for '{}'", mapId, e);
        }
    }

    private static byte[] readCachedPreviewBytes(Path normalizedFile, long size) throws IOException {
        long lastModified = Files.getLastModifiedTime(normalizedFile).toMillis();
        CachedPreview cached = PREVIEW_CACHE.get(normalizedFile);
        if (cached != null && cached.lastModifiedMillis == lastModified && cached.size == size) {
            return cached.bytes;
        }
        byte[] bytes = Files.readAllBytes(normalizedFile);
        PREVIEW_CACHE.put(normalizedFile, new CachedPreview(lastModified, bytes.length, bytes));
        return bytes;
    }

    private static byte[] readPreview(Path base, String relative, byte[] fallback) {
        try {
            Path normalizedBase = base.toAbsolutePath().normalize();
            Path file = normalizedBase.resolve(relative).normalize();
            if (!file.startsWith(normalizedBase) || !Files.isRegularFile(file)) {
                return fallback;
            }
            long size = Files.size(file);
            if (size <= 0 || size > MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
                LOGGER.warn("[MapVoteProfileStore] preview '{}' size {} exceeds limit, using placeholder",
                        relative, size);
                return fallback;
            }
            return readCachedPreviewBytes(file, size);
        } catch (Exception e) {
            LOGGER.warn("[MapVoteProfileStore] failed to read preview '{}'", relative, e);
            return fallback;
        }
    }

    /** 从模组 jar 读取内置占位预览图（带内存缓存）；失败时降级为 1×1 像素 PNG。 */
    public static byte[] placeholderBytes() {
        byte[] existing = cachedPlaceholder;
        if (existing != null) {
            return existing;
        }
        try (InputStream in = HabiTrainCore.class.getResourceAsStream(PLACEHOLDER_RESOURCE)) {
            if (in != null) {
                byte[] loaded = in.readAllBytes();
                cachedPlaceholder = loaded;
                return loaded;
            }
        } catch (IOException e) {
            LOGGER.warn("[MapVoteProfileStore] failed to read placeholder resource", e);
        }
        // 1×1 透明 PNG 兜底
        byte[] fallback = new byte[] {
                (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x48, (byte) 0x44, (byte) 0x52,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
                (byte) 0x08, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1F, (byte) 0x15, (byte) 0xC4,
                (byte) 0x89, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x44, (byte) 0x41,
                (byte) 0x54, (byte) 0x78, (byte) 0x9C, (byte) 0x62, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
                (byte) 0x05, (byte) 0x00, (byte) 0x01, (byte) 0x0D, (byte) 0x0A, (byte) 0x2D, (byte) 0xB4, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x49, (byte) 0x45, (byte) 0x4E, (byte) 0x44, (byte) 0xAE,
                (byte) 0x42, (byte) 0x60, (byte) 0x82
        };
        cachedPlaceholder = fallback;
        return fallback;
    }
}
