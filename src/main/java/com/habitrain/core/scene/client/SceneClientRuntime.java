package com.habitrain.core.scene.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.habitrain.core.scene.network.SceneInstanceResyncC2S;

/**
 * 客户端移动场景统一生命周期入口。所有换会话/换世界/局终清理都从这里完成，
 * 避免网格、预览、下载流、音效、抖动或选区状态残留到下一局。
 */
@Environment(EnvType.CLIENT)
public final class SceneClientRuntime {
    private SceneClientRuntime() {}

    public static void reset(String reason) {
        SceneRenderRuntime.getInstance().reset();
        SceneBuildScheduler.getInstance().reset();
        SceneAssetCache.getInstance().resetSession();
        SceneAmbientSoundController.getInstance().stopAllSounds();
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
                && com.habitrain.core.api.scene.SceneMotionSettings.LOBBY_MAP_KEY.equals(state.getMapKey())) {
            stopPreview();
            resetEditorState();
        } else {
            reset("match_finished");
        }
        // 上面的 reset 会把 API 场景实例与网格一并清掉，但服务端并不知道客户端做过这件事
        // （既没换会话也没换维度，服务端的"已下发"认知仍然有效）。这里主动要一次全量重同步，
        // 让外部 Mod 注册的场景在对局结束后立刻回来。
        requestInstanceResync();
    }

    /**
     * 请求服务端重新下发当前维度的全部 API 场景实例。
     *
     * <p>幂等、带服务端冷却；未连接时安全地什么都不做。</p>
     */
    public static void requestInstanceResync() {
        try {
            if (ClientPlayNetworking.canSend(SceneInstanceResyncC2S.TYPE)) {
                ClientPlayNetworking.send(new SceneInstanceResyncC2S());
            }
        } catch (Throwable ignored) {
            // 连接尚未就绪/正在断开：无需重同步。
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
