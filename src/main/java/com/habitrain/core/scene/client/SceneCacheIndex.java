package com.habitrain.core.scene.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.habitrain.core.client.config.SceneClientPerformanceRules;
import com.habitrain.core.persist.AtomicJsonFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 磁盘缓存热度索引（{@code cache_index.json}）：hash → 最近一次被使用的时间。
 *
 * <p>淘汰如果只按文件 mtime 排序，<b>内存命中的资产永远不会刷新热度</b>——一个反复使用的
 * 背景，其 .hscene 可能因为"很久没读盘"而被当成旧文件删掉。这里把"被用到"这件事单独记下来，
 * 写盘做节流，避免每次命中都产生一次小写入。</p>
 *
 * <p><b>线程约定</b>：{@link #touch} 是纯内存 O(1) 操作，可在客户端线程调用；
 * {@link #loadIfNeeded} / {@link #flush} / {@link #prune} 会碰磁盘，只在 IO 线程调用。
 * 不依赖 Minecraft，可用临时目录单测。</p>
 */
public final class SceneCacheIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneCacheIndex.class.getSimpleName());

    public static final String FILE_NAME = "cache_index.json";
    private static final int SCHEMA_VERSION = 1;
    private static final String LAST_USED = "lastUsed";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final LongSupplier clockMillis;
    private final Map<String, Long> lastUsed = new ConcurrentHashMap<>();

    private boolean loaded;
    private boolean dirty;
    private long lastFlushMillis;

    public SceneCacheIndex(Path file, LongSupplier clockMillis) {
        this.file = file;
        this.clockMillis = clockMillis;
    }

    /** 读取索引；只做一次。文件缺失或损坏都退化为空索引（淘汰时回退到 mtime）。 */
    public synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        lastFlushMillis = clockMillis.getAsLong();
        if (!Files.isRegularFile(file)) return;

        try {
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || !root.has(LAST_USED) || !root.get(LAST_USED).isJsonObject()) return;
            JsonObject entries = root.getAsJsonObject(LAST_USED);
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
                try {
                    lastUsed.put(entry.getKey(), value.getAsLong());
                } catch (RuntimeException ignored) {
                    // 单条脏数据不影响其余条目。
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("场景缓存热度索引无法读取，淘汰将回退到文件修改时间: {}", file, e);
        }
    }

    /** 标记某个 hash 刚被使用。可在客户端线程调用。 */
    public void touch(String sha256, long nowMillis) {
        if (sha256 == null || sha256.isBlank()) return;
        lastUsed.put(sha256, nowMillis);
        dirty = true;
    }

    /** 热度值；索引里没有该 hash 时用调用方提供的回退值（通常是文件 mtime）。 */
    public long lastUsed(String sha256, long fallbackMillis) {
        Long value = sha256 == null ? null : lastUsed.get(sha256);
        return value != null ? value : fallbackMillis;
    }

    /**
     * 节流写盘：距上次写盘不足 {@link SceneClientPerformanceRules#CACHE_INDEX_FLUSH_INTERVAL_MS}
     * 时直接跳过（返回 false 表示"这次没写，不是失败"）。
     */
    public boolean flushThrottled() {
        if (!dirty) return false;
        long now = clockMillis.getAsLong();
        if (now - lastFlushMillis < SceneClientPerformanceRules.CACHE_INDEX_FLUSH_INTERVAL_MS) {
            return false;
        }
        return flush();
    }

    /** 立即写盘。写入的是**此刻**的实时索引，不是任何快照。 */
    public synchronized boolean flush() {
        if (!loaded) loaded = true;
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, Long> entry : lastUsed.entrySet()) {
            entries.addProperty(entry.getKey(), entry.getValue());
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", SCHEMA_VERSION);
        root.add(LAST_USED, entries);

        lastFlushMillis = clockMillis.getAsLong();
        if (!AtomicJsonFiles.writeJson(file, root, GSON, false, true)) {
            LOGGER.warn("场景缓存热度索引写入失败: {}", file);
            return false;
        }
        dirty = false;
        return true;
    }

    /** 丢掉已经不存在于磁盘缓存里的条目，避免索引无限增长。 */
    public void prune(Set<String> liveHashes) {
        Set<String> live = liveHashes == null ? Set.of() : liveHashes;
        if (lastUsed.keySet().removeIf(hash -> !live.contains(hash))) {
            dirty = true;
        }
    }

    /** 当前索引条目数（诊断用）。 */
    public int size() {
        return lastUsed.size();
    }
}
