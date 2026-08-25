package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.network.EliminatedRestPromptPayload;
import com.habitrain.core.network.EliminatedRestTogglePayload;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import io.wifi.starrailexpress.event.AllowSpectatorPlayerInAreas;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import io.wifi.starrailexpress.event.OnPlayerDeath;
import io.wifi.starrailexpress.game.GameUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lets a genuinely eliminated SRE player visit the map's post-game spawn
 * without turning that player back into a live round participant.
 */
public final class EliminatedRestAreaService {
    /**
     * Players eliminated in the current round. Intentionally retained across
     * disconnect/reconnect so a still-running match can let them re-enter the
     * rest area. Cleared on round end, server stop, or upstream revival.
     */
    private static final Set<UUID> ELIMINATED_PLAYERS = new HashSet<>();
    /**
     * Match dimension that recorded each elimination. Used so {@link OnGameStarted}
     * / {@link OnGameEnd} can drop only that match's entries. UUID-only
     * {@link #markEliminated(UUID)} leaves this empty (no dim known).
     */
    private static final Map<UUID, ResourceKey<Level>> ELIMINATED_MATCH_LEVELS = new HashMap<>();
    /**
     * Occupancy only: the match world while a player is physically in the
     * post-game area. Keys are ResourceKey, not Level references. Dropped on
     * disconnect so reconnect does not keep {@link #isResting(ServerPlayer)}.
     */
    private static final Map<UUID, ResourceKey<Level>> RESTING_MATCH_LEVELS = new HashMap<>();
    private static final Set<UUID> ENTERING_REST_PLAYERS = new HashSet<>();
    /**
     * Set by {@link #prepareUpstreamRevival} when a resting player is about to
     * be switched to adventure by an upstream revive. {@link #finishUpstreamRevival}
     * is a no-op unless this set contains the UUID, so login / occupancy
     * {@code setGameMode(ADVENTURE)} cannot clear {@link #ELIMINATED_PLAYERS}.
     */
    private static final Set<UUID> REVIVING_PLAYERS = new HashSet<>();
    private static final Map<UUID, RestPromptState> PROMPT_STATES = new HashMap<>();
    private static final Map<UUID, Long> TOGGLE_COOLDOWN_UNTIL = new HashMap<>();
    private static final int TOGGLE_COOLDOWN_TICKS = 10;
    private static boolean initialized;

    private EliminatedRestAreaService() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        OnPlayerDeath.EVENT.register((player, deathReason) -> {
            // OnPlayerDeath 是全局事件：玩家可能在未挂 SRE 世界组件的维度死亡，
            // KEY.get 结果必须判 null，否则 NPE 会中断后续监听链（review M7）。
            if (player instanceof ServerPlayer serverPlayer) {
                SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(serverPlayer.serverLevel());
                if (gameWorld != null && gameWorld.isRunning()) {
                    UUID playerId = serverPlayer.getUUID();
                    ELIMINATED_PLAYERS.add(playerId);
                    ELIMINATED_MATCH_LEVELS.put(playerId, serverPlayer.serverLevel().dimension());
                    RESTING_MATCH_LEVELS.remove(playerId);
                    REVIVING_PLAYERS.remove(playerId);
                    syncPrompt(serverPlayer, false);
                }
            }
        });
        OnGameStarted.EVENT.register(EliminatedRestAreaService::clearRoundStateForLevel);
        OnGameEnd.EVENT.register((level, gameWorld) -> clearRoundStateForLevel(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearRoundState());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.getPlayer() != null) {
                handleDisconnect(handler.getPlayer().getUUID());
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            restoreAfterReconnect(player, server);
            syncPrompt(player, true);
        });
        ServerTickEvents.END_SERVER_TICK.register(EliminatedRestAreaService::syncPromptStates);

        ServerPlayNetworking.registerGlobalReceiver(EliminatedRestTogglePayload.TYPE, (payload, context) ->
                context.server().execute(() -> toggle(context.player())));

        // The rest area is deliberately outside the map's playArea, so SRE's
        // per-tick spectator limiter (limitPlayerToBox against the playArea)
        // must never pull a resting player back into the map. This is the
        // upstream extension point the default limitSpectatorPlayer consults.
        AllowSpectatorPlayerInAreas.EVENT.register(player ->
                player instanceof ServerPlayer serverPlayer && isResting(serverPlayer));
    }

    /**
     * Records rest-area eligibility without waiting for {@link OnPlayerDeath}.
     * Used when a blackout player is eliminated while offline (timeout) and no
     * {@link ServerLevel} is available. Does not bind a match dimension; those
     * entries survive per-level round clear until {@code SERVER_STOPPED} or a
     * later death that writes {@link #ELIMINATED_MATCH_LEVELS}.
     */
    public static void markEliminated(UUID playerId) {
        markEliminated(playerId, null);
    }

    /**
     * Same as {@link #markEliminated(UUID)} but ties the player to {@code level}
     * so {@link OnGameStarted}/{@link OnGameEnd} for that dimension can drop them.
     */
    public static void markEliminated(ServerLevel level, UUID playerId) {
        markEliminated(playerId, level == null ? null : level.dimension());
    }

    private static void markEliminated(UUID playerId, ResourceKey<Level> matchLevel) {
        if (playerId == null) {
            return;
        }
        ELIMINATED_PLAYERS.add(playerId);
        if (matchLevel != null) {
            ELIMINATED_MATCH_LEVELS.put(playerId, matchLevel);
        }
    }

    /**
     * Used by the ServerPlayer mixin so all upstream survival checks continue
     * to regard a resting player as eliminated.
     */
    public static boolean isResting(ServerPlayer player) {
        return player != null && RESTING_MATCH_LEVELS.containsKey(player.getUUID());
    }

    /**
     * Disconnect drops rest occupancy and prompt/cooldown state. {@link #ELIMINATED_PLAYERS}
     * and {@link #ELIMINATED_MATCH_LEVELS} are kept so the same still-running round still
     * treats this player as eliminated, and so that match's later start/end can drop them.
     */
    static void handleDisconnect(UUID playerId) {
        if (playerId == null) {
            return;
        }
        RESTING_MATCH_LEVELS.remove(playerId);
        ENTERING_REST_PLAYERS.remove(playerId);
        REVIVING_PLAYERS.remove(playerId);
        PROMPT_STATES.remove(playerId);
        TOGGLE_COOLDOWN_UNTIL.remove(playerId);
    }

    /**
     * JOIN fallback: never auto-teleport into the rest area. Drop a leftover rest
     * key when the match world is gone or not running. Occupancy is always cleared
     * so {@code isResting()} is false after reconnect. Elimination is dropped only
     * when the match is known stopped; a lobby/overworld with no SRE component is
     * not treated as "round ended" (OnGameEnd already clears a finished round).
     */
    static void applyJoinReconnect(UUID playerId, boolean restKeyPresent,
                                   boolean matchLevelPresent, boolean matchKnownStopped,
                                   boolean stillEliminated) {
        if (playerId == null) {
            return;
        }
        if (restKeyPresent && (!matchLevelPresent || matchKnownStopped)) {
            RESTING_MATCH_LEVELS.remove(playerId);
        }
        RESTING_MATCH_LEVELS.remove(playerId);
        if (matchKnownStopped) {
            ELIMINATED_PLAYERS.remove(playerId);
            ELIMINATED_MATCH_LEVELS.remove(playerId);
            REVIVING_PLAYERS.remove(playerId);
            return;
        }
        if (stillEliminated) {
            return;
        }
    }

    private static void restoreAfterReconnect(ServerPlayer player, MinecraftServer server) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        ResourceKey<Level> restKey = RESTING_MATCH_LEVELS.get(playerId);
        ServerLevel matchLevel = restKey == null || server == null ? null : server.getLevel(restKey);
        SREGameWorldComponent matchWorld = matchLevel == null ? null : SREGameWorldComponent.KEY.get(matchLevel);
        boolean restKeyPresent = restKey != null;
        boolean matchLevelPresent = matchLevel != null;
        boolean matchKnownStopped;
        if (restKeyPresent) {
            matchKnownStopped = matchWorld == null || !matchWorld.isRunning();
        } else {
            ServerLevel current = player.serverLevel();
            SREGameWorldComponent currentWorld = current == null ? null : SREGameWorldComponent.KEY.get(current);
            matchKnownStopped = currentWorld != null && !currentWorld.isRunning();
        }
        applyJoinReconnect(playerId, restKeyPresent, matchLevelPresent, matchKnownStopped,
                GameUtils.isPlayerEliminated(player));
    }

    /**
     * Called when an upstream revival mechanism changes a resting player back
     * to adventure mode. The upstream mechanism itself owns the actual revive.
     * No-op unless {@link #prepareUpstreamRevival} marked this UUID; does not
     * touch {@link #ELIMINATED_PLAYERS} for login / occupancy adventure sets.
     */
    public static void finishUpstreamRevival(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!completeUpstreamRevival(player.getUUID())) {
            return;
        }
        HabiTrainCore.LOGGER.info(
                "[EliminatedRest] {} cleared eliminated/rest state for upstream revival in {}",
                player.getGameProfile().getName(), player.serverLevel().dimension().location());
        syncPrompt(player, true);
    }

    /**
     * Upstream revival code generally teleports by coordinates only, which
     * means it relies on the revived player still being in the match world.
     * Return a resting player there immediately before that code makes the
     * player an adventurer, without deciding whether the revival is allowed.
     */
    public static void prepareUpstreamRevival(ServerPlayer player) {
        if (player == null || !beginUpstreamRevival(player.getUUID())) {
            return;
        }

        ServerLevel matchLevel = getRestingMatchLevel(player);
        if (matchLevel != null && player.serverLevel() != matchLevel) {
            player.teleportTo(matchLevel, player.getX(), player.getY(), player.getZ(), player.getYRot(),
                    player.getXRot());
        }
    }

    /**
     * Marks a resting player as an in-flight upstream revive. ENTERING_REST is
     * the rest-area occupancy path and must not count as revival.
     */
    static boolean beginUpstreamRevival(UUID playerId) {
        if (playerId == null || ENTERING_REST_PLAYERS.contains(playerId)) {
            return false;
        }
        if (!RESTING_MATCH_LEVELS.containsKey(playerId)) {
            return false;
        }
        REVIVING_PLAYERS.add(playerId);
        return true;
    }

    /**
     * Drops rest + elimination only for a UUID previously marked by
     * {@link #beginUpstreamRevival}. Otherwise a no-op (including eliminated
     * spectators who were set to adventure without preparing).
     */
    static boolean completeUpstreamRevival(UUID playerId) {
        if (playerId == null || ENTERING_REST_PLAYERS.contains(playerId)) {
            return false;
        }
        if (!REVIVING_PLAYERS.remove(playerId)) {
            return false;
        }
        RESTING_MATCH_LEVELS.remove(playerId);
        ELIMINATED_PLAYERS.remove(playerId);
        ELIMINATED_MATCH_LEVELS.remove(playerId);
        PROMPT_STATES.remove(playerId);
        TOGGLE_COOLDOWN_UNTIL.remove(playerId);
        return true;
    }

    private static void toggle(ServerPlayer player) {
        if (player == null) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        Long until = TOGGLE_COOLDOWN_UNTIL.get(player.getUUID());
        if (until != null && now < until) {
            return;
        }
        TOGGLE_COOLDOWN_UNTIL.put(player.getUUID(), now + TOGGLE_COOLDOWN_TICKS);

        if (isResting(player)) {
            ServerLevel matchLevel = getRestingMatchLevel(player);
            SREGameWorldComponent matchWorld = matchLevel == null
                    ? null : SREGameWorldComponent.KEY.get(matchLevel);
            if (matchLevel == null || matchWorld == null || !matchWorld.isRunning()) {
                RESTING_MATCH_LEVELS.remove(player.getUUID());
                syncPrompt(player, true);
                return;
            }
            returnToSpectator(player, matchLevel);
            return;
        }

        ServerLevel matchLevel = player.serverLevel();
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(matchLevel);
        if (gameWorld == null || !gameWorld.isRunning()) {
            syncPrompt(player, true);
            return;
        }
        if (!ELIMINATED_PLAYERS.contains(player.getUUID()) || !GameUtils.isPlayerEliminated(player)) {
            syncPrompt(player, true);
            return;
        }
        moveToRestArea(player, matchLevel);
    }

    private static void moveToRestArea(ServerPlayer player, ServerLevel matchLevel) {
        // This deliberately matches GameUtils.resetPlayerAfterGame: the selected
        // map owns spawnPos, while the post-game/rest area is in the overworld.
        AreasWorldComponent.PosWithOrientation spawn = getPostGameSpawn(matchLevel);
        ServerLevel postGameLevel = player.getServer().overworld();
        player.teleportTo(postGameLevel, spawn.pos.x, spawn.pos.y, spawn.pos.z, spawn.yaw, spawn.pitch);

        // Set the physical game mode before recording the virtual spectator
        // state; the ServerPlayer mixin then preserves elimination semantics.
        ENTERING_REST_PLAYERS.add(player.getUUID());
        try {
            player.setGameMode(GameType.ADVENTURE);
            RESTING_MATCH_LEVELS.put(player.getUUID(), matchLevel.dimension());
        } finally {
            ENTERING_REST_PLAYERS.remove(player.getUUID());
        }
        syncPrompt(player, true);

        AreasWorldComponent areas = AreasWorldComponent.KEY.get(matchLevel);
        String mapName = areas.mapName;
        HabiTrainCore.LOGGER.info("[EliminatedRest] {} entered rest: mapWorld={}, map={}, targetWorld={}, pos=({}, {}, {})",
                player.getGameProfile().getName(), matchLevel.dimension().location(), mapName,
                postGameLevel.dimension().location(), spawn.pos.x, spawn.pos.y, spawn.pos.z);

        AABB playArea = areas.getPlayArea();
        if (playArea != null && (spawn.pos.z < playArea.minZ || spawn.pos.z > playArea.maxZ)) {
            HabiTrainCore.LOGGER.warn(
                    "[EliminatedRest] {} rest pos z={} is outside map '{}' playArea z=[{}, {}]; "
                            + "SRE out-of-area systems treat this as normal for the post-game area",
                    player.getGameProfile().getName(), spawn.pos.z, mapName, playArea.minZ, playArea.maxZ);
        }

        // Deliberately do not call TrainVoicePlugin.resetPlayer here. Death
        // already assigned the player to Train Spectators, which must stay.
    }

    private static void returnToSpectator(ServerPlayer player, ServerLevel matchLevel) {
        RESTING_MATCH_LEVELS.remove(player.getUUID());
        player.setGameMode(GameType.SPECTATOR);

        AreasWorldComponent.PosWithOrientation spawn = AreasWorldComponent.KEY.get(matchLevel).getSpectatorSpawnPos();
        if (spawn != null) {
            player.teleportTo(matchLevel, spawn.pos.x, spawn.pos.y, spawn.pos.z, spawn.yaw, spawn.pitch);
        }

        HabiTrainCore.LOGGER.info("[EliminatedRest] {} returned to spectator: mapWorld={}, pos={}",
                player.getGameProfile().getName(), matchLevel.dimension().location(),
                spawn == null ? "<missing>" : "(" + spawn.pos.x + ", " + spawn.pos.y + ", " + spawn.pos.z + ")");

        // Reassert the DLC's simple-voice spectator group without touching the
        // lobby group logic, which only runs outside an active match.
        TrainVoicePlugin.addPlayer(player.getUUID());
        syncPrompt(player, true);
    }

    private static AreasWorldComponent.PosWithOrientation getPostGameSpawn(ServerLevel level) {
        AreasWorldComponent.PosWithOrientation configured = AreasWorldComponent.KEY.get(level).getSpawnPos();
        if (configured != null) {
            return configured;
        }

        // Match the DLC's GameUtils.resetPlayerAfterGame fallback exactly.
        HabiTrainCore.LOGGER.warn("[EliminatedRest] map world {} has no post-game spawn; using shared world spawn",
                level.dimension().location());
        BlockPos fallback = level.getSharedSpawnPos();
        return new AreasWorldComponent.PosWithOrientation(
                fallback.getX(), fallback.getY(), fallback.getZ(), level.getSharedSpawnAngle(), 0.0F);
    }

    private static ServerLevel getRestingMatchLevel(ServerPlayer player) {
        ResourceKey<Level> matchLevelKey = RESTING_MATCH_LEVELS.get(player.getUUID());
        return matchLevelKey == null ? null : player.getServer().getLevel(matchLevelKey);
    }

    private static boolean canEnterRestArea(ServerPlayer player) {
        if (player == null || isResting(player) || !ELIMINATED_PLAYERS.contains(player.getUUID())) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(level);
        return gameWorld != null && gameWorld.isRunning() && GameUtils.isPlayerEliminated(player);
    }

    private static void syncPromptStates(MinecraftServer server) {
        if (ELIMINATED_PLAYERS.isEmpty()) {
            return;
        }
        for (UUID playerId : new HashSet<>(ELIMINATED_PLAYERS)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                PROMPT_STATES.remove(playerId);
                continue;
            }
            syncPrompt(player, false);
        }
    }

    private static void syncPrompt(ServerPlayer player, boolean force) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        boolean visible = canEnterRestArea(player);
        RestPromptState current = new RestPromptState(visible, visible || isResting(player));
        RestPromptState previous = PROMPT_STATES.put(playerId, current);
        if (force || !current.equals(previous)) {
            EliminatedRestPromptPayload.sendTo(player, current.visible(), current.canToggle());
        }
    }

    private static void clearRoundStateForLevel(ServerLevel level) {
        if (level == null) {
            return;
        }
        clearRoundStateForDimension(level.dimension(), level.getServer());
    }

    /**
     * Drops rest occupancy and elimination only for players bound to this
     * match dimension. Other dimensions' resters stay. Process-wide wipe is
     * {@link #clearRoundState()} on {@code SERVER_STOPPED} only.
     */
    static void clearRoundStateForDimension(ResourceKey<Level> dimension) {
        clearRoundStateForDimension(dimension, null);
    }

    private static void clearRoundStateForDimension(ResourceKey<Level> dimension, MinecraftServer server) {
        if (dimension == null) {
            return;
        }
        Set<UUID> affected = playersBoundToDimension(dimension);
        if (server != null) {
            for (UUID playerId : affected) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    EliminatedRestPromptPayload.sendTo(player, false, false);
                }
            }
        }
        dropRoundState(affected);
    }

    private static Set<UUID> playersBoundToDimension(ResourceKey<Level> dimension) {
        Set<UUID> affected = new HashSet<>();
        for (Map.Entry<UUID, ResourceKey<Level>> e : RESTING_MATCH_LEVELS.entrySet()) {
            if (dimension.equals(e.getValue())) {
                affected.add(e.getKey());
            }
        }
        for (Map.Entry<UUID, ResourceKey<Level>> e : ELIMINATED_MATCH_LEVELS.entrySet()) {
            if (dimension.equals(e.getValue())) {
                affected.add(e.getKey());
            }
        }
        return affected;
    }

    private static void dropRoundState(Set<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            ELIMINATED_PLAYERS.remove(playerId);
            ELIMINATED_MATCH_LEVELS.remove(playerId);
            RESTING_MATCH_LEVELS.remove(playerId);
            ENTERING_REST_PLAYERS.remove(playerId);
            REVIVING_PLAYERS.remove(playerId);
            PROMPT_STATES.remove(playerId);
            TOGGLE_COOLDOWN_UNTIL.remove(playerId);
        }
    }

    private static void clearRoundState() {
        ELIMINATED_PLAYERS.clear();
        ELIMINATED_MATCH_LEVELS.clear();
        RESTING_MATCH_LEVELS.clear();
        ENTERING_REST_PLAYERS.clear();
        REVIVING_PLAYERS.clear();
        PROMPT_STATES.clear();
        TOGGLE_COOLDOWN_UNTIL.clear();
    }

    static void resetTablesForTest() {
        clearRoundState();
    }

    static void seedDisconnectFixture(UUID playerId, ResourceKey<Level> restKey) {
        if (playerId == null) {
            return;
        }
        ELIMINATED_PLAYERS.add(playerId);
        if (restKey != null) {
            RESTING_MATCH_LEVELS.put(playerId, restKey);
            ELIMINATED_MATCH_LEVELS.put(playerId, restKey);
        }
        ENTERING_REST_PLAYERS.add(playerId);
        PROMPT_STATES.put(playerId, new RestPromptState(true, true));
        TOGGLE_COOLDOWN_UNTIL.put(playerId, 1L);
    }

    static void seedRestingEliminated(UUID playerId, ResourceKey<Level> matchLevel) {
        if (playerId == null) {
            return;
        }
        ELIMINATED_PLAYERS.add(playerId);
        if (matchLevel != null) {
            RESTING_MATCH_LEVELS.put(playerId, matchLevel);
            ELIMINATED_MATCH_LEVELS.put(playerId, matchLevel);
        }
    }

    static void seedEnteringRest(UUID playerId) {
        if (playerId != null) {
            ENTERING_REST_PLAYERS.add(playerId);
        }
    }

    static boolean hasEliminated(UUID playerId) {
        return playerId != null && ELIMINATED_PLAYERS.contains(playerId);
    }

    static boolean hasRestOccupancy(UUID playerId) {
        return playerId != null && RESTING_MATCH_LEVELS.containsKey(playerId);
    }

    static boolean isMarkedReviving(UUID playerId) {
        return playerId != null && REVIVING_PLAYERS.contains(playerId);
    }

    static boolean hasTransientDisconnectState(UUID playerId) {
        return playerId != null && (ENTERING_REST_PLAYERS.contains(playerId)
                || PROMPT_STATES.containsKey(playerId)
                || TOGGLE_COOLDOWN_UNTIL.containsKey(playerId)
                || REVIVING_PLAYERS.contains(playerId));
    }

    private record RestPromptState(boolean visible, boolean canToggle) {
    }
}
