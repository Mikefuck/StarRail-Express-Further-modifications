package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JOIN/DISCONNECT mode lookup when the player is not in the match dimension
 * (rest area / reconnect in overworld while MATCH is another world).
 */
public final class ActiveModeForPlayer {
    private ActiveModeForPlayer() {}

    public static Optional<GameMode> resolve(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        ServerLevel current = player.serverLevel();
        GameMode currentMode = current == null
                ? null
                : GameModeRegistry.getActiveForLevel(current).orElse(null);
        List<GameMode> inRound = new ArrayList<>();
        List<GameMode> allActive = new ArrayList<>();
        MinecraftServer server = player.getServer();
        if (server != null) {
            UUID playerId = player.getUUID();
            for (ServerLevel level : server.getAllLevels()) {
                GameMode mode = GameModeRegistry.getActiveForLevel(level).orElse(null);
                if (mode == null) {
                    continue;
                }
                allActive.add(mode);
                if (playerInRound(mode, level, playerId)) {
                    inRound.add(mode);
                }
            }
        }
        return pick(currentMode, inRound, allActive);
    }

    /**
     * Current level wins; else a mode whose round contains this UUID;
     * else the unique active mode on the server.
     */
    static <T> Optional<T> pick(@Nullable T currentLevelMode, List<T> inRound, List<T> allActive) {
        if (currentLevelMode != null) {
            return Optional.of(currentLevelMode);
        }
        if (inRound != null) {
            for (T mode : inRound) {
                if (mode != null) {
                    return Optional.of(mode);
                }
            }
        }
        if (allActive == null) {
            return Optional.empty();
        }
        T only = null;
        int count = 0;
        for (T mode : allActive) {
            if (mode == null) {
                continue;
            }
            count++;
            only = mode;
            if (count > 1) {
                return Optional.empty();
            }
        }
        return count == 1 ? Optional.of(only) : Optional.empty();
    }

    static boolean belongsToBlackoutRound(boolean alive, boolean hasRoleHistory) {
        return alive || hasRoleHistory;
    }

    private static boolean playerInRound(GameMode mode, ServerLevel level, UUID playerId) {
        if (mode instanceof BlackoutMode) {
            try {
                boolean alive = BlackoutRoleManager.isAlive(level, playerId);
                boolean history = BlackoutRoleManager.getRoleHistoryEntry(level, playerId) != null;
                return belongsToBlackoutRound(alive, history);
            } catch (Throwable t) {
                return false;
            }
        }
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(level);
            return gw != null && gw.getRole(playerId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
