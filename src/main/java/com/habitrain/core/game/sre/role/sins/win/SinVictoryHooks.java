package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.AllowGameEnd;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.agmas.noellesroles.utils.RoleUtils;
import pro.fazeclan.river.stupid_express.constants.SEModifiers;
import pro.fazeclan.river.stupid_express.modifier.lovers.cca.LoversComponent;

import java.util.ArrayList;
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
        // Array-backed AllowGameEnd: first non-NOT_MODIFY wins. Prepend so pride/sloth
        // still see faction proposals; lust only steals when proposed is LOVERS.
        AllowGameEnd.EVENT.register(SinVictoryHooks::onAllowGameEnd);
        prependOurAllowGameEndHandler();
        HabiTrainCore.LOGGER.info(
                "[SevenSins] SinVictoryHooks registered (AllowGameEnd pride/sloth/lust; greed instant)");
    }

    /**
     * Move our handler to the front of the array-backed event so pride/sloth run
     * before other faction ends. Lust steals only on proposed {@code LOVERS}.
     */
    private static void prependOurAllowGameEndHandler() {
        try {
            Object event = AllowGameEnd.EVENT;
            java.lang.reflect.Field handlersField = null;
            Class<?> c = event.getClass();
            while (c != null && handlersField == null) {
                try {
                    handlersField = c.getDeclaredField("handlers");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (handlersField == null) {
                for (java.lang.reflect.Field f : event.getClass().getDeclaredFields()) {
                    if (f.getType().isArray()) {
                        handlersField = f;
                        break;
                    }
                }
            }
            if (handlersField == null) {
                HabiTrainCore.LOGGER.warn(
                        "[SinVictoryHooks] could not reorder AllowGameEnd handlers; lust may lose race to lovers");
                return;
            }
            handlersField.setAccessible(true);
            Object arr = handlersField.get(event);
            if (!(arr instanceof Object[] handlers) || handlers.length == 0) return;

            int ours = -1;
            for (int i = 0; i < handlers.length; i++) {
                if (handlers[i] != null
                        && handlers[i].getClass().getName().contains("SinVictoryHooks")) {
                    ours = i;
                    break;
                }
            }
            if (ours <= 0) return; // already first or not found

            Object h = handlers[ours];
            System.arraycopy(handlers, 0, handlers, 1, ours);
            handlers[0] = h;
            HabiTrainCore.LOGGER.info(
                    "[SinVictoryHooks] prepended sin handler to AllowGameEnd index 0 (was {})",
                    ours);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn(
                    "[SinVictoryHooks] AllowGameEnd reorder failed; lust may lose race to lovers", t);
        }
    }

    static GameUtils.WinStatus onAllowGameEnd(ServerLevel world, GameUtils.WinStatus proposed,
                                              boolean loose) {
        if (world == null || proposed == null) {
            return GameUtils.WinStatus.NOT_MODIFY;
        }

        // Order (priority):
        // 1) only-pride → CUSTOM pride
        // 2) pride blocking (pride alive + others) on PASSENGERS/KILLERS → NONE
        // 3) sloth alive on PASSENGERS/KILLERS/TIME → CUSTOM sloth
        // 4) lust alive + proposed LOVERS only → CUSTOM lust
        // Pride must never lose the end to a sloth hijack while still blocking.
        // Lust steals only when proposed is LOVERS — never faction/timer/other ends,
        // and never races ahead via lovers.won() (safer; requires LOVERS to surface).
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

        // Lust lovers steal: only when proposed is LOVERS (no wouldLoversWin race).
        // Reflective prepend keeps us ahead of LoversWinCheckEvent when possible; if
        // reorder fails we still only steal once LOVERS is the proposed status.
        if (!loose && proposed == GameUtils.WinStatus.LOVERS && isLustAlive(world)) {
            ServerPlayer lust = findAliveLustPlayer(world);
            stealLoversWinForLust(world, lust);
            HabiTrainCore.LOGGER.info(
                    "[SinVictoryHooks] lust steals lovers win → CUSTOM (proposed=LOVERS, loose={})",
                    loose);
            return GameUtils.WinStatus.CUSTOM;
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

    public static boolean isLustAlive(ServerLevel level) {
        return scanLust(level).lustAlive;
    }

    public static ServerPlayer findAliveLustPlayer(ServerLevel level) {
        if (level == null || level.getServer() == null) return null;
        LustPresence p = scanLust(level);
        if (!p.lustAlive || p.lustId == null) return null;
        return level.getServer().getPlayerList().getPlayer(p.lustId);
    }

    /**
     * True when any true-lover player currently has {@link LoversComponent#won()}.
     * Used to race {@code LoversWinCheckEvent} on the AllowGameEnd bus.
     */
    public static boolean wouldLoversWin(ServerLevel level) {
        if (level == null) return false;
        try {
            WorldModifierComponent wmc = WorldModifierComponent.KEY.get(level);
            if (wmc == null || SEModifiers.LOVERS == null) return false;
            for (ServerPlayer p : level.players()) {
                if (p == null || p.isSpectator()) continue;
                if (!wmc.isModifier(p, SEModifiers.LOVERS)) continue;
                LoversComponent lc = LoversComponent.KEY.get(p);
                if (lc != null && lc.isLover() && lc.won()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[SinVictoryHooks] wouldLoversWin check failed", t);
        }
        return false;
    }

    /**
     * Replace lovers custom winners with lust only and fire custom sin win.
     */
    public static void stealLoversWinForLust(ServerLevel level, ServerPlayer lust) {
        if (level == null) return;
        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
            if (roundEnd != null) {
                if (roundEnd.CustomWinnerPlayers == null) {
                    roundEnd.CustomWinnerPlayers = new ArrayList<>();
                } else {
                    roundEnd.CustomWinnerPlayers.clear();
                }
                if (lust != null) {
                    roundEnd.CustomWinnerPlayers.add(lust.getUUID());
                }
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[SinVictoryHooks] clear CustomWinnerPlayers for lust failed", t);
        }
        triggerCustomSinWin(level, SevenSins.LUST, lust);
    }

    /**
     * Blackout helper: if lovers win surfaces and lust is alive, end as lust custom.
     * Currently lovers are SRE-murder path; this is reserved for shared API.
     *
     * @return true if lust stole the end
     */
    public static boolean tryBlackoutLustLoversSteal(ServerLevel level) {
        if (level == null || !isLustAlive(level)) return false;
        if (!wouldLoversWin(level)) return false;
        ServerPlayer lust = findAliveLustPlayer(level);
        stealLoversWinForLust(level, lust);
        return true;
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

    private static LustPresence scanLust(ServerLevel level) {
        LustPresence out = new LustPresence();
        if (level == null) return out;

        List<UUID> blackoutAlive = BlackoutRoleManager.getAllAlive(level);
        if (!blackoutAlive.isEmpty()) {
            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(level);
            for (UUID id : blackoutAlive) {
                if (SevenSins.LUST_ID.equals(history.get(id))) {
                    out.lustAlive = true;
                    out.lustId = id;
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
            if (SevenSins.LUST_ID.equals(role.getIdentifier())) {
                out.lustAlive = true;
                out.lustId = player.getUUID();
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

    /**
     * True when this greed player has finished the collection goal.
     */
    public static boolean isGreedCollectionComplete(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) return false;
        try {
            GreedComponent greed = GreedComponent.KEY.get(player);
            return greed != null && greed.isCollectionComplete();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Any alive greed player has completed collection.
     */
    public static boolean isGreedWinReady(ServerLevel level) {
        if (level == null) return false;
        GreedPresence p = scanGreed(level);
        return p.greedAlive && p.complete;
    }

    public static ServerPlayer findAliveGreedPlayer(ServerLevel level) {
        if (level == null || level.getServer() == null) return null;
        GreedPresence p = scanGreed(level);
        if (!p.greedAlive || p.greedId == null) return null;
        return level.getServer().getPlayerList().getPlayer(p.greedId);
    }

    /**
     * Instant greed collection win (SRE custom). Blackout dual-write is done by caller
     * via {@code BlackoutVictoryChecker.endGameGreedCustom} to avoid package cycles.
     */
    public static void triggerGreedWin(ServerLevel level, ServerPlayer greed) {
        if (level == null) return;
        ServerPlayer winner = greed != null ? greed : findAliveGreedPlayer(level);
        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
            if (roundEnd != null) {
                if (roundEnd.CustomWinnerPlayers == null) {
                    roundEnd.CustomWinnerPlayers = new ArrayList<>();
                } else {
                    roundEnd.CustomWinnerPlayers.clear();
                }
                if (winner != null) {
                    roundEnd.CustomWinnerPlayers.add(winner.getUUID());
                }
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[SinVictoryHooks] set CustomWinnerPlayers for greed failed", t);
        }

        if (SevenSins.GREED != null) {
            triggerCustomSinWin(level, SevenSins.GREED, winner);
        }
    }

    private static GreedPresence scanGreed(ServerLevel level) {
        GreedPresence out = new GreedPresence();
        if (level == null || level.getServer() == null) return out;

        List<UUID> blackoutAlive = BlackoutRoleManager.getAllAlive(level);
        if (!blackoutAlive.isEmpty()) {
            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(level);
            for (UUID id : blackoutAlive) {
                if (SevenSins.GREED_ID.equals(history.get(id))) {
                    out.greedAlive = true;
                    out.greedId = id;
                    ServerPlayer sp = level.getServer().getPlayerList().getPlayer(id);
                    if (sp != null) {
                        try {
                            GreedComponent g = GreedComponent.KEY.get(sp);
                            out.complete = g != null && g.isCollectionComplete();
                        } catch (Throwable ignored) {
                        }
                    }
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
            if (SevenSins.GREED_ID.equals(role.getIdentifier())) {
                out.greedAlive = true;
                out.greedId = player.getUUID();
                try {
                    GreedComponent g = GreedComponent.KEY.get(player);
                    out.complete = g != null && g.isCollectionComplete();
                } catch (Throwable ignored) {
                }
                break;
            }
        }
        return out;
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

    private static final class LustPresence {
        boolean lustAlive;
        UUID lustId;
    }

    private static final class GreedPresence {
        boolean greedAlive;
        boolean complete;
        UUID greedId;
    }
}
