package com.habitrain.core.internal;

import com.habitrain.core.api.spi.CoreSpi;
import com.habitrain.core.api.spi.RoleSpi;
import com.habitrain.core.api.spi.TaskPoolCacheBridge;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MenuGateBridgeImpl;
import com.habitrain.core.game.sre.RestAreaBridgeImpl;
import com.habitrain.core.game.sre.SreExtraSlotReclaimBridge;
import com.habitrain.core.game.sre.SreRuntimeBridgeImpl;
import com.habitrain.core.role.override.RoleVisibilityBridgeImpl;
import com.habitrain.core.scene.server.SceneAssetStore;
import com.habitrain.core.scene.server.SceneCaptureService;
import com.habitrain.core.scene.server.SceneInstanceService;
import com.habitrain.core.scene.server.SceneRuntimeCoordinator;
import com.habitrain.core.task.TaskPoolBuilder;
import com.habitrain.core.vote.VoteBridgeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 装配公开层的 SPI 实现（{@link CoreSpi}）。
 *
 * <p>这是 {@code api} 与实现层之间<b>唯一的</b>装配点：{@code api} 只定义接口，
 * 由本类（{@code internal}）在 {@code HabiTrainCore.onInitialize()} 最早期把
 * {@code game.sre} / {@code task} / {@code scene} / {@code config} / {@code vote}
 * 的实现注入。装配方向永远是 {@code internal → api}，因此公开层不会反向依赖任何实现类。
 *
 * <p>审核 A2：{@code scene} / {@code menu} / {@code vote} 三组反向依赖在这里收口。
 * 审核 B14：本方法必须在 {@link CoreLifecycleScope#run(Runnable)} 内调用，
 * 否则 {@link CoreSpi} 会拒绝装配。
 */
public final class CoreSpiRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|CoreSpiRegistrar");

    private CoreSpiRegistrar() {}

    public static void register() {
        CoreSpi.installSreRuntime(SreRuntimeBridgeImpl.INSTANCE);
        CoreSpi.installExtraSlotReclaim(SreExtraSlotReclaimBridge.INSTANCE);
        CoreSpi.installTaskPoolCache(new TaskPoolCacheBridge() {
            @Override
            public void invalidateAll() {
                TaskPoolBuilder.invalidateAll();
            }

            @Override
            public void invalidate(String modeId) {
                TaskPoolBuilder.invalidate(modeId);
            }
        });
        // 场景系统：服务端实例表 / 运行时编排 / 资产索引 / 区域捕获 / 配置根。
        CoreSpi.installSceneInstances(SceneInstanceService.getInstance());
        CoreSpi.installSceneRuntime(SceneRuntimeCoordinator.getInstance());
        CoreSpi.installSceneAssets(SceneAssetStore.getInstance());
        CoreSpi.installSceneCapture(SceneCaptureService.getInstance());
        CoreSpi.installSceneConfig(ConfigManager.getInstance());
        // 菜单门控（服务端）与投票组。
        CoreSpi.installMenuGate(MenuGateBridgeImpl.INSTANCE);
        CoreSpi.installVote(VoteBridgeImpl.INSTANCE);
        // 审核 B-01 / M-01：把「淘汰休息区状态」与「角色解析 / 可见性」这两项
        // 此前只有实现层可用的能力收进公开层，消除下游的越层 import。
        CoreSpi.installRestArea(RestAreaBridgeImpl.INSTANCE);
        CoreSpi.installRoleVisibility(RoleVisibilityBridgeImpl.INSTANCE);
        installRoleServices();
        LOGGER.info("Core SPI 已装配：SreRuntime / ExtraSlotReclaim / TaskPoolCache / Scene / MenuGate / Vote / "
                + "RestArea / RoleVisibility / Role");
    }

    /**
     * 审核 A2：角色扩展服务的默认实例。这里只注入<b>工厂</b>，保持原有的懒加载语义
     * （例如 {@code RoleCatalogImpl.defaultInstance()} 会推迟 {@code TMMRoles} 的初始化）。
     */
    private static void installRoleServices() {
        RoleSpi.installCatalog(com.habitrain.core.role.catalog.RoleCatalogImpl::defaultInstance);
        RoleSpi.installChange(com.habitrain.core.role.change.RoleChangeServiceImpl::new);
        RoleSpi.installDiagnostics(com.habitrain.core.role.diag.RoleDiagnosticsImpl::new);
        RoleSpi.installExtension(com.habitrain.core.role.extension.RoleExtensionServiceImpl::new);
        RoleSpi.installForce(com.habitrain.core.role.force.RoleForceServiceImpl::new);
        RoleSpi.installAction(com.habitrain.core.role.action.RoleActionServiceImpl::new);
        RoleSpi.installCapability(com.habitrain.core.role.capability.RoleCapabilityServiceImpl::new);
        // RoleClientExtensionApi is deliberately installed by HabiTrainCoreClient only.
        // Installing it here as well makes an integrated client hit RoleSpi's duplicate-install
        // guard when the Fabric client entrypoint runs, and also leaks a client implementation
        // into the common/dedicated-server bootstrap path.
        RoleSpi.installState(com.habitrain.core.role.state.RoleStateServiceImpl::new);
        RoleSpi.installOverride(com.habitrain.core.role.override.RoleOverrideBridgeImpl.INSTANCE);
    }
}
