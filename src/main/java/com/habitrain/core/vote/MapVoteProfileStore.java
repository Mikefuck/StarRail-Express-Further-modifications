package com.habitrain.core.vote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.MapVoteProfileSettings;
import com.habitrain.core.network.MapVoteProfilePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private MapVoteProfileStore() {}

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
        if (mapId == null) return false;
        String normalized = mapId.replace('\\', '/');
        return normalized.equals(MAP_VOTE_PREFIX)
                || normalized.startsWith(MAP_VOTE_PREFIX + "/");
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
            for (int i = 0; i < 6; i++) out.append(String.format("%02x", digest[i]));
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

    /**
     * Stores exactly one PNG for a map. The deterministic target name plus atomic
     * replacement means a successful upload removes the previous image, while a
     * failed upload leaves the old image untouched.
     */
    public static UploadResult saveUploadedPreview(ServerLevel level, String mapId,
                                                   String previousPreviewPath, byte[] pngBytes) {
        if (level == null) return UploadResult.error("服务端世界不可用");
        if (mapId == null || mapId.isBlank() || mapId.length() > 128 || isReservedMapId(mapId)) {
            return UploadResult.error("地图 ID 无效");
        }
        if (pngBytes == null || pngBytes.length <= 0
                || pngBytes.length > MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
            return UploadResult.error("图片超过 128 KiB 限制");
        }
        if (!isValidPng(pngBytes)) {
            return UploadResult.error("文件不是有效的 PNG 图片");
        }

        Path base = baseDir(level);
        Path previewDir = base.resolve(PREVIEW_DIR).normalize();
        String fileName = escapeId(mapId) + ".png";
        Path target = previewDir.resolve(fileName).normalize();
        if (!target.startsWith(previewDir)) {
            return UploadResult.error("预览图路径无效");
        }
        Path temp = target.resolveSibling(fileName + ".uploading");
        try {
            Files.createDirectories(previewDir);
            Files.write(temp, pngBytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // Config override already points to this deterministic path. Keep maps.json
            // aligned as a fallback, but an index-write problem must not invalidate an
            // otherwise successful atomic image replacement.
            try {
                pointIndexAtPreview(base, mapId, PREVIEW_DIR + "/" + fileName);
            } catch (IOException indexError) {
                LOGGER.warn("[MapVoteProfileStore] preview stored but maps.json update failed for '{}'",
                        mapId, indexError);
            }
            deletePreviousPreviewIfUnshared(base, mapId, previousPreviewPath, target);
            return UploadResult.ok();
        } catch (Exception e) {
            LOGGER.error("[MapVoteProfileStore] failed to store uploaded preview for '{}'", mapId, e);
            return UploadResult.error("服务器写入图片失败");
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
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
        return width > 0 && height > 0 && width <= 8192 && height <= 8192;
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
                                                        String previousRelativePath, Path newTarget) {
        if (previousRelativePath == null || previousRelativePath.isBlank()) return;
        try {
            Path previewDir = base.resolve(PREVIEW_DIR).normalize();
            Path previous = base.resolve(previousRelativePath).normalize();
            if (!previous.startsWith(previewDir) || previous.equals(newTarget)
                    || !previous.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".png")) {
                return;
            }
            if (isPreviewReferencedByAnotherMap(base, mapId, previous)) return;
            Files.deleteIfExists(previous);
        } catch (Exception e) {
            LOGGER.warn("[MapVoteProfileStore] uploaded preview saved but old preview cleanup failed for '{}'",
                    mapId, e);
        }
    }

    private static boolean isPreviewReferencedByAnotherMap(Path base, String mapId, Path candidate) {
        for (Map.Entry<String, MapVoteEntry> configured
                : com.habitrain.core.config.ConfigManager.getInstance()
                .getModeMapVoteSettings().maps.entrySet()) {
            if (configured.getKey().equals(mapId) || configured.getValue().profile == null) continue;
            String path = configured.getValue().profile.previewPath;
            if (path != null && base.resolve(path).normalize().equals(candidate)) return true;
        }
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
        try {
            Path base = baseDir(level);
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
        Map<String, MapVoteProfilePayload.MapProfile> result = new LinkedHashMap<>();
        if (level == null || mapIds == null || mapIds.isEmpty()) {
            return result;
        }
        try {
            Path base = baseDir(level);
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
                byte[] preview = placeholder;

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
                        preview = readPreview(base, entry.get("preview").getAsString(), placeholder);
                    }
                }
                MapVoteEntry cfg = configEntries == null ? null : configEntries.get(mapId);
                if (cfg != null) {
                    minPlayers = Math.max(0, cfg.minPlayers);
                    maxPlayers = Math.max(0, cfg.maxPlayers);
                    if (cfg.profile != null) {
                        description = cfg.profile.description == null ? "" : cfg.profile.description;
                        tags = MapVoteProfileSettings.normalizedTags(cfg.profile.tags);
                        String configuredPreview = cfg.profile.previewPath == null
                                ? "" : cfg.profile.previewPath.trim();
                        preview = configuredPreview.isEmpty()
                                ? placeholder
                                : readPreview(base, configuredPreview, placeholder);
                    }
                }
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
        if (Files.isRegularFile(index)) {
            try {
                String text = Files.readString(index, StandardCharsets.UTF_8);
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
        Files.createDirectories(index.getParent());
        Path temp = index.resolveSibling(index.getFileName() + ".tmp");
        Files.writeString(temp, PRETTY.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensurePreviewFile(Path base, String mapId) {
        Path file = base.resolve(PREVIEW_DIR).resolve(escapeId(mapId) + ".png");
        try {
            if (Files.isRegularFile(file) && Files.size(file) > 0) {
                return;
            }
            byte[] placeholder = placeholderBytes();
            Files.createDirectories(file.getParent());
            Files.write(file, placeholder);
            LOGGER.info("[MapVoteProfileStore] wrote placeholder preview for map '{}'", mapId);
        } catch (Exception e) {
            LOGGER.warn("[MapVoteProfileStore] failed to write placeholder preview for '{}'", mapId, e);
        }
    }

    private static byte[] readPreview(Path base, String relative, byte[] fallback) {
        try {
            Path file = base.resolve(relative).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) {
                return fallback;
            }
            long size = Files.size(file);
            if (size <= 0 || size > MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
                LOGGER.warn("[MapVoteProfileStore] preview '{}' size {} exceeds limit, using placeholder",
                        relative, size);
                return fallback;
            }
            return Files.readAllBytes(file);
        } catch (Exception e) {
            LOGGER.warn("[MapVoteProfileStore] failed to read preview '{}'", relative, e);
            return fallback;
        }
    }

    /** 从模组 jar 读取内置占位预览图；失败时降级为 1×1 像素 PNG。 */
    public static byte[] placeholderBytes() {
        try (InputStream in = HabiTrainCore.class.getResourceAsStream(PLACEHOLDER_RESOURCE)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            LOGGER.warn("[MapVoteProfileStore] failed to read placeholder resource", e);
        }
        // 1×1 透明 PNG 兜底
        return new byte[] {
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
    }
}
