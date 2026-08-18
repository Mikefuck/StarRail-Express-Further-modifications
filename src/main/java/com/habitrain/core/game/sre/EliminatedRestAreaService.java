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
    private static final Set<UUID> ELIMINATED_PLAYERS = new HashSet<>();
    /**
     * The match world is retained while a player is in the post-game area.
     * The post-game area itself is always in the overworld, so reading the
     * player's current level on the second G press would otherwise lose the
     * map-specific spectator spawn.
     */
    private static final Map<UUID, ResourceKey<Level>> RESTING_MATCH_LEVELS = new HashMap<>();
    private static final Set<UUID> ENTERING_REST_PLAYERS = new HashSet<>();
    private static final Map<UUID, RestPromptState> PROMPT_STATES = new HashMap<>();
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
                    ELIMINATED_PLAYERS.add(serverPlayer.getUUID());
                    RESTING_MATCH_LEVELS.remove(serverPlayer.getUUID());
                    syncPrompt(serverPlayer, false);
                }
            }
        });
        OnGameStarted.EVENT.register(level -> clearRoundState(level.getServer()));
        OnGameEnd.EVENT.register((level, gameWorld) -> clearRoundState(level.getServer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearRoundState());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                syncPrompt(handler.getPlayer(), true));
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
     * Used by the ServerPlayer mixin so all upstream survival checks continue
     * to regard a resting player as eliminated.
     */
    public static boolean isResting(ServerPlayer player) {
        return player != null && RESTING_MATCH_LEVELS.containsKey(player.getUUID());
    }

    /**
     * Called when an upstream revival mechanism changes a resting player back
     * to adventure mode. The upstream mechanism itself owns the actual revive.
     */
    public static void finishUpstreamRevival(ServerPlayer player) {
        if (player == null || ENTERING_REST_PLAYERS.contains(player.getUUID())) {
            return;
        }
        UUID playerId = player.getUUID();
        boolean wasTracked = RESTING_MATCH_LEVELS.remove(playerId) != null;
        wasTracked |= ELIMINATED_PLAYERS.remove(playerId);
        wasTracked |= PROMPT_STATES.containsKey(playerId);
        if (wasTracked) {
            HabiTrainCore.LOGGER.info(
                    "[EliminatedRest] {} cleared eliminated/rest state for upstream revival in {}",
                    player.getGameProfile().getName(), player.serverLevel().dimension().location());
            syncPrompt(player, true);
        }
    }

    /**
     * Upstream revival code generally teleports by coordinates only, which
     * means it relies on the revived player still being in the match world.
     * Return a resting player there immediately before that code makes the
     * player an adventurer, without deciding whether the revival is allowed.
     */
    public static void prepareUpstreamRevival(ServerPlayer player) {
        if (!isResting(player)) {
            return;
        }

        ServerLevel matchLevel = getRestingMatchLevel(player);
        if (matchLevel != null && player.serverLevel() != matchLevel) {
            player.teleportTo(matchLevel, player.getX(), player.getY(), player.getZ(), player.getYRot(),
                    player.getXRot());
        }
    }

    private static void toggle(ServerPlayer player) {
        if (player == null) {
            return;
        }

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

    private static void clearRoundState(MinecraftServer server) {
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                EliminatedRestPromptPayload.sendTo(player, false, false);
            }
        }
        clearRoundState();
    }

    private static void clearRoundState() {
        ELIMINATED_PLAYERS.clear();
        RESTING_MATCH_LEVELS.clear();
        ENTERING_REST_PLAYERS.clear();
        PROMPT_STATES.clear();
    }

    private record RestPromptState(boolean visible, boolean canToggle) {
    }
}
