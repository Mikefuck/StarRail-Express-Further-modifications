package com.habitrain.core.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.persist.AtomicJsonFiles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Client-only visual preferences that must not be synchronized with a server.
 *
 * <p>The main {@code habitrain_core.json} is server-authoritative and participates in
 * config synchronization. Title-screen visuals are a local choice, so they live in a
 * separate file and are applied immediately.</p>
 *
 * <p>场景传输/性能项放在同一个文件的 {@code scenePerformance} 子对象里，而不是另起一个
 * 配置类：两个类同时读写同一个 JSON 文件会在读-改-写之间互相覆盖。</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientVisualPreferences {
    private static final String FILE_NAME = "habitrain_core-client.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED = true;
    private static final String SCENE_PERFORMANCE = "scenePerformance";

    private static boolean loaded;
    private static boolean customTitlePanoramaEnabled = DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED;
    private static int requestTimeoutMs = SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS;
    private static int maxChunkRetries = SceneClientPerformanceRules.DEFAULT_MAX_CHUNK_RETRIES;
    private static int diskCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB;
    private static boolean partialResumeEnabled = SceneClientPerformanceRules.DEFAULT_PARTIAL_RESUME_ENABLED;
    private static int partialCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_PARTIAL_CACHE_QUOTA_MIB;
    private static int partialExpiryHours = SceneClientPerformanceRules.DEFAULT_PARTIAL_EXPIRY_HOURS;
    private static int transferWindowChunks = SceneClientPerformanceRules.DEFAULT_TRANSFER_WINDOW_CHUNKS;
    private static int meshBatchMinSections = SceneClientPerformanceRules.DEFAULT_MESH_BATCH_MIN_SECTIONS;
    private static int meshBuildBudgetMsPerFrame =
            SceneClientPerformanceRules.DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME;
    private static int memoryCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_MEMORY_CACHE_QUOTA_MIB;
    private static int meshCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_MESH_CACHE_QUOTA_MIB;

    private ClientVisualPreferences() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        AtomicJsonFiles.JsonLoad<JsonObject> result = AtomicJsonFiles.readJson(
                file(), JsonObject.class, GSON);
        if (!result.ok()) {
            if (result.corrupt()) {
                HabiTrainCore.LOGGER.warn("客户端视觉配置损坏，已回退为默认设置");
            }
            applyDefaults();
            return;
        }

        JsonObject root = result.value();
        customTitlePanoramaEnabled = !root.has("customTitlePanoramaEnabled")
                ? DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED
                : root.get("customTitlePanoramaEnabled").isJsonPrimitive()
                        && root.get("customTitlePanoramaEnabled").getAsBoolean();

        JsonObject performance = root.has(SCENE_PERFORMANCE) && root.get(SCENE_PERFORMANCE).isJsonObject()
                ? root.getAsJsonObject(SCENE_PERFORMANCE)
                : new JsonObject();
        requestTimeoutMs = readInt(performance, "requestTimeoutMs",
                SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS,
                SceneClientPerformanceRules::clampRequestTimeoutMs);
        maxChunkRetries = readInt(performance, "maxChunkRetries",
                SceneClientPerformanceRules.DEFAULT_MAX_CHUNK_RETRIES,
                SceneClientPerformanceRules::clampMaxChunkRetries);
        diskCacheQuotaMiB = readInt(performance, "diskCacheQuotaMiB",
                SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules::clampDiskCacheQuotaMiB);
        partialResumeEnabled = readBoolean(performance, "partialResumeEnabled",
                SceneClientPerformanceRules.DEFAULT_PARTIAL_RESUME_ENABLED);
        partialCacheQuotaMiB = readInt(performance, "partialCacheQuotaMiB",
                SceneClientPerformanceRules.DEFAULT_PARTIAL_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules::clampPartialCacheQuotaMiB);
        partialExpiryHours = readInt(performance, "partialExpiryHours",
                SceneClientPerformanceRules.DEFAULT_PARTIAL_EXPIRY_HOURS,
                SceneClientPerformanceRules::clampPartialExpiryHours);
        transferWindowChunks = readInt(performance, "transferWindowChunks",
                SceneClientPerformanceRules.DEFAULT_TRANSFER_WINDOW_CHUNKS,
                SceneClientPerformanceRules::clampTransferWindowChunks);
        meshBuildBudgetMsPerFrame = readInt(performance, "meshBuildBudgetMsPerFrame",
                SceneClientPerformanceRules.DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME,
                SceneClientPerformanceRules::clampMeshBuildBudgetMsPerFrame);
        memoryCacheQuotaMiB = readInt(performance, "memoryCacheQuotaMiB",
                SceneClientPerformanceRules.DEFAULT_MEMORY_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules::clampMemoryCacheQuotaMiB);
        meshCacheQuotaMiB = readInt(performance, "meshCacheQuotaMiB",
                SceneClientPerformanceRules.DEFAULT_MESH_CACHE_QUOTA_MIB,
                SceneClientPerformanceRules::clampMeshCacheQuotaMiB);
        meshBatchMinSections = readInt(performance, "meshBatchMinSections",
                SceneClientPerformanceRules.DEFAULT_MESH_BATCH_MIN_SECTIONS,
                SceneClientPerformanceRules::clampMeshBatchMinSections);
    }

    private static void applyDefaults() {
        customTitlePanoramaEnabled = DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED;
        requestTimeoutMs = SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS;
        maxChunkRetries = SceneClientPerformanceRules.DEFAULT_MAX_CHUNK_RETRIES;
        diskCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB;
        partialResumeEnabled = SceneClientPerformanceRules.DEFAULT_PARTIAL_RESUME_ENABLED;
        partialCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_PARTIAL_CACHE_QUOTA_MIB;
        partialExpiryHours = SceneClientPerformanceRules.DEFAULT_PARTIAL_EXPIRY_HOURS;
        transferWindowChunks = SceneClientPerformanceRules.DEFAULT_TRANSFER_WINDOW_CHUNKS;
        meshBuildBudgetMsPerFrame = SceneClientPerformanceRules.DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME;
        memoryCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_MEMORY_CACHE_QUOTA_MIB;
        meshCacheQuotaMiB = SceneClientPerformanceRules.DEFAULT_MESH_CACHE_QUOTA_MIB;
        meshBatchMinSections = SceneClientPerformanceRules.DEFAULT_MESH_BATCH_MIN_SECTIONS;
    }

    private static int readInt(JsonObject root, String key, int fallback,
                               java.util.function.IntUnaryOperator clamp) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        try {
            return clamp.applyAsInt(root.get(key).getAsInt());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        try {
            return root.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static synchronized boolean isCustomTitlePanoramaEnabled() {
        load();
        return customTitlePanoramaEnabled;
    }

    /**
     * Applies and persists the preference. A failed write restores the previous value so
     * the Mod Menu control never claims a state that will disappear after restart.
     */
    public static synchronized boolean setCustomTitlePanoramaEnabled(boolean enabled) {
        load();
        boolean previous = customTitlePanoramaEnabled;
        customTitlePanoramaEnabled = enabled;
        if (save()) return true;
        customTitlePanoramaEnabled = previous;
        return false;
    }

    public static synchronized int getRequestTimeoutMs() {
        load();
        return requestTimeoutMs;
    }

    /** 单次分片请求超时（毫秒），超时后按退避重试。 */
    public static synchronized boolean setRequestTimeoutMs(int millis) {
        load();
        int previous = requestTimeoutMs;
        requestTimeoutMs = SceneClientPerformanceRules.clampRequestTimeoutMs(millis);
        if (save()) return true;
        requestTimeoutMs = previous;
        return false;
    }

    public static synchronized int getMaxChunkRetries() {
        load();
        return maxChunkRetries;
    }

    /** 同一分片的最大重试次数；0 表示不重试。 */
    public static synchronized boolean setMaxChunkRetries(int retries) {
        load();
        int previous = maxChunkRetries;
        maxChunkRetries = SceneClientPerformanceRules.clampMaxChunkRetries(retries);
        if (save()) return true;
        maxChunkRetries = previous;
        return false;
    }

    /** 已完成资产（.hscene）的磁盘缓存配额上限（MiB）。 */
    public static synchronized int getDiskCacheQuotaMiB() {
        load();
        return diskCacheQuotaMiB;
    }

    public static synchronized boolean setDiskCacheQuotaMiB(int quotaMiB) {
        load();
        int previous = diskCacheQuotaMiB;
        diskCacheQuotaMiB = SceneClientPerformanceRules.clampDiskCacheQuotaMiB(quotaMiB);
        if (save()) return true;
        diskCacheQuotaMiB = previous;
        return false;
    }

    /** 是否保留未完成的分片、断线重连后只补缺失部分。 */
    public static synchronized boolean isPartialResumeEnabled() {
        load();
        return partialResumeEnabled;
    }

    public static synchronized boolean setPartialResumeEnabled(boolean enabled) {
        load();
        boolean previous = partialResumeEnabled;
        partialResumeEnabled = enabled;
        if (save()) return true;
        partialResumeEnabled = previous;
        return false;
    }

    /** 未完成分片文件（.part）的总配额上限（MiB），与 .hscene 配额相互独立。 */
    public static synchronized int getPartialCacheQuotaMiB() {
        load();
        return partialCacheQuotaMiB;
    }

    public static synchronized boolean setPartialCacheQuotaMiB(int quotaMiB) {
        load();
        int previous = partialCacheQuotaMiB;
        partialCacheQuotaMiB = SceneClientPerformanceRules.clampPartialCacheQuotaMiB(quotaMiB);
        if (save()) return true;
        partialCacheQuotaMiB = previous;
        return false;
    }

    /** 未完成分片的保留时长（小时），超期在下次启动时清理。 */
    public static synchronized int getPartialExpiryHours() {
        load();
        return partialExpiryHours;
    }

    public static synchronized boolean setPartialExpiryHours(int hours) {
        load();
        int previous = partialExpiryHours;
        partialExpiryHours = SceneClientPerformanceRules.clampPartialExpiryHours(hours);
        if (save()) return true;
        partialExpiryHours = previous;
        return false;
    }

    /** 同时在途的分片请求数；1 等于此前的停等式传输。 */
    public static synchronized int getTransferWindowChunks() {
        load();
        return transferWindowChunks;
    }

    public static synchronized boolean setTransferWindowChunks(int chunks) {
        load();
        int previous = transferWindowChunks;
        transferWindowChunks = SceneClientPerformanceRules.clampTransferWindowChunks(chunks);
        if (save()) return true;
        transferWindowChunks = previous;
        return false;
    }

    /** 每多少个 Section 才把网格分批；0 = 关闭分批（保持单份网格）。 */
    public static synchronized int getMeshBatchMinSections() {
        load();
        return meshBatchMinSections;
    }

    /** 每帧用于推进场景网格构建的时间预算（毫秒），所有背景共享。 */
    public static synchronized int getMeshBuildBudgetMsPerFrame() {
        load();
        return meshBuildBudgetMsPerFrame;
    }

    public static synchronized boolean setMeshBuildBudgetMsPerFrame(int millis) {
        load();
        int previous = meshBuildBudgetMsPerFrame;
        meshBuildBudgetMsPerFrame = SceneClientPerformanceRules.clampMeshBuildBudgetMsPerFrame(millis);
        if (save()) return true;
        meshBuildBudgetMsPerFrame = previous;
        return false;
    }

    /** 已解码场景资产驻留内存的配额（MiB）。 */
    public static synchronized int getMemoryCacheQuotaMiB() {
        load();
        return memoryCacheQuotaMiB;
    }

    public static synchronized boolean setMemoryCacheQuotaMiB(int quotaMiB) {
        load();
        int previous = memoryCacheQuotaMiB;
        memoryCacheQuotaMiB = SceneClientPerformanceRules.clampMemoryCacheQuotaMiB(quotaMiB);
        if (save()) return true;
        memoryCacheQuotaMiB = previous;
        return false;
    }

    /** 场景网格顶点缓冲合计的估算配额（MiB）。 */
    public static synchronized int getMeshCacheQuotaMiB() {
        load();
        return meshCacheQuotaMiB;
    }

    public static synchronized boolean setMeshCacheQuotaMiB(int quotaMiB) {
        load();
        int previous = meshCacheQuotaMiB;
        meshCacheQuotaMiB = SceneClientPerformanceRules.clampMeshCacheQuotaMiB(quotaMiB);
        if (save()) return true;
        meshCacheQuotaMiB = previous;
        return false;
    }

    private static boolean save() {
        JsonObject root = new JsonObject();
        root.addProperty("customTitlePanoramaEnabled", customTitlePanoramaEnabled);
        JsonObject performance = new JsonObject();
        performance.addProperty("requestTimeoutMs", requestTimeoutMs);
        performance.addProperty("maxChunkRetries", maxChunkRetries);
        performance.addProperty("diskCacheQuotaMiB", diskCacheQuotaMiB);
        performance.addProperty("partialResumeEnabled", partialResumeEnabled);
        performance.addProperty("partialCacheQuotaMiB", partialCacheQuotaMiB);
        performance.addProperty("partialExpiryHours", partialExpiryHours);
        performance.addProperty("transferWindowChunks", transferWindowChunks);
        performance.addProperty("meshBuildBudgetMsPerFrame", meshBuildBudgetMsPerFrame);
        performance.addProperty("meshBatchMinSections", meshBatchMinSections);
        performance.addProperty("memoryCacheQuotaMiB", memoryCacheQuotaMiB);
        performance.addProperty("meshCacheQuotaMiB", meshCacheQuotaMiB);
        root.add(SCENE_PERFORMANCE, performance);
        boolean saved = AtomicJsonFiles.writeJson(file(), root, GSON, true);
        if (!saved) {
            HabiTrainCore.LOGGER.error("无法保存客户端视觉配置 {}", file());
        }
        return saved;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
