package com.habitrain.core.game.sre.match;

import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges SRE {@code OnGameStarted}/{@code OnGameEnd} onto public {@link MatchEvents}.
 *
 * <p>When invoked inside {@code GameUtils.finalizeGame}, settlement construction is deferred
 * until immediately after {@code GameMode#recordWinStats} (and {@code LustFollowVictoryMixin})
 * so {@code SREGameRoundEndComponent#didWin} is populated while round-end CCA and roles are still live.</p>
 */
public final class MatchEventBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchEventBridge");
    private static final Set<ResourceKey<Level>> FINALIZING_LEVELS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> PENDING_ROUND_END = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> DELIVERED_ROUND_END = ConcurrentHashMap.newKeySet();
    /** Internal phases: winner observers run before any round-state cleanup. */
    public static final Event<MatchEvents.RoundEnded> WINNERS_FINALIZED = settlementEvent();
    public static final Event<MatchEvents.RoundEnded> CLEANUP = settlementEvent();
    private static boolean registered;

    private MatchEventBridge() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            OnGameStarted.EVENT.register(level -> {
                if (level == null) {
                    return;
                }
                FINALIZING_LEVELS.remove(level.dimension());
                PENDING_ROUND_END.remove(level.dimension());
                DELIVERED_ROUND_END.remove(level.dimension());
                // SRE fires this during INITIATING; the ACTIVE tick releases the reconnect roster.
                MatchEvents.STARTED.invoker().onMatchStarted(level);
            });
            OnGameEnd.EVENT.register((level, game) -> {
                if (level == null) {
                    return;
                }
                ResourceKey<Level> dim = level.dimension();
                if (FINALIZING_LEVELS.contains(dim)) {
                    PENDING_ROUND_END.add(dim);
                    return;
                }
                try { dispatchRoundEnded(level, game); }
                finally { com.habitrain.core.game.sre.match.ServerMatchContext.release(level); }
            });
            LOGGER.info("Registered MatchEvents bridge (OnGameStarted/OnGameEnd)");
        } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
            LOGGER.error("Failed to register MatchEvents bridge", t);
        }
    }

    public static void beginFinalizeGame(@Nullable ServerLevel level) {
        if (level == null) {
            return;
        }
        com.habitrain.core.game.sre.match.ServerMatchContext.GATE.transition(com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level), com.habitrain.core.game.sre.match.SingleMatchGate.Phase.SETTLING);
        FINALIZING_LEVELS.add(level.dimension());
    }

    public static void onAfterRecordWinStats(@Nullable ServerLevel level, @Nullable SREGameWorldComponent game) {
        if (level == null) {
            return;
        }
        ResourceKey<Level> dim = level.dimension();
        if (PENDING_ROUND_END.remove(dim)) {
            dispatchRoundEnded(level, game);
        }
    }

    public static void endFinalizeGame(@Nullable ServerLevel level, @Nullable SREGameWorldComponent game) {
        if (level == null) {
            return;
        }
        ResourceKey<Level> dim = level.dimension();
        FINALIZING_LEVELS.remove(dim);
        try {
            if (PENDING_ROUND_END.remove(dim)) dispatchRoundEnded(level, game);
        } finally { com.habitrain.core.game.sre.match.ServerMatchContext.release(level); }
    }

    private static void dispatchRoundEnded(ServerLevel level, @Nullable SREGameWorldComponent game) {
        if (!DELIVERED_ROUND_END.add(level.dimension())) return;
        SREGameWorldComponent resolvedGame = game;
        if (resolvedGame == null) {
            try {
                resolvedGame = SREGameWorldComponent.KEY.get(level);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to resolve SREGameWorldComponent for round end in {}", level.dimension().location(), e);
            }
        }
        MatchSettlement settlement = MatchSettlementFactory.fromRecordedStats(level, resolvedGame);
        try {
            GameEndTransitionCoordinator.onWinStatsRecorded(level, settlement);
            WINNERS_FINALIZED.invoker().onRoundEnded(level, settlement);
            MatchEvents.ROUND_ENDED.invoker().onRoundEnded(level, settlement);
        } finally {
            CLEANUP.invoker().onRoundEnded(level, settlement);
        }
    }

    static Event<MatchEvents.RoundEnded> settlementEvent() {
        return EventFactory.createArrayBacked(MatchEvents.RoundEnded.class, listeners -> (level, settlement) -> {
            for (MatchEvents.RoundEnded listener : listeners) {
                try { listener.onRoundEnded(level, settlement); }
                catch (Throwable failure) {
                    com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(failure);
                    LOGGER.error("Internal settlement listener failed", failure);
                }
            }
        });
    }

    public static void resetAll() {
        FINALIZING_LEVELS.clear();
        PENDING_ROUND_END.clear();
        DELIVERED_ROUND_END.clear();
    }
}

