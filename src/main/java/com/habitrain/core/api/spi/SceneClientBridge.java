package com.habitrain.core.api.spi;

import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.model.SceneInstance;
import com.habitrain.core.api.scene.model.SceneRuntimeState;

import java.util.List;

/**
 * 客户端场景运行时桥接接口（SPI）。
 *
 * <p>对应实现 {@code scene.client.SceneRenderRuntime}（另由
 * {@code scene.client.SceneClientRuntime} 提供静态的重同步请求）。
 * 去掉 {@code api.scene.client → scene.client} 反向依赖（审核 A2）。
 *
 * <p><b>仅客户端</b>：实现只在客户端环境装配；专用服务端上保持默认空值。
 */
public interface SceneClientBridge {

    default SceneRuntimeState getCurrentState() {
        return null;
    }

    default boolean isPreviewActive() {
        return false;
    }

    default List<SceneInstance> dynamicInstances() {
        return List.of();
    }

    default int dynamicInstanceCount() {
        return 0;
    }

    default SceneInstance dynamicInstance(String instanceId) {
        return null;
    }

    default boolean isDynamicInstanceMeshReady(String instanceId) {
        return false;
    }

    default SceneAssetDescriptor getManifest(String assetKey) {
        return SceneAssetDescriptor.EMPTY;
    }

    default long totalMeshBytes() {
        return 0L;
    }

    /** 请求服务端重新下发当前维度的全部 API 场景实例。 */
    default void requestInstanceResync() {
    }
}
