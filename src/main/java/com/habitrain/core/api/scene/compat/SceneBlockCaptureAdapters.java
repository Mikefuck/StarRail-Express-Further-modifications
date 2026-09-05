package com.habitrain.core.api.scene.compat;

import com.habitrain.core.scene.compat.SceneBlockCaptureAdapterRegistry;

/**
 * 移动场景服务端方块捕获适配器公共注册入口。
 */
public final class SceneBlockCaptureAdapters {
    private SceneBlockCaptureAdapters() {}

    /**
     * 注册一个方块捕获适配器。
     */
    public static void register(SceneBlockCaptureAdapter adapter) {
        SceneBlockCaptureAdapterRegistry.getInstance().register(adapter);
    }
}
