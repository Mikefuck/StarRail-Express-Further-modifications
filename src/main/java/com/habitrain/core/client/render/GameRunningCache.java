package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.client.Minecraft;

/**
 * 缓存 SRE 游戏运行状态。
 *
 * 用事件驱动的 invalidation 替代旧的 500ms TTL 定时器：
 * - 首次调用 {@link #isGameRunning()} 时查询 Cardinal Component 并缓存结果
 * - 之后直接返回缓存值，不触发任何查询
 * - 游戏开始/结束时，外部调用 {@link #invalidate()} 清除缓存
 *   （由 {@link com.habitrain.core.client.HabiTrainCoreClient} 中的事件监听器负责）
 *
 * 与旧 TTL 方案相比：
 * - 无固定延迟窗口（500ms 内可能拿到过期值）
 * - 无不必要的重新查询（停服期间每 500ms 白查一次）
 */
public final class GameRunningCache {

    private static Boolean cachedValue = null;

    private GameRunningCache() {}

    /**
     * 检测 SRE 游戏是否正在运行。
     * 大厅阶段返回 false，游戏进行中返回 true。
     */
    public static boolean isGameRunning() {
        if (cachedValue != null) {
            return cachedValue;
        }
        var instance = Minecraft.getInstance();
        if (instance == null || instance.level == null) {
            cachedValue = false;
        } else {
            try {
                var gameWorld = SREGameWorldComponent.KEY.get(instance.level);
                cachedValue = gameWorld != null && gameWorld.isRunning();
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("[GameRunningCache] 查询 SREGameWorldComponent 失败", e);
                cachedValue = false;
            }
        }
        return cachedValue;
    }

    /**
     * 强制刷新缓存。
     * 在 SRE 游戏开始/结束事件发生时调用，以确保下次 isGameRunning() 重新查询。
     */
    public static void invalidate() {
        cachedValue = null;
    }
}
