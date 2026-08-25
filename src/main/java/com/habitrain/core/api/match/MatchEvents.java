package com.habitrain.core.api.match;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core match lifecycle events. Lottery and other addons subscribe here instead of
 * SRE {@code OnGameEnd} / round-end CCA.
 */
public final class MatchEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchEvents");

    private MatchEvents() {}

    public static final Event<Started> STARTED = EventFactory.createArrayBacked(
            Started.class,
            listeners -> level -> {
                for (Started listener : listeners) {
                    try {
                        listener.onMatchStarted(level);
                    } catch (Throwable t) {
                        LOGGER.error("MatchEvents.STARTED listener failed", t);
                    }
                }
            });

    public static final Event<RoundEnded> ROUND_ENDED = EventFactory.createArrayBacked(
            RoundEnded.class,
            listeners -> (level, settlement) -> {
                for (RoundEnded listener : listeners) {
                    try {
                        listener.onRoundEnded(level, settlement);
                    } catch (Throwable t) {
                        LOGGER.error("MatchEvents.ROUND_ENDED listener failed", t);
                    }
                }
            });

    @FunctionalInterface
    public interface Started {
        void onMatchStarted(ServerLevel level);
    }

    @FunctionalInterface
    public interface RoundEnded {
        void onRoundEnded(ServerLevel level, MatchSettlement settlement);
    }
}
