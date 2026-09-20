package com.habitrain.core.api.scene;

import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.model.SceneProfile;
import com.habitrain.core.api.scene.model.SceneRuntimeState;
import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/**
 * 移动场景系统公开 API（供外部 Mod 读写场景位移配置、手动启停场景与扩展地图上下文解析）。
 */
public final class SceneMotionApi {
    private static final SceneMotionApi INSTANCE = new SceneMotionApi();

    public static SceneMotionApi instance() {
        return INSTANCE;
    }

    private SceneMotionApi() {}

    /**
     * 获取场景系统全局配置根节点。
     */
    public SceneMotionSettings getSettings() {
        return CoreSpi.sceneConfig().getSceneMotionSettings();
    }

    /**
     * 获取指定地图的场景运动配置（未配置则返回默认值）。
     */
    public SceneProfile getProfile(String mapKey) {
        return getSettings().getProfile(mapKey);
    }

    /**
     * 保存或更新指定地图的场景运动配置并标记落盘。
     */
    public void setProfile(String mapKey, SceneProfile profile) {
        Objects.requireNonNull(profile, "profile cannot be null");
        String key = (mapKey != null && !mapKey.isBlank()) ? mapKey : "__default__";
        getSettings().profiles.put(key, profile);
        CoreSpi.sceneConfig().markSceneMotionDirty();
    }

    /**
     * 查询指定服务端维度当前是否有场景处于运动状态。
     */
    public boolean isSceneActive(ServerLevel level) {
        if (level == null) return false;
        SceneRuntimeState state = CoreSpi.sceneRuntime().getRuntimeState(level);
        return state != null && state.isActive();
    }

    /**
     * 手动启动指定维度的场景运动。
     */
    public boolean startScene(ServerLevel level, String mapKey) {
        return CoreSpi.sceneRuntime().activate(level, mapKey);
    }

    /**
     * 手动停止指定维度的场景运动。
     */
    public boolean stopScene(ServerLevel level) {
        return CoreSpi.sceneRuntime().deactivate(level);
    }

    /**
     * 获取指定地图已发布的场景资产元数据描述符。
     *
     * <p><b>审核 A-10</b>：本方法过去直接返回桥接结果，未发布 / 键不存在时为 {@code null}，
     * 而 {@code SceneApi.asset(String)} 已经明确用 {@link SceneAssetDescriptor#EMPTY} 兜底——
     * 同一个概念两个公开入口返回两种「空」（{@code null} vs {@code EMPTY}），
     * 调用方只要复制另一处的写法就会 NPE。现在统一返回 {@code EMPTY}
     * （{@link SceneAssetDescriptor#isValid()} 为 {@code false}），调用方无需判空。
     */
    public SceneAssetDescriptor getAssetDescriptor(String mapKey) {
        SceneAssetDescriptor descriptor = CoreSpi.sceneAssets().getDescriptor(mapKey);
        return descriptor != null ? descriptor : SceneAssetDescriptor.EMPTY;
    }

    /**
     * 注册自定义地图上下文解析器（替换默认的 SRE 解析器）。
     */
    public void registerContextResolver(SceneContextResolver resolver) {
        CoreSpi.sceneRuntime().setContextResolver(resolver);
    }
}
