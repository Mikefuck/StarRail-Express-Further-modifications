package com.habitrain.core.scene.client;

/**
 * 客户端移动场景统一生命周期入口。所有换会话/换世界/局终清理都从这里完成，
 * 避免网格、预览、下载流、音效、抖动或选区状态残留到下一局。
 */
public final class SceneClientRuntime {
    private SceneClientRuntime() {}

    public static void reset(String reason) {
        SceneRenderRuntime.getInstance().reset();
        SceneBuildScheduler.getInstance().reset();
        SceneAssetCache.getInstance().resetSession();
        SceneAmbientSoundController.getInstance().stopSound();
        SceneShakeController.getInstance().updateSettings(null, false);
        resetEditorState();
    }

    /**
     * 配置页改动后刷新所有受影响的场景子系统。
     *
     * <p>放在这里而不是让配置页逐个知道有哪些子系统：解码缓存配额、网格构建预算与显存配额
     * 分属不同单例，但它们都是由同一份 {@code scenePerformance} 偏好驱动的。</p>
     */
    public static void applyClientConfig() {
        SceneAssetCache.getInstance().applyClientConfig();
        SceneRenderRuntime.getInstance().applyClientConfig();
    }

    /** A late SRE finish callback must not erase a lobby state already received from the server. */
    public static void onMatchFinished() {
        var state = SceneRenderRuntime.getInstance().getCurrentState();
        if (state != null && state.isActive()
                && com.habitrain.core.config.SceneMotionSettings.LOBBY_MAP_KEY.equals(state.getMapKey())) {
            stopPreview();
            resetEditorState();
        } else {
            reset("match_finished");
        }
    }

    private static void resetEditorState() {
        SceneToolHud.getInstance().updateState("", null, 0L);
        SceneToolSelectionRenderer.getInstance().updateSelection(null);
        com.habitrain.core.client.gui.menu.page.SceneMotionPage.clearRememberedEditorTarget();
        SceneOriginPlacementController.getInstance().reset();
        SceneStagingClientController.getInstance().reset();
    }

    /** 结束设置页预览，并恢复服务端正式状态对应的声音和微震。 */
    public static void stopPreview() {
        SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
        runtime.stopPreview();
        var state = runtime.getCurrentState();
        if (state != null && state.isActive()) {
            SceneAmbientSoundController.getInstance().playSound(state.getProfile().getOutsideSound());
            SceneShakeController.getInstance().updateSettings(state.getProfile().getShake(), true);
        } else {
            SceneAmbientSoundController.getInstance().stopSound();
            SceneShakeController.getInstance().updateSettings(null, false);
        }
    }
}
