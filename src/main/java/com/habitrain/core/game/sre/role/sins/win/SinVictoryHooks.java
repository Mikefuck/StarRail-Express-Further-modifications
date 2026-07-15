package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.AllowGameEnd;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.noellesroles.utils.RoleUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared sin win hooks for SRE murder ({@link AllowGameEnd}) and Blackout
 * ({@link com.habitrain.core.game.blackout.BlackoutVictoryChecker}).
 */
public final class SinVictoryHooks {
    private static boolean registered;

    private SinVictoryHooks() {}

    public static void init() {
        if (registered) return;
        registered = true;
        // SRE murder only — Blackout ends through BlackoutVictoryChecker.
        AllowGameEnd.EVENT.register(SinVictoryHooks::onAllowGameEnd);
        HabiTrainCore.LOGGER.info("[SevenSins] SinVictoryHooks registered (AllowGameEnd pride/sloth)");
    }

    static GameUtils.WinStatus onAllowGameEnd(ServerLevel world, GameUtils.WinStatus proposed,
                                              boolean loose) {
        if (world == null || proposed == null) {
            return GameUtils.WinStatus.NOT_MODIFY;
        }

        // Sloth hijack: any proposed PASSENGERS/KILLERS/TIME while sloth alive → CUSTOM.
        // Runs before pride block so a living sloth steals the faction end (design §5.7 / §6.2).
        if (proposed == GameUtils.WinStatus.KILLERS
                || proposed == GameUtils.WinStatus.PASSENGERS
                || proposed == GameUtils.WinStatus.TIME) {
            if (isSlothAlive(world)) {
                ServerPlayer sloth = findAliveSlothPlayer(world);
                triggerCustomSinWin(world, SevenSins.SLOTH, sloth);
                HabiTrainCore.LOGGER.info(
                        "[SinVictoryHooks] sloth alive → CUSTOM hijack (proposed={}, loose={})",
                        proposed, loose);
                return GameUtils.WinStatus.CUSTOM;
            }
        }

        if (proposed == GameUtils.WinStatus.KILLERS || proposed == GameUtils.WinStatus.PASSENGERS) {
            if (isOnlyPrideAlive(world)) {
                ServerPlayer pride = findAlivePridePlayer(world);
                triggerCustomSinWin(world, SevenSins.PRIDE, pride);
                HabiTrainCore.LOGGER.info(
                        "[SinVictoryHooks] only pride alive → CUSTOM (proposed={}, loose={})",
                        proposed, loose);
                return GameUtils.WinStatus.CUSTOM;
            }
            if (isPrideBlocking(world)) {
                HabiTrainCore.LOGGER.debug(
                        "[SinVictoryHooks] pride blocks {} (loose={})",
                        proposed, loose);
                return GameUtils.WinStatus.NONE;
            }
        }
        return GameUtils.WinStatus.NOT_MODIFY;
    }

    /**
     * Pride is still alive and at least one other assigned/alive participant remains.
     * Blocks good/bad faction wipe ends (timer ends are not gated by this).
     */
    public static boolean isPrideBlocking(ServerLevel level) {
        PridePresence p = scanPride(level);
        return p.prideAlive && p.otherAlive;
    }

    /** Alias for plan interface name. */
    public static boolean isPrideBlockingFactionEnd(ServerLevel level) {
        return isPrideBlocking(level);
    }

    /** Pride is the sole remaining assigned/alive participant. */
    public static boolean isOnlyPrideAlive(ServerLevel level) {
        PridePresence p = scanPride(level);
        return p.prideAlive && !p.otherAlive;
    }

    public static ServerPlayer findAlivePridePlayer(ServerLevel level) {
        if (level == null || level.getServer() == null) return null;
        PridePresence p = scanPride(level);
        if (!p.prideAlive || p.prideId == null) return null;
        return level.getServer().getPlayerList().getPlayer(p.prideId);
    }

    public static boolean isSlothAlive(ServerLevel level) {
        return scanSloth(level).slothAlive;
    }

    public static ServerPlayer findAliveSlothPlayer(ServerLevel level) {
        if (level == null || level.getServer() == null) return null;
        SlothPresence p = scanSloth(level);
        if (!p.slothAlive || p.slothId == null) return null;
        return level.getServer().getPlayerList().getPlayer(p.slothId);
    }

    private static PridePresence scanPride(ServerLevel level) {
        PridePresence out = new PridePresence();
        if (level == null) return out;

        List<UUID> blackoutAlive = BlackoutRoleManager.getAllAlive(level);
        if (!blackoutAlive.isEmpty()) {
            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(level);
            for (UUID id : blackoutAlive) {
                ResourceLocation roleId = history.get(id);
                if (SevenSins.PRIDE_ID.equals(roleId)) {
                    out.prideAlive = true;
                    out.prideId = id;
                } else {
                    out.otherAlive = true;
                }
            }
            return out;
        }

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || !game.isRunning()) {
            return out;
        }
        for (ServerPlayer player : level.players()) {
            if (player == null || player.isSpectator()) continue;
            SRERole role = game.getRole(player);
            if (role == null) continue;
            if (SevenSins.PRIDE_ID.equals(role.getIdentifier())) {
                out.prideAlive = true;
                out.prideId = player.getUUID();
            } else {
                out.otherAlive = true;
            }
        }
        return out;
    }

    private static SlothPresence scanSloth(ServerLevel level) {
        SlothPresence out = new SlothPresence();
        if (level == null) return out;

        List<UUID> blackoutAlive = BlackoutRoleManager.getAllAlive(level);
        if (!blackoutAlive.isEmpty()) {
            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(level);
            for (UUID id : blackoutAlive) {
                if (SevenSins.SLOTH_ID.equals(history.get(id))) {
                    out.slothAlive = true;
                    out.slothId = id;
                    break;
                }
            }
            return out;
        }

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || !game.isRunning()) {
            return out;
        }
        for (ServerPlayer player : level.players()) {
            if (player == null || player.isSpectator()) continue;
            SRERole role = game.getRole(player);
            if (role == null) continue;
            if (SevenSins.SLOTH_ID.equals(role.getIdentifier())) {
                out.slothAlive = true;
                out.slothId = player.getUUID();
                break;
            }
        }
        return out;
    }

    /**
     * Alive players excluding {@link BlackoutRoleManager.Faction#SIN_INDEPENDENT}.
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

    private static final class PridePresence {
        boolean prideAlive;
        boolean otherAlive;
        UUID prideId;
    }

    private static final class SlothPresence {
        boolean slothAlive;
        UUID slothId;
    }
}
