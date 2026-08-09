package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.network.MapVoteLaunchAbortPayload;
import com.habitrain.core.network.MapVoteLaunchTransitionPayload;
import com.habitrain.core.network.MapVoteProgressPayload;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 地图投票后「开局加载」的服务端权威协调器。
 *
 * <p>在 {@code GameUtils.startGame}（投完票→地图重置开始）到 {@code GameUtils.trueStartGame}
 * （重置完成→真正开局）之间的窗口内：</p>
 * <ul>
 *   <li>1Hz 向该维度广播 {@link MapVoteProgressPayload}：进度（SRE STARTING fade 百分比，
 *       未进 STARTING 时按时间推进封顶 90）、游玩人数、按模式配置估算的杀手人数、
 *       选中地图与模式 id。客户端据此绘制加载面板与进度条。</li>
 *   <li>当 {@code trueStartGame} 真正执行（地图重置 + 5 tick 调度完成）时，由服务端 mixin
 *       {@code SRETrueStartGameMixin} 调用 {@link #onGameStartConfirmed} 记录开局成功；待 SRE
 *       地图天气与 API 对局环境都在 {@code OnGameStarted} 中应用完毕后，再由
 *       {@link #onMatchEnvironmentReady} 广播 {@link MapVoteLaunchTransitionPayload}。这样客户端
 *       的「对局开始」动画不会先于天气切换播放。</li>
 * </ul>
 *
 * <p>加载与扫场期间对局时间不计入（SRE 只在 ACTIVE 后开始倒数游戏时间），符合"加载不算在
 * 对局时间内"的要求。</p>
 */
public final class MapVoteLoadCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MapVoteLoadCoordinator");

    private static final ConcurrentMap<ResourceKey<Level>, LoadState> LOADS = new ConcurrentHashMap<>();

    private MapVoteLoadCoordinator() {}

    private static final class LoadState {
        String mapId = "";
        String modeId = "";
        int lastProgress = -1;
        long startedMs = System.currentTimeMillis();
        /** trueStartGame 已成功进入 STARTING，正在等待 OnGameStarted 完成环境应用。 */
        boolean startConfirmed = false;
        /** 环境已应用后再等待两个世界 tick，确保原版天气同步包先于动画包发出。 */
        long environmentReadyAtTick = -1L;
        /** 是否已向客户端广播过开局确认（环境就绪）或开局中止。避免重复广播。 */
        boolean settled = false;
    }

    /** 地图投票选定后、SRE startGame 开始地图重置前调用。 */
    public static void beginLoad(ServerLevel level, String mapId, String modeId) {
        if (level == null) return;
        LoadState st = new LoadState();
        st.mapId = mapId == null ? "" : mapId;
        st.modeId = modeId == null ? "" : modeId;
        LOADS.put(level.dimension(), st);
        LOGGER.info("[MapVoteLoad] load begin dim={} map={} mode={}",
                level.dimension().location(), st.mapId, st.modeId);
    }

    /** 1Hz：若该维度正在开局加载，广播进度。由 ModTickHandler 调用。 */
    public static void tickSecond(MinecraftServer server) {
        if (server == null || LOADS.isEmpty()) return;
        for (ServerLevel level : server.getAllLevels()) {
            LoadState st = LOADS.get(level.dimension());
            if (st == null) continue;
            if (!isSreLoading(level)) {
                // 不再处于加载中。若尚未广播确认/中止（例如 trueStartGame 因人数不足未进
                // STARTING，而 mixin 又未触发），兜底广播中止，避免客户端无限等待。
                if (!st.settled && !st.startConfirmed) {
                    onGameStartConfirmed(level, false);
                } else if (st.settled) {
                    LOADS.remove(level.dimension());
                }
                continue;
            }
            int progress = computeProgress(level);
            if (progress == st.lastProgress) continue;
            st.lastProgress = progress;
            broadcastProgress(level, st, progress);
        }
    }

    /**
     * 每个服务端 tick 检查环境就绪延迟。天气状态是在世界 tick 中同步给客户端的，
     * 因此不能在 {@code setWeatherParameters} 同一个 tick 立即发送动画包。
     */
    public static void tick(MinecraftServer server) {
        if (server == null || LOADS.isEmpty()) return;
        for (ServerLevel level : server.getAllLevels()) {
            LoadState st = LOADS.get(level.dimension());
            if (st == null || st.settled || !st.startConfirmed
                    || st.environmentReadyAtTick < 0L
                    || level.getGameTime() < st.environmentReadyAtTick) {
                continue;
            }
            st.settled = true;
            LOGGER.info("[MapVoteLoad] environment synced dim={} map={} → sending launch transition",
                    level.dimension().location(), st.mapId);
            MapVoteLaunchTransitionPayload.broadcastToLevel(level, st.mapId);
            LOADS.remove(level.dimension(), st);
        }
    }

    /**
     * SRE {@code trueStartGame} 已结束（成功或人数不足中止）。仅当该维度有进行中的开局加载时，
     * 依据 SRE 是否真正进入 STARTING（成功）决定等待环境就绪或广播「开局中止」。
     *
     * @param started true=游戏已进入 STARTING，保留加载遮挡并等待环境就绪；
     *                false=人数不足等原因中止，广播开局中止让客户端立即交还。
     */
    public static void onGameStartConfirmed(ServerLevel level, boolean started) {
        if (level == null) return;
        LoadState st = LOADS.get(level.dimension());
        if (st == null) {
            // 非投票触发的开局（普通 SRE 轮次）：不广播，避免误播"进入游戏"转场。
            return;
        }
        if (st.settled) return;
        if (started) {
            st.startConfirmed = true;
            LOGGER.info("[MapVoteLoad] game start confirmed dim={} map={} → waiting for environment",
                    level.dimension().location(), st.mapId);
        } else {
            st.settled = true;
            LOGGER.info("[MapVoteLoad] game start ABORTED dim={} (not enough players?) → sending launch abort",
                    level.dimension().location());
            MapVoteLaunchAbortPayload.broadcastToLevel(level);
            LOADS.remove(level.dimension());
        }
    }

    /**
     * SRE 自带地图天气和 habitrain API 对局环境均已应用。仅在投票开局且
     * {@link #onGameStartConfirmed} 已确认成功时广播真正的开局标题动画。
     */
    public static void onMatchEnvironmentReady(ServerLevel level) {
        if (level == null) return;
        LoadState st = LOADS.get(level.dimension());
        if (st == null || st.settled || !st.startConfirmed) return;
        if (st.environmentReadyAtTick < 0L) {
            st.environmentReadyAtTick = level.getGameTime() + 2L;
            LOGGER.info("[MapVoteLoad] environment applied dim={} map={} → waiting for weather sync",
                    level.dimension().location(), st.mapId);
        }
    }

    /** 维度清理 / 停服时释放。 */
    public static void reset(ServerLevel level) {
        if (level == null) return;
        LOADS.remove(level.dimension());
    }

    public static boolean isLoading(ServerLevel level) {
        return level != null && LOADS.containsKey(level.dimension());
    }

    private static boolean isSreLoading(ServerLevel level) {
        // startGame 已置 isStartingGame，或 SRE 世界组件处于 STARTING（fade 未满）。
        if (GameUtils.isStartingGame) return true;
        try {
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
            if (gw != null && gw.getGameStatus()
                    == io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STARTING) {
                return true;
            }
        } catch (Throwable t) {
            // ignore
        }
        return false;
    }

    /** 估算开局加载进度：基于 SRE 服务端任务队列（地图重置）。 */
    private static int computeProgress(ServerLevel level) {
        try {
            // FullTrainResetTask / OnlySomeBlockResetTask 的进度可从 GameUtils 队列推断，
            // 但没有公开 getter。用「是否进入 trueStartGame 前」的启发式：
            // STARTING 阶段 fade 每 tick +1，进度 = fade / (FADE_TIME+FADE_PAUSE)。
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
            if (gw != null) {
                int fade = gw.getFade();
                int total = io.wifi.starrailexpress.game.GameConstants.FADE_TIME
                        + io.wifi.starrailexpress.game.GameConstants.FADE_PAUSE;
                int pct = (int) Math.round(fade * 100.0 / Math.max(1, total));
                return Math.max(0, Math.min(100, pct));
            }
        } catch (Throwable t) {
            // ignore
        }
        // 未到 STARTING（仍在 reset task 队列）→ 低位进度，用时间推进避免卡 0。
        LoadState st = LOADS.get(level.dimension());
        long sinceStart = System.currentTimeMillis() - (st != null ? st.startedMs : 0L);
        return (int) Math.min(90, 10 + sinceStart / 300L);
    }

    private static void broadcastProgress(ServerLevel level, LoadState st, int progress) {
        int players = GameUtils.getParticipatingPlayerCount(level);
        int killers = KillerCountResolver.killerCount(players);
        for (ServerPlayer player : level.players()) {
            if (RepairModeManager.isRepairer(player)) {
                continue; // 维修员不看开局加载转场
            }
            MapVoteProgressPayload.sendTo(player, progress, players, killers,
                    st.mapId, st.modeId);
        }
    }

    /** 静态占位：见 {@link KillerCountResolver}。 */
    private static final class KillerCountResolver {
        static int killerCount(int playerCount) {
            try {
                return org.agmas.harpymodloader.commands.RoleCountManager.getKillerCount(playerCount);
            } catch (Throwable t) {
                return Math.max(1, playerCount / 6);
            }
        }
    }
}
