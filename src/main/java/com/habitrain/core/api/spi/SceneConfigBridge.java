package com.habitrain.core.api.spi;

import com.habitrain.core.api.scene.SceneMotionSettings;

/**
 * 场景配置根桥接接口（SPI）。
 *
 * <p>公开层需要读取 {@code sceneMotion} 配置节点并在修改后标记落盘，但
 * {@code ConfigManager} 属于 {@code config} 实现包。收成接口后去掉
 * {@code api.scene.* → config} 反向依赖（审核 A2）。
 *
 * <p>默认实现返回一个新建的默认配置，保证未装配时公开层仍可安全读取。
 */
public interface SceneConfigBridge {

    default SceneMotionSettings getSceneMotionSettings() {
        return SceneMotionSettings.createDefault();
    }

    default void markSceneMotionDirty() {
    }
}
