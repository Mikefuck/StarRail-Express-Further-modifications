package com.habitrain.core.client;

import com.habitrain.core.client.role.sre.HabiRoleClientHooks;
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
        // A built-in patch loads above normal mod assets; root assets alone may
        // lose to SRE's own language file in the production resource stack.
        boolean languagePatch = net.fabricmc.fabric.api.resource.ResourceManagerHelper.registerBuiltinResourcePack(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("habitrain_core", "language_fixes"),
                net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("habitrain_core").orElseThrow(),
                net.minecraft.network.chat.Component.literal("HabiTrain Core - Language fixes"),
                net.fabricmc.fabric.api.resource.ResourcePackActivationType.ALWAYS_ENABLED);
        if (!languagePatch) {
            throw new IllegalStateException("HabiTrain Core language compatibility pack is missing");
        }
        com.habitrain.core.config.ConfigManager.getInstance().installClientSession();
        com.habitrain.core.client.config.ClientConfigSubmission.init();
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
            com.habitrain.core.api.spi.RoleSpi.installStateClient(
                    () -> com.habitrain.core.client.role.RoleStateClientService.INSTANCE);
            com.habitrain.core.api.spi.RoleSpi.installScreenClient(
                    () -> com.habitrain.core.client.role.RoleScreenDispatcher.INSTANCE);
        });
        com.habitrain.core.client.config.ClientVisualPreferences.load();
        // 结算纪念车票的做旧/燃烧与 MVP 人像着色器
        com.habitrain.core.client.gui.TicketShaders.register();
        com.habitrain.core.scene.client.SceneShaders.register();
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
        EliminatedRestCommandTip.register();

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
        // 上局结算看板：方块上一层的木质沙盘、同步回放与荣誉屏（自带视锥/距离裁剪与网格生命周期）
        com.habitrain.core.scene.client.replay.ReplayBoardRenderer.init();
        com.habitrain.core.scene.client.SceneToolHud.init();
        com.habitrain.core.client.gui.scene.SceneOriginPlacementController.init();
        com.habitrain.core.scene.client.SceneViewDistanceWarningController.getInstance().init();
        // 分片请求超时判定与退避重试需要一个与帧率无关的固定节奏。
        com.habitrain.core.scene.client.SceneClientTicker.init();
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(new com.habitrain.core.scene.client.SceneResourceReloadListener());
        com.habitrain.core.scene.client.compat.builtin.BuiltinClientSceneAdapters.registerClient();

        // 投稿职业客户端钩子（替罪羊本能伪装等）
        HabiRoleClientHooks.init();
        com.habitrain.core.client.tailor.DollTailorClient.init();
        com.habitrain.core.client.idiot.IdiotClient.init();
        com.habitrain.core.client.role.RoleClientExtensionHooks.init();

        // 角色动作客户端结果 API：注册超时 tick
        com.habitrain.core.client.role.RoleActionClientSession.INSTANCE.registerTick();

        // 生命周期事件处理（JOIN / DISCONNECT / 游戏结束 / 配置保存回调）
        new ClientLifecycleHandler(shaderMonitor);

        // 注：加载界面纹理由 HabiLoadingScreenTextures.registerTextures 幂等按需注册；
        //     SREClient.cachedHighLightMap 由 InstinctCacheFixMixin 在 <clinit> 统一替换为 ConcurrentHashMap；
        //     字幕报幕客户端接收由 SRE 4.3.0 原生注册（SREClient），
        //     SubtitleHUDPrefixFixMixin 仍拦截 enqueueFromPacket 做任务标题归一化。
    }
}
