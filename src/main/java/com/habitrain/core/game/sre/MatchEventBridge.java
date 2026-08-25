package com.habitrain.core.game.sre;

import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges SRE {@code OnGameStarted}/{@code OnGameEnd} onto public {@link MatchEvents}.
 * Fired at {@code OnGameEnd} (finalizeGame) so round-end CCA and roles are still live.
 */
public final class MatchEventBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchEventBridge");
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
                MatchEvents.STARTED.invoker().onMatchStarted(level);
            });
            OnGameEnd.EVENT.register((level, game) -> {
                if (level == null) {
                    return;
                }
                MatchSettlement settlement = MatchSettlementFactory.fromEnd(level, game);
                MatchEvents.ROUND_ENDED.invoker().onRoundEnded(level, settlement);
            });
            LOGGER.info("Registered MatchEvents bridge (OnGameStarted/OnGameEnd)");
        } catch (Throwable t) {
            LOGGER.error("Failed to register MatchEvents bridge", t);
        }
    }
}
