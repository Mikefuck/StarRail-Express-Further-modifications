package com.habitrain.core.scene.client;

/**
 * 客户端移动场景统一生命周期入口。所有换会话/换世界/局终清理都从这里完成，
 * 避免网格、预览、下载流、音效、抖动或选区状态残留到下一局。
 */
public final class SceneClientRuntime {
    private SceneClientRuntime() {}

    public static void reset(String reason) {
        SceneRenderRuntime.getInstance().reset();
        SceneAssetCache.getInstance().resetSession();
        SceneAmbientSoundController.getInstance().stopSound();
        SceneShakeController.getInstance().updateSettings(null, false);
        SceneToolHud.getInstance().updateState("", null, 0L);
        SceneToolSelectionRenderer.getInstance().updateSelection(null);
        SceneOriginPlacementController.getInstance().reset();
    }

    /** 结束设置页预览，并恢复服务端正式状态对应的声音和微震。 */
    public static void stopPreview() {
        SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
        runtime.stopPreview();
        var state = runtime.getCurrentState();
        if (state != null && state.isActive() && runtime.isRuntimeMeshReady()) {
            SceneAmbientSoundController.getInstance().playSound(state.getProfile().getOutsideSound());
            SceneShakeController.getInstance().updateSettings(state.getProfile().getShake(), true);
        } else {
            SceneAmbientSoundController.getInstance().stopSound();
            SceneShakeController.getInstance().updateSettings(null, false);
        }
    }
}
