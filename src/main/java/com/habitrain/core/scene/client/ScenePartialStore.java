package com.habitrain.core.scene.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.habitrain.core.client.config.SceneClientPerformanceRules;
import com.habitrain.core.persist.AtomicJsonFiles;
import com.habitrain.core.api.scene.SceneLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 断点续传的磁盘布局与保留策略：{@code <sha256>.part}（数据）+ {@code <sha256>.part.json}（元数据）。
 *
 * <p>元数据只登记「已经确认落在磁盘上的完整分片前缀」（{@code resumableBytes} 及其
 * {@code prefixSha256}）。它永远只用于**少续**，永远不会把没验证过的字节当成有效数据：
 * 续传前调用方必须用 {@code prefixSha256} 重新校验前缀。</p>
 *
 * <p><b>IO 约定</b>：本类的所有方法都可能碰磁盘，调用方必须只在 IO 线程上调用（
 * {@link #load} 与 {@link #dataFile} 除外——前者在续传校验路径上、后者是纯路径计算）。
 * 本类不依赖 Minecraft，可在单测里用临时目录完整覆盖。</p>
 */
public final class ScenePartialStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenePartialStore.class.getSimpleName());

    public static final String PART_SUFFIX = ".part";
    public static final String META_SUFFIX = ".part.json";
    /** Phase B 之前的临时文件命名：{@code <sha>.download.<millis>}，崩溃残留从不清理。 */
    private static final String LEGACY_DOWNLOAD_MARKER = ".download.";
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 一个可续传的部分下载。
     *
     * @param resumableBytes 已确认落在磁盘上的前缀长度；必定是对齐值，或等于 {@code totalSize}
     * @param prefixSha256   前 {@code resumableBytes} 个字节的 SHA-256，续传前用它校验
     * @param fingerprint    生成时的方块注册表指纹，可为 null（缺失即不校验）
     */
    public record PendingPart(String sha256, long totalSize, long resumableBytes,
                              String prefixSha256, String fingerprint, long updatedAtMillis) {

        public PendingPart withCheckpoint(long newResumableBytes, String newPrefixSha256, long nowMillis) {
            return new PendingPart(sha256, totalSize, newResumableBytes, newPrefixSha256,
                    fingerprint, nowMillis);
        }
    }

    public record SweepResult(int removed, long freedBytes) {}

    private final Path dir;

    public ScenePartialStore(Path dir) {
        this.dir = dir;
    }

    public Path dataFile(String sha256) {
        return dir.resolve(sha256 + PART_SUFFIX);
    }

    public Path metaFile(String sha256) {
        return dir.resolve(sha256 + META_SUFFIX);
    }

    /**
     * 读取并严格校验一个 hash 的续传记录。任何不合法（版本不符、字段越界、未对齐、数据文件
     * 缺失或过长）都返回 empty——调用方据此退回从 0 重新下载。
     */
    public Optional<PendingPart> load(String sha256) {
        if (sha256 == null || sha256.isBlank()) return Optional.empty();
        Path meta = metaFile(sha256);
        if (!Files.isRegularFile(meta)) return Optional.empty();

        JsonObject root;
        try {
            root = GSON.fromJson(Files.readString(meta, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            LOGGER.debug("续传元数据无法解析，按不可续传处理: {}", meta.getFileName(), e);
            return Optional.empty();
        }
        if (root == null || !hasInt(root, "version", SCHEMA_VERSION)) return Optional.empty();

        String storedHash = readString(root, "sha256");
        if (storedHash == null || !storedHash.equalsIgnoreCase(sha256)) return Optional.empty();

        Long totalSize = readLong(root, "totalSize");
        Long resumableBytes = readLong(root, "resumableBytes");
        String prefixSha256 = readString(root, "prefixSha256");
        Long updatedAt = readLong(root, "updatedAtMillis");
        if (totalSize == null || resumableBytes == null || updatedAt == null) return Optional.empty();
        if (prefixSha256 == null || !prefixSha256.matches("[0-9a-fA-F]{64}")) return Optional.empty();

        if (totalSize <= 0L || totalSize > SceneLimits.MAX_TRANSFER_BYTES) return Optional.empty();
        if (resumableBytes < 0L || resumableBytes > totalSize) return Optional.empty();
        if (resumableBytes != totalSize) {
            // 服务端只接受对齐偏移；未对齐的记录一律作废，绝不发出去换一条 OUT_OF_RANGE。
            if (resumableBytes % SceneClientPerformanceRules.CHUNK_ALIGNMENT != 0L) return Optional.empty();
        }
        if (resumableBytes == 0L) return Optional.empty();

        Path data = dataFile(sha256);
        if (!Files.isRegularFile(data)) return Optional.empty();
        long dataLength;
        try {
            dataLength = Files.size(data);
        } catch (IOException e) {
            return Optional.empty();
        }
        // 数据文件比声明的总大小还长 → 孤儿/被篡改，不能让它驱动任何分配。
        if (dataLength > totalSize) return Optional.empty();
        // 声明的前缀比盘上的字节还多 → 连前缀都读不出来，无从校验。
        if (dataLength < resumableBytes) return Optional.empty();

        return Optional.of(new PendingPart(sha256, totalSize, resumableBytes,
                prefixSha256.toLowerCase(Locale.ROOT), readString(root, "fingerprint"), updatedAt));
    }

    /** 原子写入一条续传记录（不 fsync）。失败返回 false，调用方不得据此宣布已续传。 */
    public boolean store(PendingPart part) {
        return store(part, false);
    }

    /**
     * 原子写入一条续传记录。
     *
     * @param fsync 逐片检查点传 false（进程崩溃/断线由 OS page cache 兜住，断电丢掉的那一段
     *              会被续传时的前缀校验挡住）；挂起/收尾的最终记录传 true
     */
    public boolean store(PendingPart part, boolean fsync) {
        if (part == null || part.sha256() == null || part.sha256().isBlank()) return false;
        JsonObject root = new JsonObject();
        root.addProperty("version", SCHEMA_VERSION);
        root.addProperty("sha256", part.sha256());
        root.addProperty("totalSize", part.totalSize());
        root.addProperty("resumableBytes", part.resumableBytes());
        root.addProperty("prefixSha256", part.prefixSha256());
        if (part.fingerprint() != null && !part.fingerprint().isBlank()) {
            root.addProperty("fingerprint", part.fingerprint());
        }
        root.addProperty("updatedAtMillis", part.updatedAtMillis());
        // backup=false：这是逐资产的临时记录，多一个 .bak 只会污染缓存目录。
        return AtomicJsonFiles.writeJson(metaFile(part.sha256()), root, GSON, fsync, false);
    }

    /**
     * 丢弃一条续传记录：先删数据、后删元数据。
     *
     * <p>次序不可颠倒——只要元数据在，就永远有 {@code resumableBytes} 声称的字节存在；
     * 反过来（元数据还在、数据没了）会被 {@link #load} 的校验挡住，但那属于要靠校验兜底
     * 的坏状态，不该主动制造。</p>
     */
    public void discard(String sha256) {
        if (sha256 == null) return;
        deleteQuietly(dataFile(sha256));
        deleteQuietly(metaFile(sha256));
    }

    /**
     * 清理不可续传的残留：旧版 {@code .download.*} 崩溃残留、没有元数据或元数据不合法的孤儿、
     * 过期项，最后按配额从最旧的开始删。
     *
     * <p>只应在会话开始时跑一次（或缓存目录首次被使用时），不要在每次下载后跑。</p>
     */
    public SweepResult sweep(long nowMillis, long quotaBytes, long maxAgeMillis) {
        int removed = 0;
        long freed = 0L;

        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.toList();
        } catch (IOException e) {
            LOGGER.warn("扫描场景缓存目录失败: {}", dir, e);
            return new SweepResult(0, 0L);
        }

        // 1) 旧版残留：无元数据可依，直接删。
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (!name.contains(LEGACY_DOWNLOAD_MARKER)) continue;
            long length = sizeOf(entry);
            if (deleteQuietly(entry)) {
                removed++;
                freed += length;
            }
        }

        // 2) 收集 .part 数据文件；没有合法元数据的一律删（数据 + 元数据）。
        List<PendingPart> resumable = new ArrayList<>();
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (!name.endsWith(PART_SUFFIX)) continue;
            String hash = name.substring(0, name.length() - PART_SUFFIX.length());
            Optional<PendingPart> part = load(hash);
            if (part.isEmpty()) {
                long length = sizeOf(entry);
                deleteQuietly(entry);
                deleteQuietly(metaFile(hash));
                removed++;
                freed += length;
                continue;
            }
            resumable.add(part.get());
        }

        // 3) 过期（以记录里的 updatedAtMillis 为准，不信任文件 mtime）。
        List<PendingPart> alive = new ArrayList<>(resumable.size());
        for (PendingPart part : resumable) {
            if (maxAgeMillis > 0L && nowMillis - part.updatedAtMillis() > maxAgeMillis) {
                freed += sizeOf(dataFile(part.sha256()));
                discard(part.sha256());
                removed++;
            } else {
                alive.add(part);
            }
        }

        // 4) 配额：最旧的先走。
        long total = 0L;
        for (PendingPart part : alive) total += sizeOf(dataFile(part.sha256()));
        if (quotaBytes > 0L && total > quotaBytes) {
            alive.sort(Comparator.comparingLong(PendingPart::updatedAtMillis));
            for (PendingPart part : alive) {
                if (total <= quotaBytes) break;
                long length = sizeOf(dataFile(part.sha256()));
                discard(part.sha256());
                total -= length;
                freed += length;
                removed++;
            }
        }

        if (removed > 0) {
            LOGGER.info("清理场景部分下载文件: 删除 {} 个，释放 {} bytes", removed, freed);
        }
        return new SweepResult(removed, freed);
    }

    /** 当前全部部分下载数据的字节数合计。 */
    public long totalBytes() {
        long total = 0L;
        for (String hash : hashes()) {
            total += sizeOf(dataFile(hash));
        }
        return total;
    }

    /** 当前存在数据文件的所有 hash。 */
    public Set<String> hashes() {
        Set<String> hashes = new HashSet<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(entry -> {
                String name = entry.getFileName().toString();
                if (!name.endsWith(PART_SUFFIX) || name.endsWith(META_SUFFIX)) return;
                hashes.add(name.substring(0, name.length() - PART_SUFFIX.length()));
            });
        } catch (IOException e) {
            LOGGER.debug("枚举场景部分下载文件失败: {}", dir, e);
        }
        return hashes;
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("删除场景缓存文件失败: {}", file, e);
            return false;
        }
    }

    private static boolean hasInt(JsonObject root, String key, int expected) {
        Long value = readLong(root, key);
        return value != null && value == expected;
    }

    private static Long readLong(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readString(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value;
    }
}
