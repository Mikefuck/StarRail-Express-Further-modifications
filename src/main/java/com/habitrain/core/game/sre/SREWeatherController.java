package com.habitrain.core.game.sre;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.EnvProfile;
import com.habitrain.core.config.EnvironmentSettings;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * 对局内活跃人数不足阈值时自动下雨的控制器。
 *
 * 全局生效：对所有在主世界运行的 SRE 对局（凶杀/修理/停电）生效，不局限于停电模式。
 * 存活人数取 SRE 自带 getAlivePlayerRoleTeamInfo（适用所有模式，非停电不再返回 0）。
 * 只影响主世界，只跟踪由本机制触发的雨（不清自然雨/地图脚本雨）。
 * 大厅阶段不触发（主世界无运行中的 SRE 对局时不生效）。
 * 对局结束时只清 force 标志，天气由 EnvironmentController 接管 lobby/post-match。
 *
 * S6-014: State is now per-dimension (keyed by ResourceKey) rather than static fields,
 * allowing independent weather control per level if extended in the future.
 */
public final class SREWeatherController {

    /**
     * Per-dimension weather state holder.
     */
    private static class DimensionWeatherState {
        boolean forcedRainByLowPlayers = false;
        int tickCounter = 0;
    }

    private static final Map<ResourceKey<Level>, DimensionWeatherState> WEATHER_STATES = new HashMap<>();

    private static final int CHECK_INTERVAL = 20; // 每 20 tick 检查一次
    /** Documentation default; runtime threshold comes from env.clampedMinPlayers(). */
    private static final int MIN_PLAYERS = 8;
    private static final int RAIN_DURATION_TICKS = 20 * 60 * 10; // 10 分钟降雨：持续不足阈值期间不轻易到期，避免 ~1s 晴天间隙
    private static final int CLEAR_DURATION_TICKS = 20 * 60; // 1 分钟晴天

    private SREWeatherController() {}

    private static DimensionWeatherState getOrCreateState(ResourceKey<Level> dimension) {
        return WEATHER_STATES.computeIfAbsent(dimension, k -> new DimensionWeatherState());
    }

    /**
     * 每秒调用一次（每 20 tick）。
     * 对局内活跃人数 &lt; minPlayers → 下雨；≥ minPlayers → 恢复匹配环境天气。
     * 对所有 SRE 对局生效（凶杀/修理/停电），不局限于停电模式。
     * 只操作主世界。受 lowPlayerRainEnabled 开关控制。
     */
    public static void tick(ServerLevel overworld) {
        if (overworld == null || !overworld.dimension().equals(Level.OVERWORLD)) return;

        DimensionWeatherState state = getOrCreateState(overworld.dimension());

        state.tickCounter++;
        if (state.tickCounter % CHECK_INTERVAL != 0) return;

        EnvironmentSettings env = ConfigManager.getInstance().getEnvironmentSettings();
        if (!env.lowPlayerRainEnabled) {
            // 关闭时只清 force 标志，不改天气（避免与 EnvironmentController 抢控制权）
            state.forcedRainByLowPlayers = false;
            return;
        }
        int minPlayers = env.clampedMinPlayers();

        // 检查是否有 SRE 对局在运行
        var sreGame = SREGameWorldComponent.KEY.get(overworld);
        boolean gameRunning = sreGame != null && sreGame.isRunning();

        if (!gameRunning) {
            // 对局结束 → 只清 force 标志；lobby/post-match 天气由 EnvironmentController 负责
            state.forcedRainByLowPlayers = false;
            return;
        }

        // 计算当前 SRE 对局存活人数（适用所有 SRE 模式：凶杀/修理/停电）
        int aliveCount;
        try {
            var info = sreGame.getAlivePlayerRoleTeamInfo();
            aliveCount = info.innocent + info.vigilante + info.all_neturals + info.killer;
        } catch (Exception e) {
            // 无法获取人数，本轮跳过，不改变天气
            return;
        }

        if (aliveCount < minPlayers) {
            // 不足阈值 → 下雨。仅在当前非雨天时强制（避免每 tick 刷包）；rainTime 设较长，
            // 使降雨在本机制持续期间不轻易到期，偶发到期后下一秒检查即重新强制，间隙约 1s。
            if (!overworld.isRaining()) {
                overworld.setWeatherParameters(0, RAIN_DURATION_TICKS, true, false);
                state.forcedRainByLowPlayers = true;
            }
        } else if (state.forcedRainByLowPlayers) {
            // 恢复到匹配环境天气（若有启用 profile），否则 CLEAR
            EnvProfile match = EnvironmentController.getActiveMatchProfile(overworld);
            if (match != null && match.enabled) {
                EnvironmentController.applyWeatherOnly(overworld, match.weather);
            } else {
                overworld.setWeatherParameters(CLEAR_DURATION_TICKS, 0, false, false);
            }
            state.forcedRainByLowPlayers = false;
        }
    }

    /**
     * Whether low-player rain is currently forcing weather on this level.
     * Used by EnvironmentController.maintainProfile to avoid fighting rain.
     */
    public static boolean isForcingRain(ServerLevel level) {
        if (level == null) return false;
        DimensionWeatherState state = WEATHER_STATES.get(level.dimension());
        return state != null && state.forcedRainByLowPlayers;
    }

    /**
     * Reset all weather state (e.g. on server shutdown or full reset).
     */
    public static void resetAll() {
        WEATHER_STATES.clear();
    }
}
