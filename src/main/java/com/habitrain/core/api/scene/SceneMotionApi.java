package com.habitrain.core.api.scene;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRuntimeState;
import com.habitrain.core.scene.server.SceneAssetStore;
import com.habitrain.core.scene.server.SceneContextResolver;
import com.habitrain.core.scene.server.SceneRuntimeCoordinator;
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
        return ConfigManager.getInstance().getSceneMotionSettings();
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
        ConfigManager.getInstance().markSceneMotionDirty();
    }

    /**
     * 查询指定服务端维度当前是否有场景处于运动状态。
     */
    public boolean isSceneActive(ServerLevel level) {
        if (level == null) return false;
        SceneRuntimeState state = SceneRuntimeCoordinator.getInstance().getRuntimeState(level);
        return state != null && state.isActive();
    }

    /**
     * 手动启动指定维度的场景运动。
     */
    public boolean startScene(ServerLevel level, String mapKey) {
        return SceneRuntimeCoordinator.getInstance().activate(level, mapKey);
    }

    /**
     * 手动停止指定维度的场景运动。
     */
    public boolean stopScene(ServerLevel level) {
        return SceneRuntimeCoordinator.getInstance().deactivate(level);
    }

    /**
     * 获取指定地图已发布的场景资产元数据描述符。
     */
    public SceneAssetDescriptor getAssetDescriptor(String mapKey) {
        return SceneAssetStore.getInstance().getDescriptor(mapKey);
    }

    /**
     * 注册自定义地图上下文解析器（替换默认的 SRE 解析器）。
     */
    public void registerContextResolver(SceneContextResolver resolver) {
        SceneRuntimeCoordinator.getInstance().setContextResolver(resolver);
    }
}
