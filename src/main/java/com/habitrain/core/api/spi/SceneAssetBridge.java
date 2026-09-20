package com.habitrain.core.api.spi;

import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;

import java.util.Map;

/**
 * 场景资产索引桥接接口（SPI）。
 *
 * <p>对应实现 {@code scene.server.SceneAssetStore}。去掉
 * {@code api.scene.SceneApi → scene.server} 反向依赖（审核 A2）。
 */
public interface SceneAssetBridge {

    default SceneAssetDescriptor getDescriptor(String assetKey) {
        return null;
    }

    default Map<String, SceneAssetDescriptor> getAllDescriptors() {
        return Map.of();
    }

    default void deleteAsset(String assetKey) {
    }

    default boolean saveAsset(String assetKey, byte[] compressedBytes, SceneAssetDescriptor descriptor) {
        return false;
    }
}
