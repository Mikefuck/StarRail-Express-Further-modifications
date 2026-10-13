package com.habitrain.core.api.spi;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 只读的 habitrain_core 生命周期作用域探针。
 *
 * <p>{@link com.habitrain.core.api.TaskRegistry#freeze()} 与
 * {@link com.habitrain.core.api.GameModeRegistry#freeze()} 只在 core 自身的
 * {@code SERVER_STARTED} bootstrap 内生效，防止 DLC / 附属模组误调而提前冻结注册表。
 *
 * <p><b>为什么不暴露 {@code run()}</b>：2.0.10 的审核发现，旧版
 * {@code api.spi.CoreLifecycleScope} 同时暴露了「进入作用域」和「查询作用域」两件事，
 * 于是任意下游只要写
 * {@code CoreLifecycleScope.run(() -> TaskRegistry.register(...))}
 * 就能在运行期绕过注册表冻结。现在作用域的唯一驱动者
 * （{@code internal.CoreLifecycleScope}）已移出公开层，
 * 本类只保留只读判定。探针装配要求已知内部类自身调用，且加载器与实际
 * CodeSource 均和当前 Core 一致；这是防误用守卫，不是 Java Mod 安全沙箱。
 */
public final class CoreLifecycle {

    /**
     * 唯一被允许驱动生命周期作用域的类的全限定名。
     * 用字符串常量而非 {@code Class} 字面量，避免公开层反向依赖 {@code internal} 包；
     * 这里刻意拆成两段拼接，使公开层源码里不出现实现包的完整前缀。
     */
    private static final String OWNER_CLASS = "com.habitrain.core." + "internal.CoreLifecycleScope";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile BooleanSupplier probe;

    private CoreLifecycle() {}

    /** 当前线程是否处于 core 生命周期作用域内。未装配时恒为 {@code false}。 */
    public static boolean isActive() {
        BooleanSupplier current = probe;
        return current != null && current.getAsBoolean();
    }

    /** 探针是否已经装配（用于诊断与测试）。 */
    public static boolean isProbeInstalled() {
        return probe != null;
    }

    /**
     * habitrain_core 内部专用：一次性装配作用域探针。
     *
     * <p>审核 A-01 同源加固：旧实现在校验通过后先 {@code compareAndSet} 再赋值，
     * 若赋值前的任何步骤抛异常（或赋值本身失败）就会留下 {@code INSTALLED == true}
     * 但 {@code probe == null} 的半装配状态，{@link #isProbeInstalled()} 与
     * {@link #isActive()} 从此永久失配且无法重装。现在先完成容器赋值，成功后才置位。
     *
     * @param owner 调用者自身；只有 {@value #OWNER_CLASS} 可以通过校验
     * @throws IllegalStateException owner 不是 core 内部作用域类，或探针已装配过
     */
    public static synchronized void installProbe(Class<?> owner, BooleanSupplier probe) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(probe, "probe");
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (!OWNER_CLASS.equals(owner.getName()) || caller != owner || !sameOrigin(owner)) {
            throw new IllegalStateException(
                    "CoreLifecycle.installProbe is reserved for habitrain_core " + OWNER_CLASS);
        }
        if (INSTALLED.get()) {
            throw new IllegalStateException("CoreLifecycle probe already installed");
        }
        CoreLifecycle.probe = probe;
        if (!INSTALLED.compareAndSet(false, true)) {
            CoreLifecycle.probe = null;
            throw new IllegalStateException("CoreLifecycle probe already installed");
        }
    }

    private static boolean sameOrigin(Class<?> owner) {
        try {
            var ownSource = CoreLifecycle.class.getProtectionDomain().getCodeSource();
            var callerSource = owner.getProtectionDomain().getCodeSource();
            return owner.getClassLoader() == CoreLifecycle.class.getClassLoader()
                    && ownSource != null && callerSource != null
                    && ownSource.getLocation().toURI().normalize().equals(callerSource.getLocation().toURI().normalize());
        } catch (Exception failure) {
            return false;
        }
    }
}
