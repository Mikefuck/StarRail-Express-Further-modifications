package com.habitrain.core.game.sre.match;

import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameTimeComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.noellesroles.game.roles.neutral.mercenary.MercenaryPlayerComponent;
import org.agmas.noellesroles.role.ModRoles;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builds a public {@link MatchSettlement} from SRE round-end state.
 */
@SuppressWarnings("deprecation") // Retained for legacy API compatibility.
public final class MatchSettlementFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchSettlementFactory");

    private MatchSettlementFactory() {}

    public static MatchSettlement fromEnd(ServerLevel level, @Nullable SREGameWorldComponent game) {
        return fromEnd(level, game, false);
    }

    /** Called after upstream recordWinStats; an empty winner set is a valid final result. */
    public static MatchSettlement fromRecordedStats(ServerLevel level, @Nullable SREGameWorldComponent game) {
        return fromEnd(level, game, true);
    }

    private static MatchSettlement fromEnd(ServerLevel level, @Nullable SREGameWorldComponent game,
                                            boolean statsRecorded) {
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
        Set<UUID> winners = collectWinners(level, roundEnd, game, winStatus, modeId, participants, statsRecorded);
        Map<UUID, MatchWinFaction> factions = new LinkedHashMap<>();
        for (UUID id : participants) {
            factions.put(id, classifyFaction(id, level, roundEnd, game, winStatus, modeId));
        }
        for (UUID id : winners) {
            factions.putIfAbsent(id, classifyFaction(id, level, roundEnd, game, winStatus, modeId));
        }
        return new MatchSettlement(modeId, winKind, matchKey, participants, winners, factions);
    }

    public static String roundFingerprint(ServerLevel level) {
        String dim = "unknown";
        try {
            if (level != null && level.dimension() != null) {
                dim = level.dimension().location().toString();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to read dimension for roundFingerprint", e);
        }
        long startWorldTick = readStartWorldTick(level);
        long startMillis = MvpScoreTracker.getRoundStartMillis(level);
        if (startMillis == 0L) {
            try {
                startMillis = GameUtils.startTime;
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to read GameUtils.startTime for roundFingerprint", e);
            }
        }
        return dim + "|" + startWorldTick + "|" + startMillis;
    }

    private static long readStartWorldTick(ServerLevel level) {
        long tracked = MvpScoreTracker.getRoundStartWorldTick(level);
        if (tracked != 0L) {
            return tracked;
        }
        try {
            SREGameTimeComponent time = SREGameTimeComponent.KEY.get(level);
            if (time != null && time.getStartWorldTick() != 0L) {
                return time.getStartWorldTick();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("SREGameTimeComponent unavailable in readStartWorldTick", e);
        }
        try {
            return level != null ? level.getGameTime() : 0L;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to read level.getGameTime() for roundFingerprint", e);
            return 0L;
        }
    }

    private static SREGameRoundEndComponent readRoundEnd(ServerLevel level) {
        try {
            return SREGameRoundEndComponent.KEY.get(level);
        } catch (RuntimeException t) {
            LOGGER.warn("RoundEnd component unavailable", t);
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
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to read WinStatus from SREGameRoundEndComponent", e);
        }
        return GameUtils.WinStatus.NONE;
    }

    private static String detectModeFallback(SREGameWorldComponent game) {
        try {
            if (game != null && game.getGameMode() != null && game.getGameMode().identifier != null) {
                return game.getGameMode().identifier.toString();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to read fallback mode from SREGameWorldComponent", e);
        }
        return GameModeIds.MURDER;
    }

    private static boolean isExcludedRole(@Nullable SRERole role) {
        if (role == null || role.identifier() == null) {
            return false;
        }
        return role.identifier().equals(TMMRoles.DISCOVERY_CIVILIAN.identifier());
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
                    UUID id = data.player.getId();
                    if (RepairModeManager.isRepairer(id)) {
                        continue;
                    }
                    SRERole role = resolveRole(id, game);
                    if (isExcludedRole(role)) {
                        continue;
                    }
                    ids.add(id);
                }
            }
        }

        if (game != null) {
            try {
                Map<UUID, SRERole> roles = game.getRoles();
                if (roles != null) {
                    for (Map.Entry<UUID, SRERole> entry : roles.entrySet()) {
                        UUID id = entry.getKey();
                        if (id == null || RepairModeManager.isRepairer(id) || isExcludedRole(entry.getValue())) {
                            continue;
                        }
                        ids.add(id);
                    }
                }
            } catch (RuntimeException t) {
                LOGGER.warn("game role map unavailable", t);
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
            Set<UUID> participants,
            boolean statsRecorded) {
        Set<UUID> winners = new LinkedHashSet<>();
        boolean anyRoundEndHasWin = false;
        if (roundEnd != null) {
            try {
                if (roundEnd.players != null) {
                    for (SREGameRoundEndComponent.RoundEndData data : roundEnd.players) {
                        if (data == null || data.player == null || data.player.getId() == null) {
                            continue;
                        }
                        UUID id = data.player.getId();
                        if (RepairModeManager.isRepairer(id) || isExcludedRole(resolveRole(id, game))) {
                            continue;
                        }
                        boolean didWin;
                        try {
                            didWin = roundEnd.didWin(id) || data.hasWin;
                        } catch (RuntimeException t) {
                            LOGGER.warn("roundEnd.didWin failed for {}", id, t);
                            didWin = data.hasWin;
                        }
                        if (didWin) {
                            anyRoundEndHasWin = true;
                            winners.add(id);
                        }
                    }
                }
                if (!statsRecorded && isCustomWinnerListApplicable(winStatus) && roundEnd.CustomWinnerPlayers != null) {
                    for (UUID custom : roundEnd.CustomWinnerPlayers) {
                        if (custom != null && !RepairModeManager.isRepairer(custom)
                                && !isExcludedRole(resolveRole(custom, game))) {
                            winners.add(custom);
                        }
                    }
                }
            } catch (RuntimeException t) {
                LOGGER.warn("collectWinners from roundEnd failed", t);
            }
        }
        if (!statsRecorded && !anyRoundEndHasWin && game != null && winStatus != null
                && winStatus != GameUtils.WinStatus.NONE
                && winStatus != GameUtils.WinStatus.NOT_MODIFY
                && winStatus != GameUtils.WinStatus.NO_PLAYER) {
            List<UUID> lustCandidates = new ArrayList<>();
            for (UUID id : participants) {
                SRERole role = effectiveOf(resolveRole(id, game));
                if (role != null && SevenSins.LUST_ID.equals(role.identifier())) {
                    lustCandidates.add(id);
                    continue;
                }
                if (playerMatchesWinStatus(id, level, roundEnd, game, winStatus, modeId, winners)) {
                    winners.add(id);
                }
            }
            for (UUID lustId : lustCandidates) {
                if (playerMatchesWinStatus(lustId, level, roundEnd, game, winStatus, modeId, winners)) {
                    winners.add(lustId);
                }
            }
        }
        return winners;
    }

    private static boolean isCustomWinnerListApplicable(@Nullable GameUtils.WinStatus winStatus) {
        return winStatus == GameUtils.WinStatus.CUSTOM
                || winStatus == GameUtils.WinStatus.CUSTOM_COMPONENT
                || winStatus == GameUtils.WinStatus.LOVERS;
    }

    private static boolean isSingleWinnerMode(SREGameWorldComponent game, String modeId) {
        try {
            if (game != null && game.getGameMode() != null
                    && (game.getGameMode().onlyOneWinner() || game.getGameMode().isLooseEndMode())) {
                return true;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to inspect gameMode single-winner flags", e);
        }
        return GameModeIds.LOOSE_ENDS.equals(modeId);
    }

    private static boolean playerMatchesWinStatus(
            UUID uuid,
            ServerLevel level,
            @Nullable SREGameRoundEndComponent roundEnd,
            SREGameWorldComponent game,
            GameUtils.WinStatus winStatus,
            String modeId,
            Set<UUID> settledWinners) {
        if (winStatus == null || uuid == null) {
            return false;
        }
        SRERole role = effectiveOf(resolveRole(uuid, game));
        if (role == null || isExcludedRole(role)) {
            return false;
        }
        if (isSingleWinnerMode(game, modeId)) {
            try {
                return uuid.equals(game.getLooseEndWinner());
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to read looseEndWinner", e);
                return false;
            }
        }

        ServerPlayer onlinePlayer = resolveOnlinePlayer(level, uuid);
        ResourceLocation roleId = role.identifier();
        String rolePath = roleId != null ? roleId.getPath() : "";
        String roleFull = roleId != null ? roleId.toString() : "";
        boolean isWinner = false;

        switch (winStatus) {
            case CUSTOM:
            case CUSTOM_COMPONENT:
                if (roundEnd != null && roundEnd.CustomWinnerPlayers != null
                        && roundEnd.CustomWinnerPlayers.contains(uuid)) {
                    isWinner = true;
                } else if (roundEnd != null && roundEnd.CustomWinnerID != null
                        && !roundEnd.CustomWinnerID.isBlank()
                        && (roundEnd.CustomWinnerID.equals(rolePath) || roundEnd.CustomWinnerID.equals(roleFull))) {
                    isWinner = true;
                } else if (roundEnd != null && roundEnd.CustomWinnerExtraRoleIds != null
                        && (roundEnd.CustomWinnerExtraRoleIds.contains(rolePath)
                        || roundEnd.CustomWinnerExtraRoleIds.contains(roleFull))) {
                    isWinner = true;
                } else if (onlinePlayer != null && roundEnd != null) {
                    try {
                        for (Predicate<Map.Entry<Player, String>> pred : GameUtils.CustomWinnersPredicates) {
                            if (pred != null && pred.test(Map.entry(onlinePlayer, roundEnd.CustomWinnerID))) {
                                isWinner = true;
                                break;
                            }
                        }
                    } catch (RuntimeException e) {
                        LOGGER.warn("CustomWinnersPredicates evaluation failed for {}", uuid, e);
                    }
                }
                break;
            case GAMBLER:
                isWinner = "gambler".equals(rolePath);
                break;
            case KILLERS:
                if (role.winWithKiller()) {
                    isWinner = true;
                } else if (onlinePlayer != null && ModRoles.MERCENARY_ID.equals(roleId)) {
                    isWinner = checkMercenaryFollowWin(onlinePlayer, winStatus);
                }
                break;
            case LOOSE_END:
                try {
                    boolean looseEndsMode = GameModeIds.LOOSE_ENDS.equals(modeId)
                            || (SRE.GAME != null && SRE.GAME.identifier != null
                            && SRE.GAME.identifier.equals(SREGameModes.LOOSE_ENDS.identifier));
                    if (looseEndsMode) {
                        isWinner = game != null && uuid.equals(game.getLooseEndWinner());
                    } else {
                        isWinner = TMMRoles.LOOSE_END.identifier().equals(roleId);
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("LOOSE_END win check failed for {}", uuid, e);
                }
                break;
            case NIAN_SHOU:
                isWinner = "nianshou".equals(rolePath);
                break;
            case LOVERS:
                isWinner = roundEnd != null && roundEnd.CustomWinnerPlayers != null
                        && roundEnd.CustomWinnerPlayers.contains(uuid);
                break;
            case TIME:
            case PASSENGERS:
                if (role.winWithInnocent()) {
                    isWinner = true;
                } else if (onlinePlayer != null && ModRoles.MERCENARY_ID.equals(roleId)) {
                    isWinner = checkMercenaryFollowWin(onlinePlayer, winStatus);
                }
                break;
            case RECORDER:
                isWinner = "recorder".equals(rolePath);
                break;
            default:
                break;
        }

        if (onlinePlayer != null && role instanceof CustomWinnerRole cwr) {
            try {
                isWinner = cwr.didPlayerWin(onlinePlayer, isWinner, winStatus);
            } catch (RuntimeException e) {
                LOGGER.warn("CustomWinnerRole.didPlayerWin failed for {}", uuid, e);
            }
        }

        if (!isWinner && onlinePlayer != null && SevenSins.LUST_ID.equals(roleId)) {
            try {
                LustComponent lust = LustComponent.KEY.maybeGet(onlinePlayer).orElse(null);
                if (lust != null && lust.selected() != null && settledWinners.contains(lust.selected())) {
                    isWinner = true;
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Lust follow win fallback check failed for {}", uuid, e);
            }
        }

        return isWinner;
    }

    private static boolean checkMercenaryFollowWin(ServerPlayer player, GameUtils.WinStatus winStatus) {
        try {
            var mercenary = MercenaryPlayerComponent.KEY.maybeGet(player).orElse(null);
            return mercenary != null && mercenary.canFollowFactionWin(winStatus);
        } catch (RuntimeException e) {
            LOGGER.warn("Mercenary follow win check failed for {}", player.getUUID(), e);
            return false;
        }
    }

    private static @Nullable ServerPlayer resolveOnlinePlayer(@Nullable ServerLevel level, UUID uuid) {
        if (level == null || uuid == null) {
            return null;
        }
        try {
            Player inLevel = level.getPlayerByUUID(uuid);
            if (inLevel instanceof ServerPlayer sp) {
                return sp;
            }
            if (level.getServer() != null) {
                return level.getServer().getPlayerList().getPlayer(uuid);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("resolveOnlinePlayer failed for {}", uuid, e);
        }
        return null;
    }

    static MatchWinFaction classifyFaction(
            UUID uuid,
            ServerLevel level,
            SREGameWorldComponent game,
            GameUtils.WinStatus winStatus,
            String modeId) {
        return classifyFaction(uuid, level, level != null ? readRoundEnd(level) : null, game, winStatus, modeId);
    }

    static MatchWinFaction classifyFaction(
            UUID uuid,
            ServerLevel level,
            @Nullable SREGameRoundEndComponent roundEnd,
            SREGameWorldComponent game,
            GameUtils.WinStatus winStatus,
            String modeId) {
        if (winStatus == GameUtils.WinStatus.LOVERS
                && roundEnd != null
                && roundEnd.CustomWinnerPlayers != null
                && roundEnd.CustomWinnerPlayers.contains(uuid)) {
            return MatchWinFaction.NEUTRAL;
        }

        SRERole role = effectiveOf(resolveRole(uuid, game));
        if (role != null) {
            boolean notInnocent = !role.isInnocent();
            return MatchWinFaction.fromRoleFlags(
                    role.isNeutrals(),
                    role instanceof CustomWinnerRole,
                    role.isNeutralForInnocent(),
                    role.isNeutralForKiller(),
                    role.isKiller() && notInnocent,
                    role.isKillerTeam() && notInnocent,
                    role.isCanUseKiller() && notInnocent,
                    role.isMafiaTeam() && notInnocent);
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

    private static SRERole resolveRole(UUID uuid, SREGameWorldComponent game) {
        if (uuid == null || game == null) {
            return null;
        }
        try {
            return game.getRole(uuid);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to resolve role for {}", uuid, e);
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

