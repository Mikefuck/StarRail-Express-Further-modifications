package com.habitrain.core.scene.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端资源包重载监听器。
 * <p>
 * 当客户端资源包发生重载时，清理旧的 SceneMaterialKey 缓存并通知 SceneRenderRuntime 关闭旧 VBO 重新烘焙场景。
 */
@Environment(EnvType.CLIENT)
public final class SceneResourceReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneResourceReloadListener.class.getSimpleName());
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "scene_mesh_reload");

    @Override
    public ResourceLocation getFabricId() {
        return ID;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        LOGGER.info("检测到客户端资源包重载，重置移动场景材质缓存并重新烘焙活动网格");
        SceneMaterialKey.clearRenderTypeCache();
        SceneRenderRuntime.getInstance().onResourceReload();
    }
}
