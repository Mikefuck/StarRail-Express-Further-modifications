package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.client.Minecraft;

/**
 * 缓存 SRE 游戏运行状态。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code true} 可缓存，直到 {@link #invalidate()}（游戏开始/结束/进出服）</li>
 *   <li>{@code false} 不永久缓存：level 为 null 时直接返回 false 且不写入缓存；
 *       已查到 false 时用短 TTL 重查，避免大厅首帧 sticky-false 把整局透视关掉</li>
 * </ul>
 */
public final class GameRunningCache {

    private static Boolean cachedValue = null;
    /** 仅在缓存为 false 时使用的下次允许重查时间戳（ms）。 */
    private static long falseUntilMs = 0L;
    private static final long FALSE_TTL_MS = 250L;

    private GameRunningCache() {}

    /**
     * 检测 SRE 游戏是否正在运行。
     * 大厅阶段返回 false，游戏进行中返回 true。
     */
    public static boolean isGameRunning() {
        long now = System.currentTimeMillis();

        if (cachedValue != null) {
            if (Boolean.TRUE.equals(cachedValue)) {
                return true;
            }
            // cached false: allow re-query after short TTL
            if (now < falseUntilMs) {
                return false;
            }
            cachedValue = null;
        }

        var instance = Minecraft.getInstance();
        if (instance == null || instance.level == null) {
            // Do NOT sticky-cache false while level is null (loading / between worlds).
            return false;
        }

        try {
            var gameWorld = SREGameWorldComponent.KEY.get(instance.level);
            boolean running = gameWorld != null && gameWorld.isRunning();
            if (running) {
                cachedValue = true;
            } else {
                cachedValue = false;
                falseUntilMs = now + FALSE_TTL_MS;
            }
            return running;
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[GameRunningCache] 查询 SREGameWorldComponent 失败", e);
            cachedValue = false;
            falseUntilMs = now + FALSE_TTL_MS;
            return false;
        }
    }

    /**
     * 强制刷新缓存。
     * 在 SRE 游戏开始/结束、JOIN/DISCONNECT 时调用。
     */
    public static void invalidate() {
        cachedValue = null;
        falseUntilMs = 0L;
    }
}
