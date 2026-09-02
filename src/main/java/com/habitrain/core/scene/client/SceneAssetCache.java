package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import com.habitrain.core.scene.network.SceneAssetChunkS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 客户端场景资产缓存与下载管理器（管理 .minecraft/habitrain_scene_cache/ 与内存 AssetData）。
 */
public final class SceneAssetCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAssetCache.class.getSimpleName());
    private static final String DIR_NAME = "habitrain_scene_cache";
    public static final long MAX_SINGLE_ASSET_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_TOTAL_CACHE_BYTES = 512L * 1024L * 1024L;
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HabiTrain-SceneCache-IO");
        t.setDaemon(true);
        return t;
    });

    private static final SceneAssetCache INSTANCE = new SceneAssetCache();

    public static SceneAssetCache getInstance() {
        return INSTANCE;
    }

    private File cacheDir;
    private final Map<String, SceneAssetCodec.AssetData> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, DownloadSession> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<SceneAssetCodec.AssetData>>> diskLoadWaiters = new ConcurrentHashMap<>();

    private static final class DownloadSession {
        final String sha256;
        final long totalSize;
        final File tempFile;
        final FileOutputStream fos;
        long bytesReceived = 0;
        final List<Consumer<SceneAssetCodec.AssetData>> waiters = new ArrayList<>();

        DownloadSession(String sha256, long totalSize, File tempFile, FileOutputStream fos, Consumer<SceneAssetCodec.AssetData> onComplete) {
            this.sha256 = sha256;
            this.totalSize = totalSize;
            this.tempFile = tempFile;
            this.fos = fos;
            if (onComplete != null) this.waiters.add(onComplete);
        }

        void addWaiter(Consumer<SceneAssetCodec.AssetData> waiter) {
            if (waiter != null) waiters.add(waiter);
        }
    }

    private SceneAssetCache() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        if (gameDir == null) {
            gameDir = new File(".");
        }
        this.cacheDir = new File(gameDir, DIR_NAME);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    /**
     * 获取或异步请求下载资产。若命中缓存则直接回调；若未命中则发起分片请求并在下载完成后回调。
     */
    public synchronized void getOrFetchAsset(SceneAssetDescriptor descriptor, Consumer<SceneAssetCodec.AssetData> onComplete) {
        if (descriptor == null || !descriptor.isValid()) {
            if (onComplete != null) onComplete.accept(null);
            return;
        }

        String sha256 = descriptor.sha256();
        String localFingerprint = com.habitrain.core.scene.asset.SceneRegistryFingerprint.calculate();
        if (descriptor.fingerprint() != null && !descriptor.fingerprint().isBlank()
                && !descriptor.fingerprint().equals(localFingerprint)) {
            LOGGER.error("场景资产方块注册表指纹不匹配，禁止加载: hash={}", descriptor.shortHash());
            if (onComplete != null) onComplete.accept(null);
            return;
        }
        if (descriptor.compressedSize() <= 0 || descriptor.compressedSize() > MAX_SINGLE_ASSET_BYTES) {
            LOGGER.warn("拒绝超出客户端单资产上限的场景资产: hash={}, bytes={}",
                    descriptor.shortHash(), descriptor.compressedSize());
            if (onComplete != null) onComplete.accept(null);
            return;
        }

        // 1. 检查内存缓存
        SceneAssetCodec.AssetData memoryData = memoryCache.get(sha256);
        if (memoryData != null) {
            if (onComplete != null) onComplete.accept(memoryData);
            return;
        }

        // 2. 检查本地磁盘缓存
        File cachedFile = new File(cacheDir, sha256 + ".hscene");
        if (cachedFile.exists()) {
            List<Consumer<SceneAssetCodec.AssetData>> waiters = diskLoadWaiters.get(sha256);
            if (waiters != null) {
                if (onComplete != null) waiters.add(onComplete);
                return;
            }
            List<Consumer<SceneAssetCodec.AssetData>> created = java.util.Collections.synchronizedList(new ArrayList<>());
            if (onComplete != null) created.add(onComplete);
            diskLoadWaiters.put(sha256, created);
            IO_EXECUTOR.execute(() -> loadDiskCache(descriptor, cachedFile));
            return;
        }

        // 3. 发起 C2S 分片下载
        DownloadSession existing = activeDownloads.get(sha256);
        if (existing != null) {
            existing.addWaiter(onComplete);
            LOGGER.debug("资产 {} 已在下载中", descriptor.shortHash());
            return;
        }

        try {
            File tempFile = new File(cacheDir, sha256 + ".download." + System.currentTimeMillis());
            FileOutputStream fos = new FileOutputStream(tempFile);
            DownloadSession session = new DownloadSession(sha256, descriptor.compressedSize(), tempFile, fos, onComplete);
            activeDownloads.put(sha256, session);

            LOGGER.info("发起场景资产下载: sha256={}, 大小={} bytes", descriptor.shortHash(), descriptor.compressedSize());
            requestNextChunk(sha256, 0L);
        } catch (IOException e) {
            LOGGER.error("创建下载临时文件失败: " + sha256, e);
            if (onComplete != null) onComplete.accept(null);
        }
    }

    private void requestNextChunk(String sha256, long offset) {
        if (ClientPlayNetworking.canSend(SceneAssetChunkRequestC2S.TYPE)) {
            ClientPlayNetworking.send(new SceneAssetChunkRequestC2S(sha256, offset, SceneAssetChunkRequestC2S.CHUNK_SIZE));
        }
    }

    /**
     * 接收服务端下发的分片数据。
     */
    public synchronized void handleChunk(SceneAssetChunkS2C chunk) {
        if (chunk == null) return;
        String sha256 = chunk.sha256();
        DownloadSession session = activeDownloads.get(sha256);
        if (session == null) return;

        try {
            if (chunk.chunkOffset() != session.bytesReceived || chunk.totalSize() != session.totalSize
                    || chunk.data().length <= 0
                    || session.bytesReceived + chunk.data().length > session.totalSize) {
                LOGGER.error("场景分片会话参数不匹配: hash={}, expectedOffset={}, actualOffset={}, expectedTotal={}, actualTotal={}",
                        sha256, session.bytesReceived, chunk.chunkOffset(), session.totalSize, chunk.totalSize());
                abortDownload(session);
                return;
            }
            long expectedCrc = chunk.crc32();
            long actualCrc = SceneAssetCodec.calculateCrc32(chunk.data(), 0, chunk.data().length);
            if (expectedCrc != actualCrc) {
                LOGGER.error("分片 CRC32 校验失败: sha256={}, offset={}", sha256, chunk.chunkOffset());
                abortDownload(session);
                return;
            }

            session.fos.write(chunk.data());
            session.bytesReceived += chunk.data().length;

            if (session.bytesReceived >= chunk.totalSize()) {
                // 下载完成
                session.fos.flush();
                session.fos.close();
                activeDownloads.remove(sha256);

                // 完整文件哈希、解压与磁盘移动放到后台，避免阻塞客户端渲染线程。
                IO_EXECUTOR.execute(() -> finishDownload(session));
            } else {
                // 请求下一分片
                requestNextChunk(sha256, session.bytesReceived);
            }
        } catch (Exception e) {
            LOGGER.error("写入场景分片失败", e);
            abortDownload(session);
        }
    }

    private void abortDownload(DownloadSession session) {
        try {
            session.fos.close();
        } catch (Exception ignored) {}
        session.tempFile.delete();
        activeDownloads.remove(session.sha256);
        notifyWaiters(session, null);
    }

    private void notifyWaiters(DownloadSession session, SceneAssetCodec.AssetData data) {
        for (Consumer<SceneAssetCodec.AssetData> waiter : List.copyOf(session.waiters)) {
            try {
                waiter.accept(data);
            } catch (Throwable t) {
                LOGGER.warn("场景资产完成回调异常", t);
            }
        }
        session.waiters.clear();
    }

    private void loadDiskCache(SceneAssetDescriptor descriptor, File cachedFile) {
        String sha256 = descriptor.sha256();
        SceneAssetCodec.AssetData data = null;
        try (FileInputStream fis = new FileInputStream(cachedFile)) {
            if (cachedFile.length() <= 0 || cachedFile.length() > MAX_SINGLE_ASSET_BYTES) {
                throw new IOException("Cached asset size is invalid");
            }
            byte[] bytes = fis.readAllBytes();
            if (!SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(sha256)) {
                throw new IOException("Cached asset hash mismatch");
            }
            data = SceneAssetCodec.decode(bytes);
        } catch (Exception e) {
            LOGGER.warn("读取本地场景缓存失败，将重新下载: " + sha256, e);
            cachedFile.delete();
        }
        SceneAssetCodec.AssetData loaded = data;
        Minecraft.getInstance().execute(() -> {
            List<Consumer<SceneAssetCodec.AssetData>> waiters = diskLoadWaiters.remove(sha256);
            if (loaded != null) {
                memoryCache.put(sha256, loaded);
                cachedFile.setLastModified(System.currentTimeMillis());
                if (waiters != null) for (Consumer<SceneAssetCodec.AssetData> waiter : List.copyOf(waiters)) waiter.accept(loaded);
            } else if (waiters != null) {
                for (Consumer<SceneAssetCodec.AssetData> waiter : List.copyOf(waiters)) {
                    getOrFetchAsset(descriptor, waiter);
                }
            }
        });
    }

    private void finishDownload(DownloadSession session) {
        SceneAssetCodec.AssetData data = null;
        try {
            byte[] fullBytes = Files.readAllBytes(session.tempFile.toPath());
            String fullHash = SceneAssetCodec.calculateSha256(fullBytes);
            if (!fullHash.equalsIgnoreCase(session.sha256)) {
                throw new IOException("Scene asset SHA-256 mismatch");
            }
            data = SceneAssetCodec.decode(fullBytes);
            File finalFile = new File(cacheDir, session.sha256 + ".hscene");
            Files.move(session.tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            finalFile.setLastModified(System.currentTimeMillis());
            enforceCacheLimit();
        } catch (Exception e) {
            LOGGER.error("完整场景资产校验或解码失败: " + session.sha256, e);
            session.tempFile.delete();
        }
        SceneAssetCodec.AssetData loaded = data;
        Minecraft.getInstance().execute(() -> {
            if (loaded != null) memoryCache.put(session.sha256, loaded);
            notifyWaiters(session, loaded);
        });
    }

    private void enforceCacheLimit() {
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".hscene"));
        if (files == null) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        long total = 0L;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= MAX_TOTAL_CACHE_BYTES) break;
            long length = file.length();
            if (file.delete()) total -= length;
        }
    }

    /** JOIN/DISCONNECT/换服时关闭所有流并删除未完成文件，避免跨会话串包。 */
    public synchronized void resetSession() {
        for (DownloadSession session : List.copyOf(activeDownloads.values())) {
            abortDownload(session);
        }
        activeDownloads.clear();
        for (List<Consumer<SceneAssetCodec.AssetData>> waiters : diskLoadWaiters.values()) {
            for (Consumer<SceneAssetCodec.AssetData> waiter : List.copyOf(waiters)) waiter.accept(null);
        }
        diskLoadWaiters.clear();
        memoryCache.clear();
    }

    public SceneAssetCodec.AssetData getFromMemory(String sha256) {
        return sha256 != null ? memoryCache.get(sha256) : null;
    }

    public void clearMemoryCache() {
        memoryCache.clear();
    }
}
