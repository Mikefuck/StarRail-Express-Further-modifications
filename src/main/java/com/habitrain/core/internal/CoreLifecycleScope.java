package com.habitrain.core.internal;

import com.habitrain.core.api.spi.CoreLifecycle;
import java.net.URI;
import java.util.Set;

/**
 * habitrain_core 生命周期作用域（内部实现，不属于公开 API）。
 *
 * <p>审核 A1：旧版本把这个类放在 {@code com.habitrain.core.api.spi} 下并公开
 * {@code run(Runnable)}，于是「注册表启动后不可变」的唯一守卫可被任意下游一行绕过
 * （{@code CoreLifecycleScope.run(() -> TaskRegistry.register(...))}）。
 * 现在它位于 {@code internal} 包，公开层只剩只读判定 {@link CoreLifecycle#isActive()}。
 *
 * <p>Fabric 的模组共享目标类加载器，包名与加载器不能证明来源。入口限定为已知
 * 生命周期调用者，并要求其实际 CodeSource 与本次运行的 Core 来源相同；开发目录与
 * 发布 JAR 均按本次运行来源比较，未知来源拒绝。这是防误用守卫，不能隔离已安装
 * Java Mod 的反射或字节码转换能力。
 */
public final class CoreLifecycleScope {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final Set<String> CALLERS = Set.of(
            "com.habitrain.core.HabiTrainCore",
            "com.habitrain.core.LifecycleEventsRegistrar",
            "com.habitrain.core.role.extension.RoleExtensionLifecycleHandler",
            "com.habitrain.core.client.HabiTrainCoreClient",
            "com.habitrain.core.client.role.RoleClientExtensionHooks");
    private static final URI CORE_SOURCE = sourceOf(CoreLifecycleScope.class);

    /** Secondary check only; Fabric uses a shared loader for installed mods. */
    private static final ClassLoader CORE_LOADER = CoreLifecycleScope.class.getClassLoader();

    static {
        CoreLifecycle.installProbe(CoreLifecycleScope.class, () -> DEPTH.get() > 0);
    }

    private CoreLifecycleScope() {}

    /**
     * 在 core 生命周期作用域内执行 {@code action}（可重入）。
     *
     * <p>执行 action 前进入作用域，finally 退出；正常返回和抛异常均恢复嵌套深度。
     */
    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
        if (!isCoreCaller()) {
            throw new IllegalStateException(
                    "CoreLifecycleScope.run() may only be called from habitrain_core itself");
        }
        runWithinScope(action);
    }

    private static void runWithinScope(Runnable action) {
        // 进入作用域必须在执行之前（被调用的 core 代码需要通过 isActive() 观察到它），
        // 因此这里用 try/finally 保证异常路径一定复位深度。
        DEPTH.set(DEPTH.get() + 1);
        try {
            action.run();
        } finally {
            int next = DEPTH.get() - 1;
            if (next <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(next);
            }
        }
    }

    /** 当前线程是否处于 core 生命周期作用域内。 */
    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    /**
     * 直接调用者（跳过本类的帧）是否真的是 habitrain_core 自己的类：
     * 必须在调用者白名单中，并来自当前 Core 的实际代码来源。
     */
    private static boolean isCoreCaller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .filter(frame -> frame.getDeclaringClass() != CoreLifecycleScope.class)
                        .findFirst()
                        .map(CoreLifecycleScope::isCoreClass)
                        .orElse(false));
    }

    /**
     * Unknown origins and non-whitelisted callers fail closed.
     */
    private static boolean isCoreClass(StackWalker.StackFrame frame) {
        try {
            Class<?> declaring = frame.getDeclaringClass();
            return CALLERS.contains(declaring.getName()) && hasCoreSource(declaring);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hasCoreSource(Class<?> declaring) {
        return declaring != null && declaring.getClassLoader() == CORE_LOADER
                && CORE_SOURCE != null && CORE_SOURCE.equals(sourceOf(declaring));
    }

    private static URI sourceOf(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null
                    ? null : source.getLocation().toURI().normalize();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
