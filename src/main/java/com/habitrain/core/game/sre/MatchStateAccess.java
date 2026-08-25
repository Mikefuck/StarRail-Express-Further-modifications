package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.match.MatchPhase;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal CCA adapter for {@link com.habitrain.core.api.match.MatchStateApi}.
 */
public final class MatchStateAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchStateAccess");

    private MatchStateAccess() {}

    public static MatchPhase phase(@Nullable Level level) {
        if (level == null) {
            return MatchPhase.UNKNOWN;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || game.getGameStatus() == null) {
                return MatchPhase.INACTIVE;
            }
            return MatchPhase.fromStatusName(game.getGameStatus().name());
        } catch (RuntimeException e) {
            LOGGER.error("Match phase read failed; treating as UNKNOWN (not lobby)", e);
            return MatchPhase.UNKNOWN;
        }
    }

    public static String modeId(@Nullable ServerLevel level) {
        if (level == null) {
            return "";
        }
        try {
            var registered = GameModeRegistry.getActiveForLevel(level);
            if (registered.isPresent()) {
                GameMode mode = registered.get();
                if (mode != null && mode.getId() != null && !mode.getId().isBlank()) {
                    return GameModeIds.canonical(mode.getId());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("GameModeRegistry mode id failed: {}", e.toString());
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null && game.getGameMode() != null && game.getGameMode().identifier != null) {
                return GameModeIds.canonical(game.getGameMode().identifier.toString());
            }
        } catch (RuntimeException e) {
            LOGGER.error("SRE mode identifier read failed", e);
        }
        return "";
    }
}
