package com.habitrain.core.game.sre.rest;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.voice.VoiceGroupService;
import com.habitrain.core.network.EliminatedRestPromptPayload;
import com.mojang.brigadier.context.CommandContext;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.AllowSpectatorPlayerInAreas;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import io.wifi.starrailexpress.event.OnPlayerDeath;
import io.wifi.starrailexpress.game.GameUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import pro.fazeclan.river.stupid_express.modifier.refugee.cca.RefugeeComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private static final Map<UUID, StashedLoadout> STASHED_LOADOUTS = new HashMap<>();
    private static final int TOGGLE_COOLDOWN_TICKS = 10;
    /** 往返等待房间的玩家命令；客户端右上角提示里显示的就是它。 */
    public static final String REST_COMMAND = "rest";
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
                    VoiceGroupService.ensureSpectatorVoiceGroup(playerId);
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

        // 不占用按键：出局玩家在右上角提示里看到 /rest，输入即可往返等待房间。
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal(REST_COMMAND)
                        .executes(EliminatedRestAreaService::executeRestCommand)));

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
        VoiceGroupService.ensureSpectatorVoiceGroup(playerId);
    }

    /**
     * Used by the GameUtils survival/spectator mixins so all upstream survival checks continue
     * to regard a resting player as eliminated.
     */
    public static boolean isResting(ServerPlayer player) {
        return player != null && RESTING_MATCH_LEVELS.containsKey(player.getUUID());
    }

    /** The physical spectator-to-adventure transition while entering rest is not a revival. */
    public static boolean isEnteringRest(ServerPlayer player) {
        return player != null && ENTERING_REST_PLAYERS.contains(player.getUUID());
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
            STASHED_LOADOUTS.remove(playerId);
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
        ResourceKey<Level> recordedMatchKey = ELIMINATED_MATCH_LEVELS.get(playerId);
        if (recordedMatchKey == null) {
            recordedMatchKey = RESTING_MATCH_LEVELS.get(playerId);
        }
        ServerLevel recordedMatchLevel = (server != null && recordedMatchKey != null)
                ? server.getLevel(recordedMatchKey) : null;
        Boolean matchStopped = lookupOriginalMatchStopped(playerId, key -> {
            ServerLevel matchLevel = server == null ? null : server.getLevel(key);
            SREGameWorldComponent matchWorld = matchLevel == null ? null : SREGameWorldComponent.KEY.get(matchLevel);
            return matchWorld == null ? null : !matchWorld.isRunning();
        });
        boolean stillEliminated = GameUtils.isPlayerEliminated(player) || ELIMINATED_PLAYERS.contains(playerId);
        applyJoinReconnect(playerId, RESTING_MATCH_LEVELS.containsKey(playerId),
                matchStopped != null, Boolean.TRUE.equals(matchStopped),
                stillEliminated);

        // 非 overworld 维度对局中，若玩家在休息区（overworld）断线重连，handleDisconnect 已移除 RESTING_MATCH_LEVELS，
        // 此时玩家落在 overworld 且可能仍处于 ADVENTURE 模式；将其送回对局维度的旁观出生点并确保加入隔离观战语音组。
        if (Boolean.FALSE.equals(matchStopped) && ELIMINATED_PLAYERS.contains(playerId) && recordedMatchLevel != null) {
            if (player.serverLevel() != recordedMatchLevel
                    || player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                returnToSpectator(player, recordedMatchLevel);
            } else {
                VoiceGroupService.ensureSpectatorVoiceGroup(playerId);
            }
        }
    }

    /** null means unknown; the player's current lobby is never a match fallback. */
    static Boolean lookupOriginalMatchStopped(UUID playerId,
            java.util.function.Function<ResourceKey<Level>, Boolean> worldLookup) {
        ResourceKey<Level> key = ELIMINATED_MATCH_LEVELS.get(playerId);
        if (key == null) key = RESTING_MATCH_LEVELS.get(playerId);
        return key == null ? null : worldLookup.apply(key);
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
        restoreStashedLoadout(player);
        VoiceGroupService.resetPlayerVoiceGroup(player.getUUID());
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

        restoreStashedLoadout(player);
        ServerLevel matchLevel = getRestingMatchLevel(player);
        if (matchLevel != null && player.serverLevel() != matchLevel) {
            player.teleportTo(matchLevel, player.getX(), player.getY(), player.getZ(), player.getYRot(),
                    player.getXRot());
        }
    }

    /**
     * Called from explicit upstream revival entrypoints ({@code GameUtils.revivePlayerToItsRoom}
     * / {@code GameUtils.revivePlayer}) so even a player who stayed in the train as a spectator
     * (never entering the rest area) has their elimination state cleared upon revival.
     */
    public static void onExplicitUpstreamRevival(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        if (ENTERING_REST_PLAYERS.contains(playerId)) {
            return;
        }
        restoreStashedLoadout(player);
        RESTING_MATCH_LEVELS.remove(playerId);
        ELIMINATED_PLAYERS.remove(playerId);
        ELIMINATED_MATCH_LEVELS.remove(playerId);
        REVIVING_PLAYERS.remove(playerId);
        PROMPT_STATES.remove(playerId);
        TOGGLE_COOLDOWN_UNTIL.remove(playerId);
        VoiceGroupService.resetPlayerVoiceGroup(playerId);
        syncPrompt(player, true);
    }

    /**
     * When a physical spectator in an active match is switched back to {@link GameType#ADVENTURE}
     * (e.g. Necromancer/Refugee/Timekeeper revival without entering the rest area), clear their
     * elimination record so they cannot later use {@code /rest} if swallowed by a Pelican.
     */
    public static void clearEliminatedOnSpectatorToAdventureRevival(ServerPlayer player) {
        if (player == null || ENTERING_REST_PLAYERS.contains(player.getUUID())) {
            return;
        }
        ServerLevel level = resolveMatchLevel(player);
        if (level == null) {
            return;
        }
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(level);
        if (gameWorld != null && gameWorld.isRunning()) {
            onExplicitUpstreamRevival(player);
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

    private static int executeRestCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("commands.habitrain_core.rest.player_only"));
            return 0;
        }
        ToggleResult result = toggle(player);
        switch (result) {
            case ENTERED -> ctx.getSource().sendSuccess(
                    () -> Component.translatable("commands.habitrain_core.rest.entered"), false);
            case RETURNED -> ctx.getSource().sendSuccess(
                    () -> Component.translatable("commands.habitrain_core.rest.returned"), false);
            case COOLDOWN -> ctx.getSource().sendFailure(
                    Component.translatable("commands.habitrain_core.rest.cooldown"));
            case NOT_RUNNING -> ctx.getSource().sendFailure(
                    Component.translatable("commands.habitrain_core.rest.not_running"));
            case NOT_ELIMINATED -> ctx.getSource().sendFailure(
                    Component.translatable("commands.habitrain_core.rest.not_eliminated"));
            case REFUGEE_ACTIVE -> ctx.getSource().sendFailure(
                    Component.translatable("commands.habitrain_core.rest.refugee_active"));
        }
        return result == ToggleResult.ENTERED || result == ToggleResult.RETURNED ? 1 : 0;
    }

    private enum ToggleResult { ENTERED, RETURNED, COOLDOWN, NOT_RUNNING, NOT_ELIMINATED, REFUGEE_ACTIVE }

    private static ToggleResult toggle(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        Long until = TOGGLE_COOLDOWN_UNTIL.get(player.getUUID());
        if (until != null && now < until) {
            return ToggleResult.COOLDOWN;
        }
        TOGGLE_COOLDOWN_UNTIL.put(player.getUUID(), now + TOGGLE_COOLDOWN_TICKS);

        if (isResting(player)) {
            ServerLevel matchLevel = getRestingMatchLevel(player);
            SREGameWorldComponent matchWorld = matchLevel == null
                    ? null : SREGameWorldComponent.KEY.get(matchLevel);
            if (matchLevel == null || matchWorld == null || !matchWorld.isRunning()) {
                RESTING_MATCH_LEVELS.remove(player.getUUID());
                clearPlayerInventoryAndEffects(player);
                syncPrompt(player, true);
                return ToggleResult.NOT_RUNNING;
            }
            returnToSpectator(player, matchLevel);
            return ToggleResult.RETURNED;
        }

        ServerLevel matchLevel = resolveMatchLevel(player);
        SREGameWorldComponent gameWorld = matchLevel == null ? null : SREGameWorldComponent.KEY.get(matchLevel);
        if (matchLevel == null || gameWorld == null || !gameWorld.isRunning()) {
            syncPrompt(player, true);
            return ToggleResult.NOT_RUNNING;
        }
        if (!ELIMINATED_PLAYERS.contains(player.getUUID()) || !GameUtils.isPlayerEliminated(player)) {
            syncPrompt(player, true);
            return ToggleResult.NOT_ELIMINATED;
        }
        // Refugee deaths are temporary: keep the physical spectator and upstream
        // camera restriction intact until the authoritative snapshot is restored.
        if (isRefugeeMoment(matchLevel)) {
            syncPrompt(player, true);
            return ToggleResult.REFUGEE_ACTIVE;
        }
        moveToRestArea(player, matchLevel);
        return ToggleResult.ENTERED;
    }

    private static void moveToRestArea(ServerPlayer player, ServerLevel matchLevel) {
        // 暂存并清空对局背包与药水效果，防止在 ADVENTURE 休息区使用/走私/箱子复制对局武器（GAME-SRE-05）。
        stashAndClearLoadout(player);

        // This deliberately matches GameUtils.resetPlayerAfterGame: the selected
        // map owns spawnPos, while the post-game/rest area is in the overworld.
        AreasWorldComponent.PosWithOrientation spawn = getPostGameSpawn(matchLevel);
        ServerLevel postGameLevel = player.getServer().overworld();
        player.setCamera(player);
        player.teleportTo(postGameLevel, spawn.pos.x, spawn.pos.y, spawn.pos.z, spawn.yaw, spawn.pitch);

        ENTERING_REST_PLAYERS.add(player.getUUID());
        try {
            player.setGameMode(GameType.ADVENTURE);
            RESTING_MATCH_LEVELS.put(player.getUUID(), matchLevel.dimension());
            ELIMINATED_MATCH_LEVELS.putIfAbsent(player.getUUID(), matchLevel.dimension());
        } finally {
            ENTERING_REST_PLAYERS.remove(player.getUUID());
        }
        VoiceGroupService.ensureSpectatorVoiceGroup(player.getUUID());
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
    }

    private static void returnToSpectator(ServerPlayer player, ServerLevel matchLevel) {
        RESTING_MATCH_LEVELS.remove(player.getUUID());
        // 清空在休息区期间可能拾取的任何物品，保持旁观态净空
        clearPlayerInventoryAndEffects(player);
        player.setGameMode(GameType.SPECTATOR);
        player.setCamera(player);

        AreasWorldComponent.PosWithOrientation spawn = AreasWorldComponent.KEY.get(matchLevel).getSpectatorSpawnPos();
        if (spawn != null) {
            player.teleportTo(matchLevel, spawn.pos.x, spawn.pos.y, spawn.pos.z, spawn.yaw, spawn.pitch);
        }

        HabiTrainCore.LOGGER.info("[EliminatedRest] {} returned to spectator: mapWorld={}, pos={}",
                player.getGameProfile().getName(), matchLevel.dimension().location(),
                spawn == null ? "<missing>" : "(" + spawn.pos.x + ", " + spawn.pos.y + ", " + spawn.pos.z + ")");

        VoiceGroupService.ensureSpectatorVoiceGroup(player.getUUID());
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

    private static ServerLevel resolveMatchLevel(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        ServerLevel current = player.serverLevel();
        SREGameWorldComponent currentWorld = current == null ? null : SREGameWorldComponent.KEY.get(current);
        if (currentWorld != null && currentWorld.isRunning()) {
            return current;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        ResourceKey<Level> recorded = ELIMINATED_MATCH_LEVELS.get(player.getUUID());
        if (recorded == null) {
            recorded = RESTING_MATCH_LEVELS.get(player.getUUID());
        }
        if (recorded != null) {
            ServerLevel recordedLevel = server.getLevel(recorded);
            SREGameWorldComponent recordedWorld = recordedLevel == null ? null : SREGameWorldComponent.KEY.get(recordedLevel);
            if (recordedWorld != null && recordedWorld.isRunning()) {
                return recordedLevel;
            }
        }
        return null;
    }

    private static boolean canEnterRestArea(ServerPlayer player) {
        if (player == null || isResting(player) || !ELIMINATED_PLAYERS.contains(player.getUUID())) {
            return false;
        }
        ServerLevel level = resolveMatchLevel(player);
        SREGameWorldComponent gameWorld = level == null ? null : SREGameWorldComponent.KEY.get(level);
        return gameWorld != null && gameWorld.isRunning() && !isRefugeeMoment(level)
                && GameUtils.isPlayerEliminated(player);
    }

    private static boolean isRefugeeMoment(ServerLevel matchLevel) {
        RefugeeComponent refugee = RefugeeComponent.KEY.get(matchLevel);
        return refugee != null && refugee.isAnyRevivals;
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
            VoiceGroupService.ensureSpectatorVoiceGroup(playerId);
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

    private static void stashAndClearLoadout(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        if (!STASHED_LOADOUTS.containsKey(playerId)) {
            var inv = player.getInventory();
            List<ItemStack> items = new ArrayList<>(inv.items.size());
            for (ItemStack s : inv.items) items.add(s.copy());
            List<ItemStack> armor = new ArrayList<>(inv.armor.size());
            for (ItemStack s : inv.armor) armor.add(s.copy());
            List<ItemStack> offhand = new ArrayList<>(inv.offhand.size());
            for (ItemStack s : inv.offhand) offhand.add(s.copy());
            ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried().copy() : ItemStack.EMPTY;

            Map<ResourceLocation, ItemStack> extraSlots = new HashMap<>();
            try {
                ExtraSlotComponent.KEY.maybeGet(player).ifPresent(extra -> {
                    if (extra.SLOTS != null) {
                        for (var e : extra.SLOTS.entrySet()) {
                            if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                                extraSlots.put(e.getKey(), e.getValue().copy());
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            List<MobEffectInstance> effects = new ArrayList<>();
            for (MobEffectInstance eff : player.getActiveEffects()) {
                effects.add(new MobEffectInstance(eff));
            }
            STASHED_LOADOUTS.put(playerId, new StashedLoadout(items, armor, offhand, carried, extraSlots, effects));
        }
        clearPlayerInventoryAndEffects(player);
    }

    private static void clearPlayerInventoryAndEffects(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            player.getInventory().clearContent();
            if (player.containerMenu != null) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
            ExtraSlotComponent.KEY.maybeGet(player).ifPresent(ExtraSlotComponent::clear);
            player.removeAllEffects();
            player.inventoryMenu.broadcastChanges();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[EliminatedRest] clearPlayerInventoryAndEffects failed for {}", player.getUUID(), t);
        }
    }

    private static void restoreStashedLoadout(ServerPlayer player) {
        if (player == null) {
            return;
        }
        StashedLoadout loadout = STASHED_LOADOUTS.remove(player.getUUID());
        if (loadout == null) {
            return;
        }
        try {
            clearPlayerInventoryAndEffects(player);
            var inv = player.getInventory();
            for (int i = 0; i < Math.min(inv.items.size(), loadout.items().size()); i++) {
                inv.items.set(i, loadout.items().get(i).copy());
            }
            for (int i = 0; i < Math.min(inv.armor.size(), loadout.armor().size()); i++) {
                inv.armor.set(i, loadout.armor().get(i).copy());
            }
            for (int i = 0; i < Math.min(inv.offhand.size(), loadout.offhand().size()); i++) {
                inv.offhand.set(i, loadout.offhand().get(i).copy());
            }
            if (player.containerMenu != null && loadout.carried() != null && !loadout.carried().isEmpty()) {
                player.containerMenu.setCarried(loadout.carried().copy());
            }
            if (!loadout.extraSlots().isEmpty()) {
                ExtraSlotComponent.KEY.maybeGet(player).ifPresent(extra -> {
                    for (var e : loadout.extraSlots().entrySet()) {
                        extra.setItem(e.getKey(), e.getValue().copy());
                    }
                });
            }
            for (MobEffectInstance eff : loadout.effects()) {
                player.addEffect(new MobEffectInstance(eff));
            }
            player.inventoryMenu.broadcastChanges();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[EliminatedRest] restoreStashedLoadout failed for {}", player.getUUID(), t);
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
            boolean otherMatchRunning = false;
            for (ServerLevel lvl : server.getAllLevels()) {
                if (dimension.equals(lvl.dimension())) {
                    continue;
                }
                SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(lvl);
                if (gw != null && gw.isRunning()) {
                    otherMatchRunning = true;
                    break;
                }
            }
            if (!otherMatchRunning) {
                for (UUID playerId : ELIMINATED_PLAYERS) {
                    if (!ELIMINATED_MATCH_LEVELS.containsKey(playerId)) {
                        affected.add(playerId);
                    }
                }
            }
            for (UUID playerId : affected) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    if (RESTING_MATCH_LEVELS.containsKey(playerId)) {
                        clearPlayerInventoryAndEffects(player);
                    }
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
            STASHED_LOADOUTS.remove(playerId);
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
        STASHED_LOADOUTS.clear();
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

    private record StashedLoadout(
            List<ItemStack> items,
            List<ItemStack> armor,
            List<ItemStack> offhand,
            ItemStack carried,
            Map<ResourceLocation, ItemStack> extraSlots,
            List<MobEffectInstance> effects) {
    }
}
