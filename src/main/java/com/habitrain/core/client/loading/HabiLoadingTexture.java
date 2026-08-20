package com.habitrain.core.client.loading;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * 加载界面专用纹理加载器。
 * <p>
 * 优先从 ClassLoader 直接流式加载 NativeImage，确保在游戏刚启动（LoadingOverlay 期间，
 * ResourceManager 尚在 Reload 中）能够安全可靠地解码并上传纹理，防止黑屏或缺失材质。
 */
@Environment(EnvType.CLIENT)
public class HabiLoadingTexture extends SimpleTexture {

    private final String classpathResource;

    public HabiLoadingTexture(ResourceLocation location, String classpathResource) {
        super(location);
        this.classpathResource = classpathResource;
    }

    private InputStream openStream() {
        if (classpathResource == null) {
            return null;
        }
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource);
        if (is == null) {
            is = HabiLoadingTexture.class.getClassLoader().getResourceAsStream(classpathResource);
        }
        if (is == null && !classpathResource.startsWith("/")) {
            is = HabiLoadingTexture.class.getResourceAsStream("/" + classpathResource);
        }
        return is;
    }

    @Override
    protected TextureImage getTextureImage(ResourceManager resourceManager) {
        InputStream stream = openStream();
        if (stream != null) {
            try (InputStream input = stream) {
                NativeImage nativeImage = NativeImage.read(input);
                return new TextureImage(new TextureMetadataSection(false, true), nativeImage);
            } catch (IOException e) {
                return new TextureImage(e);
            }
        }

        return super.getTextureImage(resourceManager);
    }
}
