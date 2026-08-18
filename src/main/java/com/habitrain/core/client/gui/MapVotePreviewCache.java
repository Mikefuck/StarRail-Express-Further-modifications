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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 地图预览图缓存：把服务端下发的预览图字节懒解码为 GPU 纹理。
 *
 * <p>所有解码/注册/释放都在渲染线程（屏幕 render 本就在渲染线程；接收 profile 包时
 * 只存 {@code byte[]}，不做 IO）。首次按 mapId 命中缓存时才解码一次，之后直接复用。
 * 跨局/换世界时由 {@link com.habitrain.core.client.ClientLifecycleHandler#resetState()}
 * 调用 {@link #clearAll()} 释放全部动态纹理。</p>
 *
 * <p>解码失败的 mapId 进入负缓存（{@link #FAILED_IDS}），避免档案卡每帧重复
 * IO 解码 + 刷 WARN（审查 S3）；纹理注册名经 {@link #sanitizePathSegment(String)}
 * 清洗，mapId 含大写/中文/冒号等非法字符时不再抛 IllegalArgumentException。</p>
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

    /**
     * 被逐出但尚未释放的纹理：OptionVoteScreen 交叉淡入（约 200ms）期间旧图仍可能
     * 在绘制，立即 close 会释放仍在使用的 GL id（审查 L26）。延迟约 20 帧（> 淡入
     * 时长）后统一释放；clearAll 时立即释放。
     */
    private static final List<Decoded> PENDING_CLOSE = new ArrayList<>();
    private static int deferCounter = 0;
    private static final int CLOSE_DEFER_FRAMES = 20;

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
        flushPendingCloses();
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
        Decoded decoded = decode(mapId, bytes);
        if (decoded == null) {
            FAILED_IDS.add(mapId);
            return null;
        }
        CACHE.put(mapId, decoded);
        if (CACHE.size() > MAX_ENTRIES) {
            var it = CACHE.entrySet().iterator();
            if (it.hasNext()) {
                Decoded evicted = it.next().getValue();
                PENDING_CLOSE.add(evicted);
                it.remove();
            }
        }
        return decoded;
    }

    /** 占位贴图（模组静态资源，不随缓存释放）。 */
    public static ResourceLocation placeholder() {
        return PLACEHOLDER;
    }

    /** 释放全部动态纹理（跨局/换世界）。 */
    public static void clearAll() {
        for (Decoded entry : CACHE.values()) {
            entry.dynamic().close();
        }
        CACHE.clear();
        for (Decoded entry : PENDING_CLOSE) {
            entry.dynamic().close();
        }
        PENDING_CLOSE.clear();
        deferCounter = 0;
        FAILED_IDS.clear();
    }

    /** 每帧推进延迟释放计数；超过延迟帧数后释放被逐出的纹理。 */
    private static void flushPendingCloses() {
        if (PENDING_CLOSE.isEmpty()) {
            return;
        }
        if (++deferCounter >= CLOSE_DEFER_FRAMES) {
            for (Decoded entry : PENDING_CLOSE) {
                entry.dynamic().close();
            }
            PENDING_CLOSE.clear();
            deferCounter = 0;
        }
    }

    /**
     * 把 mapId 清洗成合法的 ResourceLocation 路径段：小写化、非法字符（含
     * {@code :}、大写、中文、空格）替换为下划线，并追加原 id 的 hash 后缀防
     * 清洗后碰撞。
     */
    private static String sanitizePathSegment(String mapId) {
        String cleaned = mapId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return cleaned + "_" + Integer.toHexString(mapId.hashCode());
    }

    @Nullable
    private static Decoded decode(String mapId, byte[] bytes) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            int width = image.getWidth();
            int height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation id = Minecraft.getInstance().getTextureManager()
                    .register("map_vote_preview/" + sanitizePathSegment(mapId), texture);
            return new Decoded(id, texture, width, height);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("[MapVotePreviewCache] failed to decode preview for '{}'", mapId, e);
            return null;
        }
    }

    /** 解码后的纹理及其原始像素尺寸（绘制 UV 需要）。 */
    public record Decoded(ResourceLocation texture, DynamicTexture dynamic, int width, int height) {}
}
