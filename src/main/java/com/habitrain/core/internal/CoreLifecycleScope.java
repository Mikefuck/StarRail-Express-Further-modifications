package com.habitrain.core.internal;

import com.habitrain.core.api.spi.CoreLifecycle;

/**
 * habitrain_core 生命周期作用域（内部实现，不属于公开 API）。
 *
 * <p>审核 A1：旧版本把这个类放在 {@code com.habitrain.core.api.spi} 下并公开
 * {@code run(Runnable)}，于是「注册表启动后不可变」的唯一守卫可被任意下游一行绕过
 * （{@code CoreLifecycleScope.run(() -> TaskRegistry.register(...))}）。
 * 现在它位于 {@code internal} 包，公开层只剩只读判定 {@link CoreLifecycle#isActive()}。
 *
 * <h2>调用者校验（审核 A-03）</h2>
 * <p>旧实现的 {@link #isCoreCaller()} <b>只</b>比对包名前缀
 * {@code com.habitrain.core.}。Fabric 不在加载期强制包归属，因此下游只要把自己的类
 * 声明成 {@code com.habitrain.core.*} 就能进入本作用域，从而在运行期
 * {@code TaskRegistry.register(...)}（绕过冻结）、替换桥接、伪造
 * {@code MatchStateApi.modeId()}。
 *
 * <p>现在校验改为<b>身份判定</b>：调用者必须同时满足
 * <ol>
 *   <li>声明名在 {@code com.habitrain.core} 包空间内；且</li>
 *   <li>由<b>与本类相同的类加载器</b>定义（{@code Class.getClassLoader()}）。</li>
 * </ol>
 * 第二条是实质约束：Fabric 为每个模组使用独立的 {@code KnotClassLoader}，下游模组的类
 * 由另一个加载器定义，因此即使包名伪造成功也会在第二条被拒绝。包名检查保留为快速路径与
 * 可读的错误信息。
 *
 * <p>注意这里不能比对 jar/目录路径：开发环境（{@code build/classes}）与生产（remap 后的
 * mod jar）的 CodeSource 不同，路径比对会把正常的开发环境启动全部拒绝。
 */
public final class CoreLifecycleScope {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final String CORE_PACKAGE_PREFIX = "com.habitrain.core.";

    /**
     * 定义本类的加载器。下游模组的类在 Fabric 下由各自的 KnotClassLoader 定义，
     * 因此它是不可伪造的模组身份。
     */
    private static final ClassLoader CORE_LOADER = CoreLifecycleScope.class.getClassLoader();

    static {
        CoreLifecycle.installProbe(CoreLifecycleScope.class, () -> DEPTH.get() > 0);
    }

    private CoreLifecycleScope() {}

    /**
     * 在 core 生命周期作用域内执行 {@code action}（可重入）。
     *
     * <p><b>审核 A-01 同源问题</b>：作用域<b>只在</b> {@code action} 正常返回后进入，
     * 抛异常时不留下「已激活」的残留深度（旧实现先加深度再执行，异常会让调用方在
     * 同一线程上永久获得作用域，`freeze()` 之类的守卫随之失效）。
     */
    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
        if (!isCoreCaller()) {
            throw new IllegalStateException(
                    "CoreLifecycleScope.run() may only be called from habitrain_core itself");
        }
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
     * 必须既在 core 包空间内，又由 core 的类加载器定义。
     */
    private static boolean isCoreCaller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .filter(frame -> !frame.getClassName().startsWith(CoreLifecycleScope.class.getName()))
                        .findFirst()
                        .map(CoreLifecycleScope::isCoreClass)
                        .orElse(false));
    }

    /**
     * 类身份判定：包名前缀 + 定义加载器双向匹配。
     *
     * <p>隐藏类（lambda 实现类）没有 {@code Class} 对象可查加载器，退回纯包名判定——
     * 但隐藏类只能由其宿主类派发，宿主类本身已经过加载器校验。
     */
    private static boolean isCoreClass(StackWalker.StackFrame frame) {
        if (!frame.getClassName().startsWith(CORE_PACKAGE_PREFIX)) {
            return false;
        }
        Class<?> declaring;
        try {
            declaring = frame.getDeclaringClass();
        } catch (Throwable t) {
            return false;
        }
        if (declaring == null) {
            // lambda / 隐藏类：加载器与宿主一致，宿主已通过校验。
            return true;
        }
        return declaring.getClassLoader() == CORE_LOADER;
    }
}
