package com.habitrain.core.api.scene;

import java.util.Objects;

/**
 * 场景实例注册/更新结果。
 *
 * <p>所有写操作都返回它，因此调用方不需要"先查后写"，也不会因为静默失败而困惑：</p>
 * <pre>{@code
 * SceneSpawnResult result = SceneApi.instance().spawn(level, spec);
 * if (!result.isSuccess()) {
 *     LOGGER.warn("场景实例注册失败: {} - {}", result.status(), result.message());
 * }
 * }</pre>
 */
public record SceneSpawnResult(SceneSpawnStatus status, String instanceId, String message) {

    public SceneSpawnResult {
        status = status != null ? status : SceneSpawnStatus.INVALID_SPEC;
        instanceId = instanceId != null ? instanceId : "";
        message = message != null ? message : "";
    }

    public static SceneSpawnResult ok(String instanceId, String message) {
        return new SceneSpawnResult(SceneSpawnStatus.OK, instanceId, message);
    }

    public static SceneSpawnResult failure(SceneSpawnStatus status, String instanceId, String message) {
        return new SceneSpawnResult(status, instanceId, message);
    }

    public boolean isSuccess() {
        return status == SceneSpawnStatus.OK;
    }

    @Override
    public String toString() {
        return "SceneSpawnResult[" + status + (isSuccess() ? "" : " " + message)
                + (instanceId.isBlank() ? "" : " id=" + instanceId) + "]";
    }

    /** 注册结果状态。 */
    public enum SceneSpawnStatus {
        /** 已注册（或已按 upsert 语义更新）。 */
        OK,
        /** 结构非法（见 message）。 */
        INVALID_SPEC,
        /** 同 ID 实例已存在，且调用的是非 upsert 的 {@code spawn}。 */
        ALREADY_EXISTS,
        /** 全局开关 {@code sceneMotion.enabled=false}。 */
        GLOBAL_DISABLED,
        /** 服务端未运行 / 找不到目标维度。 */
        SERVER_UNAVAILABLE,
        /** 找不到指定维度。 */
        LEVEL_NOT_FOUND
    }
}
