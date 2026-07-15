package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.event.AllowGameEnd;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.noellesroles.utils.RoleUtils;

import java.util.UUID;

/**
 * Shared sin win hooks for SRE murder ({@link AllowGameEnd}) and Blackout
 * ({@link com.habitrain.core.game.blackout.BlackoutVictoryChecker}).
 *
 * <p>P0 shell only: pride block / custom sin win filled in later tasks.
 * Blackout does not end via AllowGameEnd ({@code SREBlackoutGameMode} always
 * returns NOT_MODIFY); registration still serves the SRE murder path.
 */
public final class SinVictoryHooks {
    private static boolean registered;

    private SinVictoryHooks() {}

    public static void init() {
        if (registered) return;
        registered = true;
        // SRE murder only — Blackout ends through BlackoutVictoryChecker.
        AllowGameEnd.EVENT.register(SinVictoryHooks::onAllowGameEnd);
        HabiTrainCore.LOGGER.info("[SevenSins] SinVictoryHooks registered (AllowGameEnd shell)");
    }

    static GameUtils.WinStatus onAllowGameEnd(ServerLevel world, GameUtils.WinStatus proposed,
                                              boolean loose) {
        // P0 shell: Task 7 will return NONE when pride is alive and other non-pride
        // players remain while proposed is KILLERS/PASSENGERS.
        if (proposed == GameUtils.WinStatus.KILLERS || proposed == GameUtils.WinStatus.PASSENGERS) {
            if (isPrideBlocking(world)) {
                HabiTrainCore.LOGGER.debug(
                        "[SinVictoryHooks] pride would block {} (loose={}) — not yet active",
                        proposed, loose);
            }
        }
        return GameUtils.WinStatus.NOT_MODIFY;
    }

    /**
     * Whether pride is still alive and should prevent good/bad faction wipe ends.
     * P0 always false; Task 7 implements real conditions.
     */
    public static boolean isPrideBlocking(ServerLevel level) {
        return false; // Task 7
    }

    /** Alias for plan interface name. */
    public static boolean isPrideBlockingFactionEnd(ServerLevel level) {
        return isPrideBlocking(level);
    }

    /**
     * Alive players excluding {@link BlackoutRoleManager.Faction#SIN_INDEPENDENT}.
     * Used by later pride/custom win checks; not used for good/bad wipe counts
     * (those already ignore SIN_* via getRemainingGood/Bad).
     */
    public static int countAliveExcludingIndependent(ServerLevel level) {
        if (level == null) return 0;
        int n = 0;
        for (UUID id : BlackoutRoleManager.getAllAlive(level)) {
            BlackoutRoleManager.Faction f = BlackoutRoleManager.getFaction(level, id);
            if (f != BlackoutRoleManager.Faction.SIN_INDEPENDENT) {
                n++;
            }
        }
        return n;
    }

    /**
     * Trigger a custom-winner SRE end for an independent sin.
     * P0: helper only; callers land in later tasks.
     *
     * <p>Round-end for Blackout custom sin wins should prefer
     * {@link GameUtils.WinStatus#CUSTOM} + {@code CustomWinnerID} on
     * {@link io.wifi.starrailexpress.cca.SREGameRoundEndComponent} when writing
     * via BlackoutVictoryChecker; this path is for SRE murder RoleUtils.
     */
    public static void triggerCustomSinWin(ServerLevel level, SRERole role, ServerPlayer winner) {
        if (level == null || role == null) return;
        try {
            String path = role.getIdentifier().getPath();
            RoleUtils.customWinnerWin(level, path, role.getColor());
            HabiTrainCore.LOGGER.info("[SinVictoryHooks] customWinnerWin path={} color={} winner={}",
                    path, role.getColor(), winner != null ? winner.getUUID() : null);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[SinVictoryHooks] triggerCustomSinWin failed for {}",
                    role.getIdentifier(), t);
        }
    }
}
