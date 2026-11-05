package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import io.wifi.starrailexpress.event.OnPlayerDeath;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.api.SREGameModes;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.agmas.noellesroles.component.DeathPenaltyComponent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconciles only Lust's identity-hiding contribution after all death/role/rest transitions finish. */
public final class LustRestrictionService {
    private static final Map<ResourceKey<Level>, LustRestrictionState> ROUNDS = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> DEATH_MATCHES = new HashMap<>();

    private LustRestrictionService() {}

    public static void init() {
        OnPlayerDeath.EVENT.register((player, reason) -> onDeath(player));
        OnGameStarted.EVENT.register(LustRestrictionService::resetRound);
        OnGameEnd.EVENT.register((level, game) -> resetRound(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ROUNDS.clear();
            DEATH_MATCHES.clear();
        });
        // Observe source changes every tick, but write/sync only when the derived flag changes.
        // END_SERVER_TICK also covers role changes, disconnects and dimension/rest transfers.
        ServerTickEvents.END_SERVER_TICK.register(LustRestrictionService::reconcile);
    }

    private static boolean active(ServerLevel level) {
        var game = SREGameWorldComponent.KEY.get(level);
        return game != null && game.getGameStatus() == SREGameWorldComponent.GameStatus.ACTIVE
                && game.getGameMode() != null
                && !SREGameModes.LOOSE_ENDS_ID.equals(game.getGameMode().identifier);
    }

    public static boolean isConfirmedDead(Player player) {
        if (player.level().isClientSide()) return false;
        ResourceKey<Level> match = DEATH_MATCHES.get(player.getUUID());
        var state = ROUNDS.get(match);
        return state != null && state.isDead(player.getUUID());
    }

    public static boolean isEffectiveLust(ServerLevel level, Player player) {
        return active(level) && player instanceof ServerPlayer online && player.level() == level
                && level.getServer().getPlayerList().getPlayer(player.getUUID()) == online
                && !EliminatedRestAreaService.isResting(online)
                && LustComponent.isAliveParticipant(player)
                && HabiRoles.isHabiRole(player, SevenSins.LUST);
    }

    public static void onDeath(Player player) {
        if (!(player.level() instanceof ServerLevel level) || !active(level)
                || SREGameWorldComponent.KEY.get(level).getRole(player) == null) return;
        ROUNDS.computeIfAbsent(level.dimension(), ignored -> new LustRestrictionState()).died(player.getUUID());
        DEATH_MATCHES.put(player.getUUID(), level.dimension());
    }

    /** Re-judging spectators must not create a new death or discard an existing exemption. */
    public static void onPenaltyHandled(Level level, List<? extends Player> victims) {
        if (!(level instanceof ServerLevel match) || !active(match)) return;
        for (Player victim : victims) {
            if (!victim.isCreative() && GameUtils.isPlayerEliminated(victim)) onDeath(victim);
        }
    }

    /** Called only by actual revival paths, never by init/clear, login or rest occupancy. */
    public static void onRevival(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.isCreative()
                || EliminatedRestAreaService.isEnteringRest(player)
                || EliminatedRestAreaService.isResting(player)) return;
        ResourceKey<Level> match = DEATH_MATCHES.remove(player.getUUID());
        var state = ROUNDS.get(match);
        if (state != null) state.revived(player.getUUID());
        setHidden(player, false, 0);
    }

    private static void resetRound(ServerLevel level) {
        ROUNDS.remove(level.dimension());
        Set<UUID> reset = new HashSet<>();
        DEATH_MATCHES.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(level.dimension())) return false;
            reset.add(entry.getKey());
            return true;
        });
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (reset.contains(player.getUUID()) || player.level() == level) setHidden(player, false, 0);
        }
    }

    private static void reconcile(MinecraftServer server) {
        Map<ResourceKey<Level>, Integer> owners = new HashMap<>();
        ROUNDS.keySet().removeIf(dimension -> server.getLevel(dimension) == null);
        DEATH_MATCHES.values().removeIf(dimension -> server.getLevel(dimension) == null);
        for (ServerLevel level : server.getAllLevels()) {
            if (!active(level)) {
                if (ROUNDS.containsKey(level.dimension())) resetRound(level);
                continue;
            }
            // Covers joining an existing match and death paths that did not emit OnPlayerDeath.
            onPenaltyHandled(level, level.players());
            int count = (int) level.players().stream().filter(player -> isEffectiveLust(level, player)).count();
            owners.put(level.dimension(), count);
        }
        // Offline deaths stay in the round table. Reconnected entities receive the same death's flag.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var match = DEATH_MATCHES.get(player.getUUID());
            var state = ROUNDS.get(match);
            int count = owners.getOrDefault(match, 0);
            setHidden(player, state != null && state.hidden(player.getUUID(), count > 0), count);
        }
    }

    /** Invoked exclusively by the successful existing penalty-stop command branch. */
    public static void adminExempt(DeathPenaltyComponent penalty) {
        if (!(penalty.getPlayer() instanceof ServerPlayer player)) return;
        if (!isConfirmedDead(player) && !player.isCreative() && GameUtils.isPlayerEliminated(player)) onDeath(player);
        var state = ROUNDS.get(DEATH_MATCHES.get(player.getUUID()));
        if (state != null && state.exempt(player.getUUID())) {
            setHidden(player, false, 0);
            // Also send the current upstream fields if the Lust flag was already clear.
            penalty.sync();
            HabiTrainCore.LOGGER.info("[LustRestriction] admin exemption: player={}, match={}, upstreamExpiry={}, camera={}, position={}",
                    player.getUUID(), DEATH_MATCHES.get(player.getUUID()), penalty.penaltyExpiry,
                    penalty.limitCameraUUID, penalty.limitPos);
        }
    }

    public static Component adminFeedback(ServerPlayer player) {
        var state = ROUNDS.get(DEATH_MATCHES.get(player.getUUID()));
        if (state == null || !state.isExempt(player.getUUID())) {
            return Component.translatable("message.habitrain_core.sin_lust.admin_not_eliminated");
        }
        var penalty = DeathPenaltyComponent.KEY.get(player);
        return Component.translatable(penalty.penaltyExpiry != 0 || penalty.limitCameraUUID != null || penalty.limitPos != null
                ? "message.habitrain_core.sin_lust.admin_other_restrictions"
                : "message.habitrain_core.sin_lust.admin_exempt");
    }

    private static void setHidden(ServerPlayer player, boolean hidden, int owners) {
        var penalty = DeathPenaltyComponent.KEY.get(player);
        var lust = (LustIdentityRestriction) penalty;
        if (lust.habitrain$isLustIdentityHidden() == hidden) return;
        lust.habitrain$setLustIdentityHidden(hidden);
        HabiTrainCore.LOGGER.info("[LustRestriction] player={}, match={}, hidden={}, effectiveLust={}, upstreamExpiry={}, camera={}, position={}",
                player.getUUID(), DEATH_MATCHES.get(player.getUUID()), hidden, owners,
                penalty.penaltyExpiry, penalty.limitCameraUUID, penalty.limitPos);
        if (hidden || penalty.penaltyExpiry == 0) {
            player.sendSystemMessage(Component.translatable(hidden
                    ? "message.habitrain_core.sin_lust.death_hidden"
                    : "message.habitrain_core.sin_lust.death_unhidden"));
        }
    }
}
