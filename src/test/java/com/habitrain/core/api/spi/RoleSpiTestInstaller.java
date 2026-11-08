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
 * 初始化，因此这里直接委托 {@code CoreSpiRegistrar.register()} 装配完整服务端桥接表，
 * 再补齐客户端专有的 4 个 {@link RoleSpi} 槽位，保证测试与生产行为一致。
 *
 * <p>自动扩展通过 {@code src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension}
 * 与 {@code junit-platform.properties} 启用，不需要在每个测试类里写 {@code @ExtendWith}。
 */
public final class RoleSpiTestInstaller implements BeforeAllCallback {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.create(RoleSpiTestInstaller.class))
                .getOrComputeIfAbsent("fabric-config", ignored -> new FabricConfigScope());
        installOnce();
    }

    /** Restore the loader field after the whole JUnit engine, even when a test fails. */
    private static final class FabricConfigScope implements ExtensionContext.Store.CloseableResource {
        private final Object loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        private final java.lang.reflect.Field configDir;
        private final Object original;

        private FabricConfigScope() {
            try {
                configDir = loader.getClass().getDeclaredField("configDir");
                configDir.setAccessible(true);
                original = configDir.get(loader);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot snapshot Fabric config directory", e);
            }
        }

        @Override public void close() throws ReflectiveOperationException {
            configDir.set(loader, original);
        }
    }

    /** 幂等：多次调用只装配一次。 */
    public static synchronized void installOnce() {
        if (INSTALLED.get()) {
            return;
        }
        // Bare JUnit does not run Fabric's game provider. Set its config path before
        // the production registrar constructs ConfigManager/ConfigStore.
        try {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            var configDir = loader.getClass().getDeclaredField("configDir");
            configDir.setAccessible(true);
            if (configDir.get(loader) == null) {
                var directory = java.nio.file.Path.of(System.getProperty("habitrain.test.configDir", "build/test-config/spi")).toAbsolutePath();
                java.nio.file.Files.createDirectories(directory);
                configDir.set(loader, directory);
            }
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("Cannot initialize Fabric config directory for tests", failure);
        }
        // 装配受 CoreLifecycle 作用域保护；测试类与生产 bootstrap 一样位于 com.habitrain.core 下。
        com.habitrain.core.internal.CoreLifecycleTestSupport.run(() -> {
            com.habitrain.core.internal.CoreSpiRegistrar.register();
            RoleSpi.installClientExtension(com.habitrain.core.role.client.RoleClientExtensionRegistry::new);
            RoleSpi.installActionClient(() -> com.habitrain.core.client.role.RoleActionClientSession.INSTANCE);
            RoleSpi.installStateClient(() -> com.habitrain.core.client.role.RoleStateClientService.INSTANCE);
            RoleSpi.installScreenClient(() -> com.habitrain.core.client.role.RoleScreenDispatcher.INSTANCE);
        });
        INSTALLED.set(true);
    }
}
