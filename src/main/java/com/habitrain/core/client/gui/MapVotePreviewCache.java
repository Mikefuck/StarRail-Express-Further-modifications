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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地图预览图缓存：把服务端下发的预览图字节懒解码为 GPU 纹理。
 *
 * <p>所有解码/注册/释放都在渲染线程（屏幕 render 本就在渲染线程；接收 profile 包时
 * 只存 {@code byte[]}，不做 IO）。首次按 mapId 命中缓存时才解码一次，之后直接复用。
 * 跨局/换世界时由 {@link com.habitrain.core.client.ClientLifecycleHandler#resetState()}
 * 调用 {@link #clearAll()} 释放全部动态纹理。</p>
 *
 * <p>占位图是模组静态资源贴图，走 {@code getTexture()}，不创建/不释放 DynamicTexture。</p>
 */
@Environment(EnvType.CLIENT)
public final class MapVotePreviewCache {
    private static final ResourceLocation PLACEHOLDER =
            HabiTrainCore.id("textures/gui/map_vote/placeholder.png");

    private static final int MAX_ENTRIES = 16;

    private static final Map<String, Decoded> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

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
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Decoded decoded = decode(mapId, bytes);
        if (decoded == null) {
            return null;
        }
        CACHE.put(mapId, decoded);
        if (CACHE.size() > MAX_ENTRIES) {
            var it = CACHE.entrySet().iterator();
            if (it.hasNext()) {
                Decoded evicted = it.next().getValue();
                evicted.dynamic().close();
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
    }

    @Nullable
    private static Decoded decode(String mapId, byte[] bytes) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            int width = image.getWidth();
            int height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation id = Minecraft.getInstance().getTextureManager()
                    .register("map_vote_preview/" + mapId, texture);
            return new Decoded(id, texture, width, height);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("[MapVotePreviewCache] failed to decode preview for '{}'", mapId, e);
            return null;
        }
    }

    /** 解码后的纹理及其原始像素尺寸（绘制 UV 需要）。 */
    public record Decoded(ResourceLocation texture, DynamicTexture dynamic, int width, int height) {}
}
