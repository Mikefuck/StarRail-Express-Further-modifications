package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.WinResult;
import com.habitrain.core.game.blackout.ForcedReadyJoinGate;
import io.wifi.starrailexpress.cca.ParticipationComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thin adapter around SRE MapManager / GameUtils / game-mode constants.
 * All SRE reflection-style calls are try/caught so missing SRE does not crash the API.
 */
public final class SREModeStartAdapter {
    private SREModeStartAdapter() {}

    /**
     * Pre-start refuse predicate. Lookup failure counts as blocking so start is refused.
     */
    static boolean blockingFrom(boolean starting, boolean running, boolean lookupFailed) {
        return lookupFailed || starting || running;
    }

    /**
     * Post-start success predicate. Lookup failure is not proven started.
     */
    static boolean startedFrom(boolean starting, boolean running, boolean lookupFailed) {
        return !lookupFailed && (starting || running);
    }

    /** True when an SRE round is already starting/running (or CCA lookup failed). */
    public static boolean isSreGameBlocking(ServerLevel level) {
        try {
            boolean starting = io.wifi.starrailexpress.game.GameUtils.isStartingGame;
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
            boolean running = gw != null && gw.isRunning();
            return blockingFrom(starting, running, false);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error(
                    "[SREModeStartAdapter] isSreGameBlocking lookup failed; refuse start", t);
            return blockingFrom(false, false, true);
        }
    }

    /** True when SRE is proven STARTING/RUNNING after a start attempt. */
    public static boolean isSreGameStarted(ServerLevel level) {
        try {
            boolean starting = io.wifi.starrailexpress.game.GameUtils.isStartingGame;
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
            boolean running = gw != null && gw.isRunning();
            return startedFrom(starting, running, false);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error(
                    "[SREModeStartAdapter] post-start SRE probe failed; treat as not started", t);
            return startedFrom(false, false, true);
        }
    }

    public static List<String> getAvailableMaps(ServerLevel level) {
        try {
            return new ArrayList<>(io.wifi.starrailexpress.game.MapManager.getAvailableMaps(level));
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static boolean loadMap(ServerLevel level, String mapId) {
        try {
            return io.wifi.starrailexpress.game.MapManager.loadMap(level, mapId);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("loadMap failed: {}", mapId, t);
            return false;
        }
    }

    /**
     * @param registryFullId e.g. habitrain_core:habitrain:blackout
     */
    public static boolean startMode(ServerLevel level, String registryFullId) {
        if (level == null || registryFullId == null || registryFullId.isBlank()) {
            return false;
        }
        try {
            // blackout path
            if (registryFullId.endsWith(":habitrain:blackout")
                    || "habitrain_core:habitrain:blackout".equals(registryFullId)) {
                if (isSreGameBlocking(level)) {
                    HabiTrainCore.LOGGER.warn(
                            "[SREModeStartAdapter] refuse blackout start: SRE already blocking in {}",
                            level.dimension().location());
                    return false;
                }
                forceReadyParticipants(level);
                GameModeRegistry.start("habitrain_core:habitrain:blackout", level);
                if (!isSreGameStarted(level)) {
                    GameModeRegistry.stop(level, WinResult.forceEnd("SRE未启动"));
                    ForcedReadyJoinGate.clear();
                    HabiTrainCore.LOGGER.warn(
                            "[SREModeStartAdapter] blackout registry active but SRE not STARTING/RUNNING in {}",
                            level.dimension().location());
                    return false;
                }
                return true;
            }
            // murder
            if (registryFullId.contains("sre:murder")) {
                var mode = io.wifi.starrailexpress.api.SREGameModes.MURDER;
                int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(mode.defaultStartTime, 0);
                forceReadyParticipants(level);
                io.wifi.starrailexpress.game.GameUtils.startGame(level, mode, ticks);
                return isSreGameStarted(level);
            }
            // repair
            if (registryFullId.contains("sre:repair")) {
                var mode = io.wifi.starrailexpress.api.SREGameModes.REPAIR_ESCAPE_MODE;
                int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(mode.defaultStartTime, 0);
                forceReadyParticipants(level);
                io.wifi.starrailexpress.game.GameUtils.startGame(level, mode, ticks);
                return isSreGameStarted(level);
            }
            // Original SRE modes bridged as thin proxies (wifi:tnt_tag, wifi:lover, …)
            GameMode registered = GameModeRegistry.get(registryFullId);
            if (registered instanceof SreOriginalModeProxy proxy) {
                return startSreModeById(level, proxy.getSreId());
            }
            // generic explicit registry start
            if (GameModeRegistry.isRegistered(registryFullId)) {
                GameModeRegistry.start(registryFullId, level);
                return GameModeRegistry.isActiveInLevel(level);
            }
            return false;
        } catch (Throwable t) {
            try {
                io.wifi.starrailexpress.game.GameUtils.clearForcedReadyPlayers();
            } catch (Throwable ignored) {
            }
            ForcedReadyJoinGate.clear();
            HabiTrainCore.LOGGER.error("startMode failed: {}", registryFullId, t);
            return false;
        }
    }

    private static boolean startSreModeById(ServerLevel level, ResourceLocation sreId) {
        if (sreId == null) {
            return false;
        }
        var sreMode = io.wifi.starrailexpress.api.SREGameModes.GAME_MODES.get(sreId);
        if (sreMode == null) {
            HabiTrainCore.LOGGER.warn("SRE mode not found in GAME_MODES: {}", sreId);
            return false;
        }
        int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(sreMode.defaultStartTime, 0);
        forceReadyParticipants(level);
        io.wifi.starrailexpress.game.GameUtils.startGame(level, sreMode, ticks);
        return isSreGameStarted(level);
    }

    /**
     * 让所有已确认参与对局的玩家强制就绪，绕过 SRE 开局人数判定对就绪区域
     * （readyArea）的过滤。
     *
     * <p>投票后 {@code MapManager.loadMap} 会把 {@code areas.readyArea} 覆盖为获胜地图的
     * 区域，而玩家此刻仍站在旧大厅位置、未被传送；{@code GameUtils.trueStartGame} 又用
     * {@code getStartingPlayers}（默认按 readyArea 过滤）做 {@code minPlayerCount} 判定，
     * 会把这些人判为 0 人而误报「人数不足」。这里像 {@code tmm:start force_all_players}
     * 一样调用 {@code GameUtils.setForcedReadyPlayers}，让 {@code getStartingPlayers}
     * 短路 readyArea 检查、按参与组件返回全部参加者。SRE 在 abort 路径
     * （{@code trueStartGame} 内）和成功路径 {@code initializeGame} 都会
     * {@code clearForcedReadyPlayers()}。core 另拷一份 UUID 到
     * {@link ForcedReadyJoinGate}，SRE 清自己的 list 后仍用于 STARTING 加入门控。</p>
     */
    private static void forceReadyParticipants(ServerLevel level) {
        if (level == null) return;
        try {
            ParticipationComponent participation = ParticipationComponent.KEY.get(level);
            List<UUID> participants = new ArrayList<>();
            for (ServerPlayer p : level.players()) {
                if (p != null && participation.isParticipating(p)) {
                    participants.add(p.getUUID());
                }
            }
            if (participants.isEmpty()) {
                ForcedReadyJoinGate.clear();
                return;
            }
            io.wifi.starrailexpress.game.GameUtils.setForcedReadyPlayers(participants);
            ForcedReadyJoinGate.snapshot(participants);
        } catch (Throwable t) {
            ForcedReadyJoinGate.clear();
            HabiTrainCore.LOGGER.warn("[SREModeStartAdapter] forceReadyParticipants failed", t);
        }
    }
}
