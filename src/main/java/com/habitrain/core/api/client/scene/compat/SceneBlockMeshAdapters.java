package com.habitrain.core.api.client.scene.compat;

import com.habitrain.core.scene.client.compat.SceneBlockMeshAdapterRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 移动场景客户端方块网格适配器公共注册入口。
 */
@Environment(EnvType.CLIENT)
public final class SceneBlockMeshAdapters {
    private SceneBlockMeshAdapters() {}

    /**
     * 注册一个客户端方块网格发射适配器。
     */
    public static void register(SceneBlockMeshAdapter adapter) {
        SceneBlockMeshAdapterRegistry.getInstance().register(adapter);
    }
}
