package com.habitrain.core.api.match;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.game.sre.MatchStateAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Public match-phase / mode-id queries. Do not use {@link GameMode#isActive}
 * to decide lobby vs in-round — leftover mode objects can stay true in the lobby.
 */
public final class MatchStateApi {
    private MatchStateApi() {}

    public static MatchPhase phase(@Nullable Level level) {
        return MatchStateAccess.phase(level);
    }

    /** True when status is not {@link MatchPhase#INACTIVE} (cards must refuse). */
    public static boolean hasLeftLobby(@Nullable Level level) {
        return phase(level).hasLeftLobby();
    }

    /**
     * Canonical short mode id for the level, or empty when no SRE/core mode is readable.
     * Prefers {@link GameModeRegistry#getActiveForLevel} then SRE {@code identifier},
     * mapping {@code sre:blackout} → {@link GameModeIds#BLACKOUT}.
     */
    public static String modeId(@Nullable ServerLevel level) {
        return MatchStateAccess.modeId(level);
    }
}
