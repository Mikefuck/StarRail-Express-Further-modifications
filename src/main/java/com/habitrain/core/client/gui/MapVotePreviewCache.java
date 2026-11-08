package com.habitrain.core.client.gui;

import com.habitrain.core.HabiTrainCore;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.Util;
import java.util.Map;
import java.util.Set;

/**
 * 地图预览图缓存：把服务端下发的预览图字节懒解码为 GPU 纹理。
 *
 * <p>PNG 解码在有界后台队列执行；GL 注册/释放只在渲染线程执行。首次访问先返回占位图，
 * 完成后按请求身份安装纹理，失效或断线后的结果只释放，不回填缓存。
 * 跨局/换世界时由 {@link com.habitrain.core.client.ClientLifecycleHandler#resetState()}
 * 调用 {@link #clearAll()} 释放全部动态纹理。</p>
 *
 * <p>解码失败的 mapId 进入负缓存（{@link #FAILED_IDS}），避免档案卡每帧重复
 * IO 解码 + 刷 WARN；显式纹理 ID 不依赖外部地图名。</p>
 *
 * <p>占位图是模组静态资源贴图，走 {@code getTexture()}，不创建/不释放 DynamicTexture。</p>
 */
@Environment(EnvType.CLIENT)
public final class MapVotePreviewCache {
    private static final ResourceLocation PLACEHOLDER =
            HabiTrainCore.id("textures/gui/map_vote/placeholder.png");

    private static final int MAX_ENTRIES = 16;

    /** 负缓存：解码失败的 mapId，防止每帧重试（审查 S3）。clearAll 时清空。 */
    private static final Set<String> FAILED_IDS = new HashSet<>();

    private static final Map<String, Decoded> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

    // Entries stay alive through deferred GUI batches and preview fades. Maintenance runs
    // once per client tick, never once per getOrDecode call/card.
    private static final List<PendingClose> PENDING_CLOSE = new ArrayList<>();
    private static final long CLOSE_GRACE_MS = 500L;
    private record PendingClose(Decoded decoded, long closeAfter) {}
    private static final Map<String, Object> IN_FLIGHT = new LinkedHashMap<>();
    private static final ThreadPoolExecutor DECODER = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_ENTRIES), task -> {
        Thread thread = new Thread(task, "HabiTrain-PreviewDecode");
        thread.setDaemon(true);
        return thread;
    });
    private static long textureSequence;
    private static final Object HANDOFF_LOCK = new Object();
    private static final Set<NativeImage> AWAITING_UPLOAD = new HashSet<>();
    private static boolean stopping;

    private MapVotePreviewCache() {}

    /**
     * 取地图预览纹理；bytes 为空或解码失败返回 null（调用方回退占位贴图）。
     * 必须在渲染线程调用。
     */
    @Nullable
    public static Decoded getOrDecode(String mapId, @Nullable byte[] bytes) {
        if (mapId == null || mapId.isBlank()) {
            return null;
        }
        Decoded cached = CACHE.get(mapId);
        if (cached != null) {
            return cached;
        }
        if (FAILED_IDS.contains(mapId)) {
            return null;
        }
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (IN_FLIGHT.containsKey(mapId)) return null;
        if (!PreviewImageLimits.accepts(bytes)) {
            FAILED_IDS.add(mapId);
            return null;
        }
        Object ticket = new Object();
        IN_FLIGHT.put(mapId, ticket);
        byte[] snapshot = bytes.clone();
        try {
            DECODER.execute(() -> {
                NativeImage decoded = null;
                try {
                    decoded = NativeImage.read(new ByteArrayInputStream(snapshot));
                    NativeImage image = decoded;
                    synchronized (HANDOFF_LOCK) {
                        if (stopping) return; // finally closes the untransferred native image
                        AWAITING_UPLOAD.add(image);
                        Minecraft.getInstance().execute(() -> {
                            synchronized (HANDOFF_LOCK) {
                                if (!AWAITING_UPLOAD.remove(image)) return;
                            }
                            install(mapId, ticket, image);
                        });
                        decoded = null;
                    }
                } catch (Exception failure) {
                    HabiTrainCore.LOGGER.warn("Failed to decode map preview '{}'", mapId, failure);
                    Minecraft.getInstance().execute(() -> {
                        if (IN_FLIGHT.remove(mapId, ticket)) FAILED_IDS.add(mapId);
                    });
                } finally {
                    if (decoded != null) decoded.close();
                }
            });
        } catch (RejectedExecutionException busy) {
            IN_FLIGHT.remove(mapId, ticket); // retry on a later frame
        }
        return null;
    }

    private static void install(String mapId, Object ticket, NativeImage image) {
        if (!IN_FLIGHT.remove(mapId, ticket)) {
            image.close();
            return;
        }
        DynamicTexture texture = null;
        ResourceLocation id = HabiTrainCore.id("map_vote_preview/" + Long.toUnsignedString(++textureSequence));
        try {
            int width = image.getWidth(), height = image.getHeight();
            texture = new DynamicTexture(image);
            image = null;
            // Explicit IDs avoid TextureManager's unbounded dynamic-prefix counters.
            Minecraft.getInstance().getTextureManager().register(id, texture);
            CACHE.put(mapId, new Decoded(id, texture, width, height));
            texture = null;
            if (CACHE.size() > MAX_ENTRIES) {
                var it = CACHE.entrySet().iterator();
                retire(it.next().getValue());
                it.remove();
            }
        } catch (Exception failure) {
            FAILED_IDS.add(mapId);
            Minecraft.getInstance().getTextureManager().release(id);
            HabiTrainCore.LOGGER.warn("Failed to upload map preview '{}'", mapId, failure);
        } finally {
            if (texture != null) texture.close();
            if (image != null) image.close();
        }
    }

    private static void retire(Decoded decoded) {
        PENDING_CLOSE.add(new PendingClose(decoded, Util.getMillis() + CLOSE_GRACE_MS));
    }

    /** 占位贴图（模组静态资源，不随缓存释放）。 */
    public static ResourceLocation placeholder() {
        return PLACEHOLDER;
    }

    /**
     * 使单张地图的纹理缓存失效（在收到新档案或上传新图时调用）。
     */
    public static void invalidate(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return;
        }
        IN_FLIGHT.remove(mapId);
        FAILED_IDS.remove(mapId);
        Decoded old = CACHE.remove(mapId);
        if (old != null) {
            retire(old);
        }
    }

    /** 释放全部动态纹理（跨局/换世界）。 */
    public static void clearAll() {
        IN_FLIGHT.clear();
        DECODER.getQueue().clear();
        synchronized (HANDOFF_LOCK) {
            for (NativeImage image : AWAITING_UPLOAD) image.close();
            AWAITING_UPLOAD.clear();
        }
        for (Decoded entry : CACHE.values()) {
            entry.close();
        }
        CACHE.clear();
        for (PendingClose entry : PENDING_CLOSE) {
            entry.decoded.close();
        }
        PENDING_CLOSE.clear();
        FAILED_IDS.clear();
    }

    /** Run on the render thread even when no preview is visible. */
    public static void tick() {
        long now = Util.getMillis();
        var it = PENDING_CLOSE.iterator();
        while (it.hasNext()) {
            PendingClose entry = it.next();
            if (now >= entry.closeAfter()) {
                entry.decoded().close();
                it.remove();
            }
        }
    }

    public static void shutdown() {
        synchronized (HANDOFF_LOCK) { stopping = true; }
        clearAll();
        DECODER.shutdownNow();
    }

    /** 解码后的纹理及其原始像素尺寸（绘制 UV 需要）。 */
    public record Decoded(ResourceLocation texture, DynamicTexture dynamic, int width, int height) {
        public void close() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && texture != null) {
                try {
                    // TextureManager.release removes the registration and closes the AbstractTexture.
                    mc.getTextureManager().release(texture);
                    return;
                } catch (Exception ignored) {
                }
            }
            if (dynamic != null) {
                dynamic.close();
            }
        }
    }
}
