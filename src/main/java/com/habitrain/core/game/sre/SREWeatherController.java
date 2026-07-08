package com.habitrain.core.game.sre;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 对局内活跃人数不足 8 人时自动下雨的控制器。
 *
 * 只影响主世界，只跟踪由本机制触发的雨。
 * 大厅阶段不触发（SRE game 未运行时不生效）。
 */
public final class SREWeatherController {
    private static boolean forcedRainByLowPlayers = false;
    private static int tickCounter = 0;

    private static final int CHECK_INTERVAL = 20; // 每 20 tick 检查一次
    private static final int MIN_PLAYERS = 8;
    private static final int RAIN_DURATION_TICKS = 20 * 60; // 1 分钟降雨
    private static final int CLEAR_DURATION_TICKS = 20 * 60; // 1 分钟晴天

    private SREWeatherController() {}

    /**
     * 每秒调用一次（每 20 tick）。
     * 对局内活跃人数 < 8 → 下雨；≥ 8 → 恢复晴天。
     * 只操作主世界。
     */
    public static void tick(ServerLevel overworld) {
        if (overworld == null || !overworld.dimension().equals(Level.OVERWORLD)) return;

        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

        // 检查是否有 SRE 对局在运行
        var sreGame = SREGameWorldComponent.KEY.get(overworld);
        boolean gameRunning = sreGame != null && sreGame.isRunning();

        if (!gameRunning) {
            // 对局结束 → 如果之前是本机制触发下雨，恢复晴天
            if (forcedRainByLowPlayers) {
                overworld.setWeatherParameters(CLEAR_DURATION_TICKS, 0, false, false);
                forcedRainByLowPlayers = false;
            }
            return;
        }

        // 计算活跃人数
        int aliveCount = BlackoutRoleManager.getAllAlive(overworld).size();

        if (aliveCount < MIN_PLAYERS) {
            if (!overworld.isRaining()) {
                overworld.setWeatherParameters(0, RAIN_DURATION_TICKS, true, false);
                forcedRainByLowPlayers = true;
            }
        } else {
            if (forcedRainByLowPlayers) {
                overworld.setWeatherParameters(CLEAR_DURATION_TICKS, 0, false, false);
                forcedRainByLowPlayers = false;
            }
        }
    }
}
