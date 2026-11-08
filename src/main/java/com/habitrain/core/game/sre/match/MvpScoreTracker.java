/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  io.wifi.starrailexpress.SRE
 *  io.wifi.starrailexpress.api.replay.ReplayEvent
 *  io.wifi.starrailexpress.api.replay.ReplayEventTypes$EventDetails
 *  io.wifi.starrailexpress.api.replay.ReplayEventTypes$EventType
 *  io.wifi.starrailexpress.api.replay.ReplayEventTypes$ItemUsedDetails
 *  io.wifi.starrailexpress.cca.SREGameWorldComponent
 *  io.wifi.starrailexpress.cca.SREGameWorldComponent$GameStatus
 *  io.wifi.starrailexpress.event.OnGameStarted
 *  io.wifi.starrailexpress.game.GameUtils
 *  net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
 *  net.minecraft.resources.ResourceKey
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.level.Level
 */
package com.habitrain.core.game.sre.match;

import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.api.replay.ReplayEvent;
import io.wifi.starrailexpress.api.replay.ReplayEventTypes;
import io.wifi.starrailexpress.cca.SREGameTimeComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameStarted;
import io.wifi.starrailexpress.game.GameUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class MvpScoreTracker {
    private static final Map<ResourceKey<Level>, RoundState> ROUNDS = new ConcurrentHashMap<ResourceKey<Level>, RoundState>();
    private static boolean initialized;

    private MvpScoreTracker() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        OnGameStarted.EVENT.register(MvpScoreTracker::beginRound);
        ServerTickEvents.END_SERVER_TICK.register(MvpScoreTracker::tick);
    }

    private static boolean isExcludedRole(SRERole role) {
        return role == null
                || (role.identifier() != null && role.identifier().equals(TMMRoles.DISCOVERY_CIVILIAN.identifier()));
    }

    private static void beginRound(ServerLevel level) {
        if (level == null) {
            return;
        }
        GameEndTransitionCoordinator.onGameStarted(level);
        long startWorldTick = 0L;
        try {
            SREGameTimeComponent time = SREGameTimeComponent.KEY.get(level);
            if (time != null && time.getStartWorldTick() != 0L) {
                startWorldTick = time.getStartWorldTick();
            } else {
                startWorldTick = level.getGameTime();
            }
        } catch (Throwable ignored) {
            try {
                startWorldTick = level.getGameTime();
            } catch (Throwable ignored2) {
            }
        }
        long startMillis = System.currentTimeMillis();
        try {
            if (GameUtils.startTime > 0L && Math.abs(startMillis - GameUtils.startTime) < 60_000L) {
                startMillis = GameUtils.startTime;
            }
        } catch (Throwable ignored) {
        }
        RoundState state = new RoundState(startWorldTick, startMillis);
        SREGameWorldComponent game = (SREGameWorldComponent)SREGameWorldComponent.KEY.get((Object)level);
        for (ServerPlayer player : level.players()) {
            if (game == null || isExcludedRole(game.getRole((Player)player))) continue;
            state.players.put(player.getUUID(), new MutableScore(player.getGameProfile().getName()));
        }
        ROUNDS.put((ResourceKey<Level>)level.dimension(), state);
    }

    public static long getRoundStartMillis(ServerLevel level) {
        if (level == null) {
            return 0L;
        }
        RoundState state = ROUNDS.get(level.dimension());
        return state != null ? state.startMillis : 0L;
    }

    public static long getRoundStartWorldTick(ServerLevel level) {
        if (level == null) {
            return 0L;
        }
        RoundState state = ROUNDS.get(level.dimension());
        return state != null ? state.startWorldTick : 0L;
    }

    private static void tick(MinecraftServer server) {
        if (server == null || ROUNDS.isEmpty()) {
            return;
        }
        for (Map.Entry<ResourceKey<Level>, RoundState> round : ROUNDS.entrySet()) {
            SREGameWorldComponent game;
            ServerLevel level;
            RoundState state = round.getValue();
            if (!state.active || (level = server.getLevel(round.getKey())) == null || (game = (SREGameWorldComponent)SREGameWorldComponent.KEY.get((Object)level)) == null || game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) continue;
            for (ServerPlayer levelPlayer : level.players()) {
                try {
                    if (levelPlayer == null || isExcludedRole(game.getRole((Player)levelPlayer))) continue;
                    state.players.computeIfAbsent(levelPlayer.getUUID(),
                            id -> new MutableScore(levelPlayer.getGameProfile().getName()));
                } catch (Throwable ignored) {
                }
            }
            for (Map.Entry<UUID, MutableScore> entry : state.players.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                try {
                    if (player == null || player.serverLevel() != level || !GameUtils.isPlayerAliveAndSurvival((Player)player)) continue;
                    ++entry.getValue().survivalTicks;
                }
                catch (Throwable throwable) {}
            }
        }
    }

    public static Map<UUID, ScoreSnapshot> freeze(ServerLevel level) {
        if (level == null) {
            return Map.of();
        }
        RoundState state = ROUNDS.get(level.dimension());
        if (state == null) {
            return Map.of();
        }
        if (state.frozenReady) {
            return state.frozen;
        }
        state.active = false;
        try {
            SREGameWorldComponent game = (SREGameWorldComponent)SREGameWorldComponent.KEY.get((Object)level);
            if (game != null) {
                Map<UUID, SRERole> roles = game.getRoles();
                if (roles != null) {
                    for (Map.Entry<UUID, SRERole> roleEntry : roles.entrySet()) {
                        UUID id = roleEntry.getKey();
                        if (id == null || isExcludedRole(roleEntry.getValue())) continue;
                        state.players.computeIfAbsent(id, uid -> {
                            ServerPlayer sp = level.getServer() != null
                                    ? level.getServer().getPlayerList().getPlayer(uid)
                                    : null;
                            return new MutableScore(sp != null ? sp.getGameProfile().getName() : "");
                        });
                    }
                }
                for (Map.Entry<UUID, MutableScore> entry : state.players.entrySet()) {
                    entry.getValue().kills = Math.max(0, game.getPlayerKills(entry.getKey()));
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            for (ReplayEvent event : SRE.REPLAY_MANAGER.getEventsByType(ReplayEventTypes.EventType.ITEM_USED)) {
                MutableScore score;
                if (event == null || event.timestamp() < 0L) continue;
                ReplayEventTypes.EventDetails eventDetails = event.details();
                if (!(eventDetails instanceof ReplayEventTypes.ItemUsedDetails used)
                        || (score = state.players.get(used.playerUuid())) == null) continue;
                ++score.itemUses;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        state.frozen = MvpScoreTracker.immutableSnapshot(state);
        state.frozenReady = true;
        return state.frozen;
    }

    public static Map<UUID, ScoreSnapshot> snapshot(ServerLevel level) {
        if (level == null) {
            return Map.of();
        }
        RoundState state = ROUNDS.get(level.dimension());
        if (state == null) {
            return Map.of();
        }
        return state.frozenReady ? state.frozen : MvpScoreTracker.immutableSnapshot(state);
    }

    public static void resetAll() {
        ROUNDS.clear();
    }

    private static Map<UUID, ScoreSnapshot> immutableSnapshot(RoundState state) {
        LinkedHashMap<UUID, ScoreSnapshot> snapshot = new LinkedHashMap<>();
        state.players.forEach((id, score) -> snapshot.put(id, score.freeze()));
        return Map.copyOf(snapshot);
    }

    private static final class RoundState {
        private final Map<UUID, MutableScore> players = new LinkedHashMap<UUID, MutableScore>();
        private final long startWorldTick;
        private final long startMillis;
        private boolean active = true;
        private boolean frozenReady;
        private Map<UUID, ScoreSnapshot> frozen = Map.of();

        private RoundState(long startWorldTick, long startMillis) {
            this.startWorldTick = startWorldTick;
            this.startMillis = startMillis;
        }
    }

    private static final class MutableScore {
        private final String playerName;
        private int kills;
        private int survivalTicks;
        private int itemUses;

        private MutableScore(String playerName) {
            this.playerName = playerName == null ? "" : playerName;
        }

        private ScoreSnapshot freeze() {
            int survivalSeconds = this.survivalTicks / 20;
            int score = this.kills * 4 + this.survivalTicks / 600 * 3 + this.itemUses;
            return new ScoreSnapshot(this.playerName, score, this.kills, survivalSeconds, this.itemUses);
        }
    }

    public record ScoreSnapshot(String playerName, int score, int kills, int survivalSeconds, int itemUses) {
    }
}

