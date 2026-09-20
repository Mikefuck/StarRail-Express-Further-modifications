package com.habitrain.core.api.spi;

import com.habitrain.core.api.role.RoleOverrideEntry;
import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleChangeApi;
import com.habitrain.core.api.role.v2.RoleDiagnostics;
import com.habitrain.core.api.role.v2.RoleExtensionApi;
import com.habitrain.core.api.role.v2.RoleForceApi;
import com.habitrain.core.api.role.v2.action.RoleActionApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 角色扩展服务的装配点（SPI）。
 *
 * <p>审核 A2：2.0.10 及以前，{@code api.role.v2.*Api} 的 {@code instance()} 会直接
 * {@code new} 出 {@code role} 实现包里的 {@code *ServiceImpl}，即公开层反向依赖
 * {@code role} 实现包。现在这些默认实例改为在本类装配：
 * <ul>
 *   <li>{@code internal.CoreSpiRegistrar} 在 core 启动时（{@link CoreLifecycle} 作用域内）
 *       注入实现工厂，保持「懒加载」语义——只有真正调用 {@code instance()} 时才创建实例；</li>
 *   <li>{@code client.HabiTrainCoreClient} 只在物理客户端装配客户端扩展与动作会话；</li>
 *   <li>单元测试通过 {@code RoleSpiTestInstaller}（JUnit 自动扩展）装配同样的实现，
 *       因此测试行为与生产一致。</li>
 * </ul>
 *
 * <p>未装配时 {@code get()} 会抛出带明确说明的 {@link IllegalStateException}，
 * 而不是静默返回一个坏对象。
 */
public final class RoleSpi {

    private static final Slot<RoleCatalogApi> CATALOG = new Slot<>("RoleCatalogApi");
    private static final Slot<RoleChangeApi> CHANGE = new Slot<>("RoleChangeApi");
    private static final Slot<RoleDiagnostics> DIAGNOSTICS = new Slot<>("RoleDiagnostics");
    private static final Slot<RoleExtensionApi> EXTENSION = new Slot<>("RoleExtensionApi");
    private static final Slot<RoleForceApi> FORCE = new Slot<>("RoleForceApi");
    private static final Slot<RoleActionApi> ACTION = new Slot<>("RoleActionApi");
    private static final Slot<RoleCapabilityApi> CAPABILITY = new Slot<>("RoleCapabilityApi");
    private static final Slot<RoleClientExtensionApi> CLIENT_EXTENSION = new Slot<>("RoleClientExtensionApi");
    private static final Slot<RoleStateApi> STATE = new Slot<>("RoleStateApi");
    private static final Slot<RoleOverrideBridge> OVERRIDE = new Slot<>("RoleOverrideBridge");
    private static final Slot<RoleActionClientBridge> ACTION_CLIENT = new Slot<>("RoleActionClientBridge");

    private RoleSpi() {}

    // ==================== 装配 ====================

    public static void installCatalog(Supplier<RoleCatalogApi> supplier) {
        CATALOG.install(supplier);
    }

    public static void installChange(Supplier<RoleChangeApi> supplier) {
        CHANGE.install(supplier);
    }

    public static void installDiagnostics(Supplier<RoleDiagnostics> supplier) {
        DIAGNOSTICS.install(supplier);
    }

    public static void installExtension(Supplier<RoleExtensionApi> supplier) {
        EXTENSION.install(supplier);
    }

    public static void installForce(Supplier<RoleForceApi> supplier) {
        FORCE.install(supplier);
    }

    public static void installAction(Supplier<RoleActionApi> supplier) {
        ACTION.install(supplier);
    }

    public static void installCapability(Supplier<RoleCapabilityApi> supplier) {
        CAPABILITY.install(supplier);
    }

    public static void installClientExtension(Supplier<RoleClientExtensionApi> supplier) {
        CLIENT_EXTENSION.install(supplier);
    }

    public static void installState(Supplier<RoleStateApi> supplier) {
        STATE.install(supplier);
    }

    public static void installOverride(RoleOverrideBridge bridge) {
        OVERRIDE.install(() -> bridge);
    }

    public static void installActionClient(RoleActionClientBridge bridge) {
        ACTION_CLIENT.install(() -> bridge);
    }

    // ==================== 读取 ====================

    public static RoleCatalogApi catalog() {
        return CATALOG.get();
    }

    public static RoleChangeApi change() {
        return CHANGE.get();
    }

    public static RoleDiagnostics diagnostics() {
        return DIAGNOSTICS.get();
    }

    public static RoleExtensionApi extension() {
        return EXTENSION.get();
    }

    public static RoleForceApi force() {
        return FORCE.get();
    }

    public static RoleActionApi action() {
        return ACTION.get();
    }

    public static RoleCapabilityApi capability() {
        return CAPABILITY.get();
    }

    public static RoleClientExtensionApi clientExtension() {
        return CLIENT_EXTENSION.get();
    }

    public static RoleStateApi state() {
        return STATE.get();
    }

    public static RoleOverrideBridge override() {
        return OVERRIDE.get();
    }

    /** @return 客户端动作会话桥；未装配（专用服务端）时返回 {@code null}。 */
    public static @Nullable RoleActionClientBridge actionClientOrNull() {
        return ACTION_CLIENT.getOrNull();
    }

    /** v1 角色覆盖（REPLACE / MODIFY）的桥接接口。 */
    public interface RoleOverrideBridge {
        void registerReplace(ReplaceRoleDefinition def);

        void registerModify(ModifyRoleDefinition def);

        String entryId(ReplaceRoleDefinition def);

        String entryId(ModifyRoleDefinition def);

        Collection<RoleOverrideEntry> effectiveEntries();

        boolean isReplaced(ResourceLocation targetRoleId);

        @Nullable io.wifi.starrailexpress.api.SRERole replacement(ResourceLocation targetRoleId);

        boolean isModified(ResourceLocation targetRoleId);

        @Nullable ModifyRoleDefinition activeModify(ResourceLocation targetRoleId);
    }

    /**
     * 客户端角色动作会话桥。
     *
     * <p>返回值刻意声明为 {@link Object}：{@code api.spi} 不得在专用服务端上解析到
     * {@code api.role.v2.action.RoleActionClientApi}（它带 {@code @Environment(CLIENT)}）。
     */
    public interface RoleActionClientBridge {
        /** @return 客户端会话（{@code RoleActionClientApi} 实例），或 {@code null}。 */
        @Nullable Object session();
    }

    /** 单一服务的懒装配槽：装配前为 {@code null}，装配后可缓存实例。 */
    private static final class Slot<T> {
        private final String name;
        private volatile Supplier<T> supplier;
        private volatile T instance;

        Slot(String name) {
            this.name = name;
        }

        /**
         * 审核 A-16：判定顺序必须与 {@code CoreSpi.install*} 一致——<b>先判重、再看作用域</b>。
         *
         * <p>旧实现先检查 {@code CoreLifecycle.isActive()}，于是在「槽位已被占用 + 调用方
         * 不在 core 生命周期内」这种双重错误的场景下抛出的是「作用域不对」，
         * 真实原因（重复装配）被掩盖；{@code CoreSpi} 那边恰好是相反顺序，
         * 两个装配入口对同一场景给出不同诊断。现在统一为与 {@code CoreSpi} 相同的信息优先级。
         */
        void install(Supplier<T> newSupplier) {
            Objects.requireNonNull(newSupplier, "supplier");
            if (supplier != null) {
                throw new IllegalStateException("RoleSpi." + name + " is already installed");
            }
            if (!CoreLifecycle.isActive()) {
                throw new IllegalStateException(
                        "RoleSpi.install" + name + " is only allowed inside the habitrain_core lifecycle scope");
            }
            supplier = newSupplier;
        }

        T get() {
            T local = instance;
            if (local != null) {
                return local;
            }
            return resolve();
        }

        @Nullable T getOrNull() {
            return supplier == null ? null : get();
        }

        private synchronized T resolve() {
            if (instance != null) {
                return instance;
            }
            Supplier<T> current = supplier;
            if (current == null) {
                throw new IllegalStateException(
                        "RoleSpi." + name + " is not installed: habitrain_core did not initialise");
            }
            T created = Objects.requireNonNull(current.get(), "RoleSpi." + name + " supplier returned null");
            instance = created;
            return created;
        }
    }
}
