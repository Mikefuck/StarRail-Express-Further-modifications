package com.habitrain.core.api.spi;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试环境下的 SPI 装配器（JUnit 5 自动扩展）。
 *
 * <p>审核 A2 之后，公开层不再自己 {@code new} 实现类，改为从 {@link RoleSpi} /
 * {@link CoreSpi} 取装配好的实现。生产路径由 {@code internal.CoreSpiRegistrar}（服务端）
 * 与 {@code client.HabiTrainCoreClient}（客户端）在 core 启动时注入；单元测试不跑 core
 * 初始化，因此这里补上同一张装配表，保证测试与生产行为一致。
 *
 * <p>自动扩展通过 {@code src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension}
 * 与 {@code junit-platform.properties} 启用，不需要在每个测试类里写 {@code @ExtendWith}。
 */
public final class RoleSpiTestInstaller implements BeforeAllCallback {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    public void beforeAll(ExtensionContext context) {
        installOnce();
    }

    /** 幂等：多次调用只装配一次。 */
    public static void installOnce() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        // 装配受 CoreLifecycle 作用域保护；测试类与生产 bootstrap 一样位于 com.habitrain.core 下。
        com.habitrain.core.internal.CoreLifecycleScope.run(() -> {
            RoleSpi.installCatalog(com.habitrain.core.role.catalog.RoleCatalogImpl::defaultInstance);
            RoleSpi.installChange(com.habitrain.core.role.change.RoleChangeServiceImpl::new);
            RoleSpi.installDiagnostics(com.habitrain.core.role.diag.RoleDiagnosticsImpl::new);
            RoleSpi.installExtension(com.habitrain.core.role.extension.RoleExtensionServiceImpl::new);
            RoleSpi.installForce(com.habitrain.core.role.force.RoleForceServiceImpl::new);
            RoleSpi.installAction(com.habitrain.core.role.action.RoleActionServiceImpl::new);
            RoleSpi.installCapability(com.habitrain.core.role.capability.RoleCapabilityServiceImpl::new);
            RoleSpi.installClientExtension(com.habitrain.core.role.client.RoleClientExtensionRegistry::new);
            RoleSpi.installState(com.habitrain.core.role.state.RoleStateServiceImpl::new);
            RoleSpi.installOverride(com.habitrain.core.role.override.RoleOverrideBridgeImpl.INSTANCE);
            RoleSpi.installActionClient(() -> com.habitrain.core.client.role.RoleActionClientSession.INSTANCE);
            CoreSpi.installMenuGate(com.habitrain.core.config.MenuGateBridgeImpl.INSTANCE);
        });
    }
}
