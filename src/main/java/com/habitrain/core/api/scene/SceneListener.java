package com.habitrain.core.api.scene;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;

/**
 * 移动场景系统服务端事件监听器（全部方法都有空实现，按需重写）。
 *
 * <p>通过 {@code SceneApi.instance().addListener(listener)} 注册。回调都在服务端主线程
 * （tick / 玩家事件）里同步触发，因此回调里可以直接操作世界与玩家；请保持轻薄，重活请自行
 * 转发到后台线程或下一个 tick。</p>
 */
public interface SceneListener {

    /** 一个 API 场景实例被注册。 */
    default void onInstanceSpawned(SceneInstanceView instance) {}

    /** 一个 API 场景实例被更新（含启停、改参数、换资产）。 */
    default void onInstanceUpdated(SceneInstanceView instance) {}

    /**
     * 一个 API 场景实例被回收。
     *
     * @param reason 回收原因：{@code despawn} / {@code expired} / {@code replaced} / {@code reset}
     */
    default void onInstanceRemoved(SceneInstanceView instance, String reason) {}

    /** 地图级（配置页）移动场景在某个维度启动。 */
    default void onMapSceneStarted(net.minecraft.server.level.ServerLevel level, String mapKey) {}

    /** 地图级（配置页）移动场景在某个维度停止。 */
    default void onMapSceneStopped(net.minecraft.server.level.ServerLevel level, String mapKey) {}

    /** 场景资产发布或热更新完成（含初始生成与手动发布）。 */
    default void onAssetPublished(String assetKey, SceneAssetDescriptor descriptor) {}

    /** 所有维度重新开始同步实例（服务器启动、客户端请求重同步、全局开关重新打开）。 */
    default void onInstancesResynced() {}
}
