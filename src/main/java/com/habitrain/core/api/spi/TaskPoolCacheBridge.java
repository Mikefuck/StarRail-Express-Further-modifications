package com.habitrain.core.api.spi;

/**
 * 任务候选池缓存失效接口（SPI）。
 *
 * <p>缓存本体在 {@code task.TaskPoolBuilder}（引擎层）。公开层的
 * {@code TaskRegistry.freeze()} 与 {@code GameModeRegistry.start/stop} 需要在
 * 「注册表冻结 / 模式启停」时让缓存失效，此前是直接调用引擎类，形成
 * {@code api → task} 的反向依赖。改为由 {@code internal.CoreSpiRegistrar} 注册本接口的实现。
 */
public interface TaskPoolCacheBridge {

    /** 清空全部池缓存（注册表冻结、配置变更时使用）。 */
    void invalidateAll();

    /** 只清空某个 GameMode 的池缓存（模式启停时使用）。 */
    void invalidate(String modeId);
}
