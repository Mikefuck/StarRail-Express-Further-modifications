package com.habitrain.core.client.loading;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 哈比列车加载界面纹理定义与注册工具类。
 */
@Environment(EnvType.CLIENT)
public final class HabiLoadingScreenTextures {

    public static final ResourceLocation GAME_LOADING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "textures/gui/loading/game_loading.png");

    public static final ResourceLocation WORLD_LOADING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "textures/gui/loading/world_loading.png");

    public static final String GAME_LOADING_RESOURCE_PATH =
            "assets/habitrain_core/textures/gui/loading/game_loading.png";

    public static final String WORLD_LOADING_RESOURCE_PATH =
            "assets/habitrain_core/textures/gui/loading/world_loading.png";

    private HabiLoadingScreenTextures() {}

    /**
     * 注册/刷新加载页面纹理到 Minecraft TextureManager。
     */
    public static synchronized void registerTextures(Minecraft minecraft) {
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        minecraft.getTextureManager().register(GAME_LOADING_TEXTURE,
                new HabiLoadingTexture(GAME_LOADING_TEXTURE, GAME_LOADING_RESOURCE_PATH));
        minecraft.getTextureManager().register(WORLD_LOADING_TEXTURE,
                new HabiLoadingTexture(WORLD_LOADING_TEXTURE, WORLD_LOADING_RESOURCE_PATH));
    }
}
