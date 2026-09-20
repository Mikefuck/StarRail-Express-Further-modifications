package com.habitrain.core.api.spi;

import com.habitrain.core.api.scene.SceneContextResolver;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.model.SceneRuntimeState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 地图级（配置页）场景运行时桥接接口（SPI）。
 *
 * <p>对应实现 {@code scene.server.SceneRuntimeCoordinator}。去掉
 * {@code api.scene.SceneApi → scene.server} 反向依赖（审核 A2）。
 */
public interface SceneRuntimeBridge {

    default void setContextResolver(SceneContextResolver resolver) {
    }

    default SceneContextResolver.SceneContext resolveContext(ServerLevel level) {
        return null;
    }

    default SceneRuntimeState getRuntimeState(ServerLevel level) {
        return null;
    }

    default boolean activate(ServerLevel level, String mapKey) {
        return false;
    }

    default boolean deactivate(ServerLevel level) {
        return false;
    }

    default void onAssetPublished(MinecraftServer server, String mapKey, SceneAssetDescriptor descriptor) {
    }
}
