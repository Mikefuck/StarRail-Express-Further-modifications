package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.client.Minecraft;

/**
 * 缓存 SRE 游戏运行状态。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code true}/{@code false} 均带 TTL，防止 OnGameFinished 漏发时 sticky-true 残留大厅 ESP</li>
 *   <li>level 为 null 时直接返回 false 且不写入缓存</li>
 *   <li>{@link #invalidate()} 立即清空（游戏开始/结束/进出服）</li>
 * </ul>
 */
public final class GameRunningCache {

    private static Boolean cachedValue = null;
    private static long cacheUntilMs = 0L;
    private static final long TRUE_TTL_MS = 1000L;
    private static final long FALSE_TTL_MS = 250L;

    private GameRunningCache() {}

    /**
     * 检测 SRE 游戏是否正在运行。
     * 大厅阶段返回 false，游戏进行中返回 true。
     */
    public static boolean isGameRunning() {
        long now = System.currentTimeMillis();

        if (cachedValue != null && now < cacheUntilMs) {
            return cachedValue;
        }
        cachedValue = null;

        var instance = Minecraft.getInstance();
        if (instance == null || instance.level == null) {
            // Do NOT sticky-cache false while level is null (loading / between worlds).
            return false;
        }

        try {
            var gameWorld = SREGameWorldComponent.KEY.get(instance.level);
            boolean running = gameWorld != null && gameWorld.isRunning();
            cachedValue = running;
            cacheUntilMs = now + (running ? TRUE_TTL_MS : FALSE_TTL_MS);
            return running;
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[GameRunningCache] 查询 SREGameWorldComponent 失败", e);
            cachedValue = false;
            cacheUntilMs = now + FALSE_TTL_MS;
            return false;
        }
    }

    /**
     * 强制刷新缓存。
     * 在 SRE 游戏开始/结束、JOIN/DISCONNECT 时调用。
     */
    public static void invalidate() {
        cachedValue = null;
        cacheUntilMs = 0L;
    }
}
