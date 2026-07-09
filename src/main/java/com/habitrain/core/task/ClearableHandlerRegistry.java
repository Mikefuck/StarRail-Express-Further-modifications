package com.habitrain.core.task;

import java.util.ArrayList;
import java.util.List;

/**
 * 可清理处理器注册中心。
 * <p>各 handler 在初始化时通过 {@link #register(Runnable)} 注册其 clearAll 方法，
 * 供 {@link GameLifecycleHandler} 在游戏结束时统一调用，避免 GameLifecycleHandler
 * 直接导入 handler 类并逐个硬编码调用。</p>
 */
public final class ClearableHandlerRegistry {

    private static final List<Runnable> handlers = new ArrayList<>();

    private ClearableHandlerRegistry() {}

    /**
     * 注册一个游戏结束清理方法。
     *
     * @param clearAll handler 的 static {@code clearAll()} 方法引用
     */
    public static void register(Runnable clearAll) {
        handlers.add(clearAll);
    }

    /** 调用所有已注册 handler 的清理方法。 */
    public static void clearAll() {
        for (Runnable handler : handlers) {
            handler.run();
        }
    }
}
