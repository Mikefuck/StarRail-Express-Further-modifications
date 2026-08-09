/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.habitrain.core.game.sre.RepairModeManager
 *  io.wifi.starrailexpress.cca.SREGameRoundEndComponent
 *  io.wifi.starrailexpress.cca.SREGameRoundEndComponent$RoundEndData
 *  io.wifi.starrailexpress.cca.SREGameWorldComponent
 *  io.wifi.starrailexpress.game.GameUtils$WinStatus
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.Component$Serializer
 *  net.minecraft.resources.ResourceKey
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.level.Level
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.habitrain.core.game.sre;

import com.habitrain.core.game.sre.MvpScoreTracker;
import com.habitrain.core.game.sre.RepairModeManager;
import com.habitrain.core.network.GameEndTransitionPayload;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameEndTransitionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"habitrain_core|GameEndTransitionCoordinator");
    private static final ConcurrentMap<ResourceKey<Level>, Boolean> NOTIFIED = new ConcurrentHashMap<ResourceKey<Level>, Boolean>();
    private static final ConcurrentMap<ResourceKey<Level>, Long> ENVIRONMENT_READY_AT = new ConcurrentHashMap<ResourceKey<Level>, Long>();
    private static final ConcurrentMap<ResourceKey<Level>, Map<UUID, MvpScoreTracker.ScoreSnapshot>> MVP_STATS = new ConcurrentHashMap<ResourceKey<Level>, Map<UUID, MvpScoreTracker.ScoreSnapshot>>();
    private static final ConcurrentMap<ResourceKey<Level>, Set<UUID>> CUSTOM_WINNERS = new ConcurrentHashMap<ResourceKey<Level>, Set<UUID>>();
    private static final ConcurrentMap<ResourceKey<Level>, ResultSnapshot> RESULTS = new ConcurrentHashMap<ResourceKey<Level>, ResultSnapshot>();

    private GameEndTransitionCoordinator() {
    }

    public static void onGameStarted(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey dimension = level.dimension();
        GameEndTransitionCoordinator.clearRoundState((ResourceKey<Level>)dimension);
        try {
            SREGameRoundEndComponent roundEnd = (SREGameRoundEndComponent)SREGameRoundEndComponent.KEY.get((Object)level);
            if (roundEnd != null) {
                roundEnd.setWinStatus(GameUtils.WinStatus.NONE);
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[GameEndTransition] failed to reset stale result dim={}", (Object)dimension.location(), (Object)t);
        }
    }

    public static void onStatusStopping(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey dimension = level.dimension();
        if (NOTIFIED.putIfAbsent((ResourceKey<Level>)dimension, Boolean.TRUE) != null) {
            return;
        }
        try {
            boolean customResult;
            SREGameRoundEndComponent roundEnd = (SREGameRoundEndComponent)SREGameRoundEndComponent.KEY.get((Object)level);
            ResultSnapshot result = GameEndTransitionCoordinator.snapshotResult(level, roundEnd);
            RESULTS.put((ResourceKey<Level>)dimension, result);
            MVP_STATS.put((ResourceKey<Level>)dimension, MvpScoreTracker.freeze(level));
            LinkedHashSet<UUID> customWinners = new LinkedHashSet<UUID>();
            boolean bl = customResult = result.winStatus() == GameUtils.WinStatus.CUSTOM || result.winStatus() == GameUtils.WinStatus.CUSTOM_COMPONENT;
            if (customResult && roundEnd != null && roundEnd.CustomWinnerPlayers != null) {
                for (UUID winner : roundEnd.CustomWinnerPlayers) {
                    if (winner == null) continue;
                    customWinners.add(winner);
                }
            }
            CUSTOM_WINNERS.put((ResourceKey<Level>)dimension, Set.copyOf(customWinners));
            GameEndTransitionCoordinator.broadcast(level, false);
        }
        catch (Throwable t) {
            GameEndTransitionCoordinator.clearRoundState((ResourceKey<Level>)dimension);
            LOGGER.warn("[GameEndTransition] initial broadcast failed dim={}", (Object)dimension.location(), (Object)t);
        }
    }

    public static void onEnvironmentReady(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey dimension = level.dimension();
        if (!NOTIFIED.containsKey(dimension)) {
            return;
        }
        if (ENVIRONMENT_READY_AT.putIfAbsent((ResourceKey<Level>)dimension, level.getGameTime() + 2L) == null) {
            LOGGER.info("[GameEndTransition] environment applied dim={} -> waiting for weather sync", (Object)dimension.location());
        }
    }

    public static void tick(MinecraftServer server) {
        if (server == null || ENVIRONMENT_READY_AT.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey dimension = level.dimension();
            Long readyAt = (Long)ENVIRONMENT_READY_AT.get(dimension);
            if (readyAt == null || level.getGameTime() < readyAt) continue;
            try {
                GameEndTransitionCoordinator.broadcast(level, true);
                GameEndTransitionCoordinator.clearRoundState((ResourceKey<Level>)dimension);
            }
            catch (Throwable t) {
                ENVIRONMENT_READY_AT.replace((ResourceKey<Level>)dimension, readyAt, level.getGameTime() + 20L);
                LOGGER.warn("[GameEndTransition] release broadcast failed dim={}", (Object)dimension.location(), (Object)t);
            }
        }
    }

    public static void onStatusLeavingStopping(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey dimension = level.dimension();
        if (!ENVIRONMENT_READY_AT.containsKey(dimension)) {
            GameEndTransitionCoordinator.clearRoundState((ResourceKey<Level>)dimension);
        }
    }

    public static void resetAll() {
        NOTIFIED.clear();
        ENVIRONMENT_READY_AT.clear();
        MVP_STATS.clear();
        CUSTOM_WINNERS.clear();
        RESULTS.clear();
    }

    private static void clearRoundState(ResourceKey<Level> dimension) {
        NOTIFIED.remove(dimension);
        ENVIRONMENT_READY_AT.remove(dimension);
        MVP_STATS.remove(dimension);
        CUSTOM_WINNERS.remove(dimension);
        RESULTS.remove(dimension);
    }

    private static void broadcast(ServerLevel level, boolean environmentReady) {
        ResultSnapshot result = (ResultSnapshot)RESULTS.get(level.dimension());
        if (result == null) {
            return;
        }
        GameUtils.WinStatus winStatus = result.winStatus();
        if (winStatus == GameUtils.WinStatus.NONE || winStatus == GameUtils.WinStatus.NOT_MODIFY) {
            return;
        }
        SREGameRoundEndComponent roundEnd = (SREGameRoundEndComponent)SREGameRoundEndComponent.KEY.get((Object)level);
        GameEndTransitionPayload payload = GameEndTransitionCoordinator.buildPayload(level, roundEnd, result, environmentReady);
        int sent = 0;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (RepairModeManager.isRepairer((ServerPlayer)player)) continue;
            try {
                GameEndTransitionPayload.sendTo(player, payload);
                ++sent;
            }
            catch (Throwable t) {
                LOGGER.warn("[GameEndTransition] send failed player={} dim={} ready={}", new Object[]{player.getGameProfile().getName(), level.dimension().location(), environmentReady, t});
            }
        }
        LOGGER.info("[GameEndTransition] broadcast dim={} winStatus={} ready={} sent={}", new Object[]{level.dimension().location(), winStatus, environmentReady, sent});
    }

    private static GameEndTransitionPayload buildPayload(ServerLevel level, SREGameRoundEndComponent roundEnd, ResultSnapshot result, boolean environmentReady) {
        List<GameEndTransitionPayload.MvpPlayer> mvpPlayers = GameEndTransitionCoordinator.resolveMvpPlayers(level, roundEnd, result);
        return new GameEndTransitionPayload(result.winStatus().name(), result.modeId(), result.customWinnerId(), result.customWinnerColor(), result.customTitleJson(), mvpPlayers, environmentReady);
    }

    private static ResultSnapshot snapshotResult(ServerLevel level, SREGameRoundEndComponent roundEnd) {
        GameUtils.WinStatus winStatus = roundEnd == null || roundEnd.getWinStatus() == null ? GameUtils.WinStatus.NONE : roundEnd.getWinStatus();
        String modeId = "";
        try {
            SREGameWorldComponent game = (SREGameWorldComponent)SREGameWorldComponent.KEY.get((Object)level);
            if (game != null && game.getGameMode() != null && game.getGameMode().identifier != null) {
                modeId = game.getGameMode().identifier.toString();
            }
        }
        catch (Throwable game) {
            // empty catch block
        }
        boolean custom = winStatus == GameUtils.WinStatus.CUSTOM;
        boolean component = winStatus == GameUtils.WinStatus.CUSTOM_COMPONENT;
        String customWinnerId = custom && roundEnd != null && roundEnd.CustomWinnerID != null && !roundEnd.CustomWinnerID.isBlank() ? roundEnd.CustomWinnerID : "";
        int customWinnerColor = (custom || component) && roundEnd != null ? roundEnd.CustomWinnerColor : 0;
        String customTitleJson = "";
        if (component && roundEnd != null && roundEnd.CustomWinnerTitle != null) {
            try {
                customTitleJson = Component.Serializer.toJson((Component)roundEnd.CustomWinnerTitle, (HolderLookup.Provider)level.registryAccess());
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        ArrayList<PlayerResultSnapshot> players = new ArrayList<PlayerResultSnapshot>();
        if (roundEnd != null) {
            for (SREGameRoundEndComponent.RoundEndData data : roundEnd.players) {
                PlayerResultSnapshot player = GameEndTransitionCoordinator.snapshotPlayer(data);
                if (player == null) continue;
                players.add(player);
            }
        }
        return new ResultSnapshot(winStatus, modeId, customWinnerId, customWinnerColor, customTitleJson, List.copyOf(players));
    }

    private static List<GameEndTransitionPayload.MvpPlayer> resolveMvpPlayers(ServerLevel level, SREGameRoundEndComponent roundEnd, ResultSnapshot result) {
        Set customWinners;
        GameUtils.WinStatus winStatus = result.winStatus();
        if (winStatus == GameUtils.WinStatus.NO_PLAYER || winStatus == GameUtils.WinStatus.NONE || winStatus == GameUtils.WinStatus.NOT_MODIFY) {
            return List.of();
        }
        ResourceKey dimension = level.dimension();
        Map stats = MVP_STATS.getOrDefault(dimension, Map.of());
        if (stats.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<UUID, PlayerResultSnapshot> candidates = new LinkedHashMap<UUID, PlayerResultSnapshot>();
        for (PlayerResultSnapshot player : result.players()) {
            if (!player.hasWin() || player.wasDead()) continue;
            candidates.putIfAbsent(player.id(), player);
        }
        if (roundEnd != null) {
            for (Object data : roundEnd.players) {
                PlayerResultSnapshot player = GameEndTransitionCoordinator.snapshotPlayer((SREGameRoundEndComponent.RoundEndData)data);
                if (player == null || !player.hasWin() || player.wasDead()) continue;
                candidates.putIfAbsent(player.id(), player);
            }
        }
        if (!(customWinners = CUSTOM_WINNERS.getOrDefault(dimension, Set.of())).isEmpty()) {
            for (PlayerResultSnapshot player : result.players()) {
                if (player.wasDead() || !customWinners.contains(player.id())) continue;
                candidates.putIfAbsent(player.id(), player);
            }
            if (roundEnd != null) {
                for (SREGameRoundEndComponent.RoundEndData data : roundEnd.players) {
                    PlayerResultSnapshot player = GameEndTransitionCoordinator.snapshotPlayer(data);
                    if (player == null || player.wasDead() || !customWinners.contains(player.id())) continue;
                    candidates.putIfAbsent(player.id(), player);
                }
            }
        }
        ArrayList<GameEndTransitionPayload.MvpPlayer> ranked = new ArrayList<GameEndTransitionPayload.MvpPlayer>();
        for (PlayerResultSnapshot candidate : candidates.values()) {
            MvpScoreTracker.ScoreSnapshot score = (MvpScoreTracker.ScoreSnapshot)stats.get(candidate.id());
            if (score == null) continue;
            String name = candidate.name().isBlank() ? score.playerName() : candidate.name();
            ranked.add(new GameEndTransitionPayload.MvpPlayer(candidate.id(), name, score.score(), score.kills(), score.survivalSeconds(), score.itemUses()));
        }
        ranked.sort(Comparator.comparingInt(GameEndTransitionPayload.MvpPlayer::score).reversed().thenComparing(Comparator.comparingInt(GameEndTransitionPayload.MvpPlayer::kills).reversed()).thenComparing(Comparator.comparingInt(GameEndTransitionPayload.MvpPlayer::survivalSeconds).reversed()).thenComparing(Comparator.comparingInt(GameEndTransitionPayload.MvpPlayer::itemUses).reversed()).thenComparing(GameEndTransitionPayload.MvpPlayer::playerId));
        if (ranked.size() > 4) {
            return List.copyOf(ranked.subList(0, 4));
        }
        return List.copyOf(ranked);
    }

    private static PlayerResultSnapshot snapshotPlayer(SREGameRoundEndComponent.RoundEndData data) {
        if (data == null || data.player() == null || data.player().getId() == null) {
            return null;
        }
        return new PlayerResultSnapshot(data.player().getId(), data.player().getName(), data.wasDead(), data.hasWin());
    }

    private record ResultSnapshot(GameUtils.WinStatus winStatus, String modeId, String customWinnerId, int customWinnerColor, String customTitleJson, List<PlayerResultSnapshot> players) {
    }

    private record PlayerResultSnapshot(UUID id, String name, boolean wasDead, boolean hasWin) {
    }
}
