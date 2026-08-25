package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameTimeComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a public {@link MatchSettlement} from SRE round-end state.
 */
public final class MatchSettlementFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchSettlementFactory");

    private MatchSettlementFactory() {}

    public static MatchSettlement fromEnd(ServerLevel level, @Nullable SREGameWorldComponent game) {
        String modeId = MatchStateAccess.modeId(level);
        if (modeId == null || modeId.isBlank()) {
            modeId = GameModeIds.canonical(detectModeFallback(game));
        } else {
            modeId = GameModeIds.canonical(modeId);
        }
        String matchKey = roundFingerprint(level);
        SREGameRoundEndComponent roundEnd = readRoundEnd(level);
        GameUtils.WinStatus winStatus = resolveWinStatus(roundEnd, game);
        MatchWinKind winKind = MatchWinKind.fromWinStatusName(winStatus == null ? null : winStatus.name());
        Set<UUID> participants = collectParticipants(level, roundEnd, game, modeId);
        Set<UUID> winners = collectWinners(level, roundEnd, game, winStatus, modeId, participants);
        Map<UUID, MatchWinFaction> factions = new LinkedHashMap<>();
        for (UUID id : participants) {
            factions.put(id, classifyFaction(id, level, game, winStatus, modeId));
        }
        for (UUID id : winners) {
            factions.putIfAbsent(id, classifyFaction(id, level, game, winStatus, modeId));
        }
        return new MatchSettlement(modeId, winKind, matchKey, participants, winners, factions);
    }

    public static String roundFingerprint(ServerLevel level) {
        String dim = "unknown";
        try {
            dim = level.dimension().location().toString();
        } catch (RuntimeException ignored) {
        }
        long startWorldTick = readStartWorldTick(level);
        long startMillis = 0L;
        try {
            startMillis = GameUtils.startTime;
        } catch (RuntimeException ignored) {
        }
        return dim + "|" + startWorldTick + "|" + startMillis;
    }

    private static long readStartWorldTick(ServerLevel level) {
        try {
            SREGameTimeComponent time = SREGameTimeComponent.KEY.get(level);
            if (time != null && time.getStartWorldTick() != 0L) {
                return time.getStartWorldTick();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            return level.getGameTime();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static SREGameRoundEndComponent readRoundEnd(ServerLevel level) {
        try {
            return SREGameRoundEndComponent.KEY.get(level);
        } catch (RuntimeException t) {
            LOGGER.debug("RoundEnd component unavailable: {}", t.toString());
            return null;
        }
    }

    private static GameUtils.WinStatus resolveWinStatus(
            SREGameRoundEndComponent roundEnd, SREGameWorldComponent game) {
        try {
            if (roundEnd != null) {
                GameUtils.WinStatus s = roundEnd.getWinStatus();
                if (s != null && s != GameUtils.WinStatus.NONE && s != GameUtils.WinStatus.NOT_MODIFY) {
                    return s;
                }
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (game != null) {
                GameUtils.WinStatus s = game.getLastWinStatus();
                if (s != null) {
                    return s;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return GameUtils.WinStatus.NONE;
    }

    private static String detectModeFallback(SREGameWorldComponent game) {
        try {
            if (game != null && game.getGameMode() != null && game.getGameMode().identifier != null) {
                return game.getGameMode().identifier.toString();
            }
        } catch (RuntimeException ignored) {
        }
        return GameModeIds.MURDER;
    }

    private static Set<UUID> collectParticipants(
            ServerLevel level,
            SREGameRoundEndComponent roundEnd,
            SREGameWorldComponent game,
            String modeId) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (roundEnd != null && roundEnd.players != null) {
            for (SREGameRoundEndComponent.RoundEndData data : roundEnd.players) {
                if (data != null && data.player != null && data.player.getId() != null) {
                    ids.add(data.player.getId());
                }
            }
        }
        if (GameModeIds.isBlackout(modeId) && level != null) {
            try {
                ids.addAll(BlackoutRoleManager.getRoleHistory(level).keySet());
            } catch (RuntimeException t) {
                LOGGER.debug("blackout role history unavailable: {}", t.toString());
            }
        }
        if (game != null) {
            try {
                Map<UUID, SRERole> roles = game.getRoles();
                if (roles != null) {
                    ids.addAll(roles.keySet());
                }
            } catch (RuntimeException t) {
                LOGGER.debug("game role map unavailable: {}", t.toString());
            }
        }
        return ids;
    }

    private static Set<UUID> collectWinners(
            ServerLevel level,
            SREGameRoundEndComponent roundEnd,
            SREGameWorldComponent game,
            GameUtils.WinStatus winStatus,
            String modeId,
            Set<UUID> participants) {
        Set<UUID> winners = new LinkedHashSet<>();
        if (roundEnd != null) {
            try {
                if (roundEnd.players != null) {
                    for (SREGameRoundEndComponent.RoundEndData data : roundEnd.players) {
                        if (data == null || data.player == null || data.player.getId() == null) {
                            continue;
                        }
                        UUID id = data.player.getId();
                        boolean didWin = false;
                        try {
                            didWin = roundEnd.didWin(id) || data.hasWin;
                        } catch (RuntimeException t) {
                            didWin = data.hasWin;
                        }
                        if (didWin) {
                            winners.add(id);
                        }
                    }
                }
                if (roundEnd.CustomWinnerPlayers != null) {
                    for (UUID custom : roundEnd.CustomWinnerPlayers) {
                        if (custom != null) {
                            winners.add(custom);
                        }
                    }
                }
            } catch (RuntimeException t) {
                LOGGER.debug("collectWinners from roundEnd failed: {}", t.toString());
            }
        }
        if (winners.isEmpty() && game != null && winStatus != null
                && winStatus != GameUtils.WinStatus.NONE
                && winStatus != GameUtils.WinStatus.NOT_MODIFY
                && winStatus != GameUtils.WinStatus.NO_PLAYER) {
            for (UUID id : participants) {
                if (playerMatchesWinStatus(id, level, game, winStatus, modeId)) {
                    winners.add(id);
                }
            }
        }
        return winners;
    }

    private static boolean playerMatchesWinStatus(
            UUID uuid,
            ServerLevel level,
            SREGameWorldComponent game,
            GameUtils.WinStatus winStatus,
            String modeId) {
        if (winStatus == null) {
            return false;
        }
        MatchWinFaction faction = classifyFaction(uuid, level, game, winStatus, modeId);
        if (winStatus.isInnocentWin()) {
            return faction == MatchWinFaction.PASSENGER;
        }
        if (winStatus.isKillerWin()) {
            return faction == MatchWinFaction.KILLER;
        }
        if (winStatus == GameUtils.WinStatus.CUSTOM
                || winStatus == GameUtils.WinStatus.CUSTOM_COMPONENT
                || winStatus == GameUtils.WinStatus.LOVERS
                || winStatus == GameUtils.WinStatus.GAMBLER
                || winStatus == GameUtils.WinStatus.RECORDER
                || winStatus == GameUtils.WinStatus.LOOSE_END
                || winStatus == GameUtils.WinStatus.NIAN_SHOU) {
            SRERole role = effectiveOf(resolveRole(uuid, game));
            return faction == MatchWinFaction.NEUTRAL || role instanceof CustomWinnerRole;
        }
        return false;
    }

    static MatchWinFaction classifyFaction(
            UUID uuid,
            ServerLevel level,
            SREGameWorldComponent game,
            GameUtils.WinStatus winStatus,
            String modeId) {
        if (GameModeIds.isBlackout(modeId) && level != null && uuid != null) {
            MatchWinFaction blackout = classifyBlackout(level, uuid, winStatus);
            if (blackout != null) {
                return blackout;
            }
        }
        SRERole role = effectiveOf(resolveRole(uuid, game));
        if (role != null) {
            return MatchWinFaction.fromRoleFlags(
                    role.isNeutrals(),
                    role instanceof CustomWinnerRole,
                    role.isNeutralForInnocent(),
                    role.isNeutralForKiller(),
                    role.isKiller(),
                    role.isKillerTeam(),
                    role.isCanUseKiller(),
                    role.isMafiaTeam());
        }
        if (winStatus != null) {
            if (winStatus.isKillerWin()) {
                return MatchWinFaction.KILLER;
            }
            if (winStatus.isInnocentWin()) {
                return MatchWinFaction.PASSENGER;
            }
            MatchWinKind kind = MatchWinKind.fromWinStatusName(winStatus.name());
            if (kind == MatchWinKind.CUSTOM) {
                return MatchWinFaction.NEUTRAL;
            }
        }
        return MatchWinFaction.PASSENGER;
    }

    private static MatchWinFaction classifyBlackout(
            ServerLevel level, UUID uuid, GameUtils.WinStatus winStatus) {
        try {
            boolean known = BlackoutRoleManager.getFaction(level, uuid) != null
                    || BlackoutRoleManager.getRoleHistory(level).containsKey(uuid);
            if (!known) {
                return null;
            }
            BlackoutRoleManager.Faction faction = BlackoutRoleManager.getFactionForEnd(level, uuid);
            boolean killerWon = winStatus != null && winStatus.isKillerWin();
            return MatchWinFaction.fromBlackoutName(faction == null ? null : faction.name(), killerWon);
        } catch (RuntimeException t) {
            LOGGER.debug("blackout faction lookup failed: {}", t.toString());
            return null;
        }
    }

    private static SRERole resolveRole(UUID uuid, SREGameWorldComponent game) {
        if (uuid == null || game == null) {
            return null;
        }
        try {
            return game.getRole(uuid);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Prefer the ended-round snapshot. Catalog miss logs an error and keeps the
     * assigned match role (never silently treats a REPLACE target as vanilla).
     */
    private static SRERole effectiveOf(SRERole raw) {
        if (raw == null || raw.identifier() == null) {
            return raw;
        }
        RoleSnapshot snap = RoleSnapshotManager.INSTANCE.current();
        if (snap != null) {
            Optional<EffectiveRole> found = snap.find(RoleKey.of(raw.identifier()));
            if (found.isPresent() && found.get().role() != null) {
                return found.get().role();
            }
            LOGGER.error(
                    "Round snapshot present but did not resolve {}; keeping assigned match role",
                    raw.identifier());
            return raw;
        }
        try {
            Optional<EffectiveRole> live = RoleCatalogApi.instance().resolve(raw);
            if (live.isPresent() && live.get().role() != null) {
                return live.get().role();
            }
        } catch (RuntimeException t) {
            LOGGER.error("RoleCatalogApi.resolve failed for {}; keeping assigned match role", raw.identifier(), t);
        }
        return raw;
    }
}
