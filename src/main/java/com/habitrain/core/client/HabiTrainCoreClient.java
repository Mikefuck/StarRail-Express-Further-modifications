package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.render.TaskOverlayDrawer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 哈比列车任务API - 客户端初始化（组装类）。
 * <p>
 * 实例化并注册所有客户端 module，自身不承载业务逻辑：
 * <ul>
 *   <li>{@link NetworkReceiverRegistrar} - S2C 网络接收器注册</li>
 *   <li>{@link ShaderMonitor} - Iris 光影包实时监测</li>
 *   <li>{@link HudRegistrar} - HUD 叠加层与快捷键注册</li>
 *   <li>{@link ClientLifecycleHandler} - JOIN/DISCONNECT/游戏结束/配置保存生命周期</li>
 * </ul>
 * <p>
 * 光影监测状态存放于 {@link ClientStateHolder}。
 */
@Environment(EnvType.CLIENT)
public class HabiTrainCoreClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 审核 A2/B14：装配客户端侧 SPI（场景渲染运行时 + 菜单门控客户端状态）。
        // 与主入口一致，只在 core 生命周期作用域内生效。
        com.habitrain.core.internal.CoreLifecycleScope.run(() -> {
            com.habitrain.core.api.spi.CoreSpi.installSceneClient(
                    com.habitrain.core.scene.client.SceneRenderRuntime.getInstance());
            com.habitrain.core.api.spi.CoreSpi.installMenuGateClient(
                    com.habitrain.core.client.menu.MenuGateClientBridgeImpl.INSTANCE);
            com.habitrain.core.api.spi.RoleSpi.installClientExtension(
                    com.habitrain.core.role.client.RoleClientExtensionRegistry::new);
            com.habitrain.core.api.spi.RoleSpi.installActionClient(
                    () -> com.habitrain.core.client.role.RoleActionClientSession.INSTANCE);
        });
        com.habitrain.core.client.config.ClientVisualPreferences.load();
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(
                com.habitrain.core.scene.client.SceneCameraShakeRenderer::apply);
        HabiTrainCore.LOGGER.info("哈比列车任务API 客户端初始化完成");

        // S2C 网络接收器注册
        new NetworkReceiverRegistrar();

        // Iris 光影包实时监测（轮询检测切换）
        ShaderMonitor shaderMonitor = new ShaderMonitor();

        // HUD 叠加层 + 快捷键注册
        new HudRegistrar();
        EliminatedRestPromptState.registerLifecycle();

        // 任务点统一在世界渲染 LAST 阶段直绘，避免深度状态和延迟 buffer 重新遮挡。
        TaskOverlayDrawer.registerFinalPass();

        // 移动场景系统：世界渲染与配置器 HUD
        // Match SRE's proven scene-preview pass: the terrain/depth buffer is complete here,
        // while the context matrix still expects an explicit -camera world translation.
        // 场景网格构建的主驱动：每帧一次。必须挂在 render(context) 之后而不是它内部——
        // render 是 synchronized 且在没有活动场景时会提前返回，而预取构建在那种情况下
        // 依然要推进，也不该在渲染期间嵌套进入场景运行时的同步块。
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            com.habitrain.core.scene.client.SceneRenderRuntime.getInstance().render(context);
            com.habitrain.core.scene.client.SceneBuildScheduler.getInstance().pump();
        });
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT.register(
                context -> com.habitrain.core.scene.client.SceneToolSelectionRenderer.getInstance().render(context));
        com.habitrain.core.scene.client.SceneToolHud.init();
        com.habitrain.core.scene.client.SceneOriginPlacementController.init();
        com.habitrain.core.scene.client.SceneViewDistanceWarningController.getInstance().init();
        // 分片请求超时判定与退避重试需要一个与帧率无关的固定节奏。
        com.habitrain.core.scene.client.SceneClientTicker.init();
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(new com.habitrain.core.scene.client.SceneResourceReloadListener());
        com.habitrain.core.scene.client.compat.builtin.BuiltinClientSceneAdapters.registerClient();

        // 投稿职业客户端钩子（替罪羊本能伪装等）
        HabiRoleClientHooks.init();
        com.habitrain.core.client.role.RoleClientExtensionHooks.init();

        // 角色动作客户端结果 API：注册超时 tick
        com.habitrain.core.client.role.RoleActionClientSession.INSTANCE.registerTick();

        // 生命周期事件处理（JOIN / DISCONNECT / 游戏结束 / 配置保存回调）
        new ClientLifecycleHandler(shaderMonitor);

        // 加载界面纹理预注册
        try {
            com.habitrain.core.client.loading.HabiLoadingScreenTextures.registerTextures(net.minecraft.client.Minecraft.getInstance());
        } catch (Throwable ignored) {
        }

        // 确保 SREClient 的本能高亮缓存为线程安全的 ConcurrentHashMap
        try {
            if (!(io.wifi.starrailexpress.client.SREClient.cachedHighLightMap instanceof java.util.concurrent.ConcurrentHashMap)) {
                io.wifi.starrailexpress.client.SREClient.cachedHighLightMap = new java.util.concurrent.ConcurrentHashMap<>(
                        io.wifi.starrailexpress.client.SREClient.cachedHighLightMap != null ? io.wifi.starrailexpress.client.SREClient.cachedHighLightMap : java.util.Map.of()
                );
            }
        } catch (Throwable ignored) {
        }

        // 注：字幕报幕客户端接收由 SRE 4.3.0 原生注册（SREClient），
        //     SubtitleHUDPrefixFixMixin 仍拦截 enqueueFromPacket 做任务标题归一化。
    }
}
