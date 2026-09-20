package com.habitrain.core.api.spi;

import com.habitrain.core.api.scene.model.SceneBounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 世界区域捕获桥接接口（SPI）。
 *
 * <p>对应实现 {@code scene.server.SceneCaptureService}。去掉
 * {@code api.scene.SceneApi → scene.server} 反向依赖（审核 A2）。
 */
public interface SceneCaptureBridge {

    default boolean requestCapture(ServerLevel level, String assetKey, SceneBounds bounds, ServerPlayer requester) {
        return false;
    }

    default boolean cancelCapture(UUID requesterPlayerId, String reason) {
        return false;
    }
}
