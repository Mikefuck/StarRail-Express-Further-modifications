package com.habitrain.core.role.change;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleChangeApi;
import com.habitrain.core.api.role.v2.RoleChangeCause;
import com.habitrain.core.api.role.v2.RoleChangeOptions;
import com.habitrain.core.api.role.v2.RoleChangeResult;
import com.habitrain.core.api.role.v2.RoleHistoryEntry;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.api.role.v2.RoleView;
import com.habitrain.core.api.role.v2.state.ResetCause;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Default {@link RoleChangeApi} implementation, wrapping the Blackout role
 * manager's unified reassign/eliminate entry points and the v2 catalog for
 * canonical role resolution.
 *
 * <p>Every change is a {@link RoleChangeTransaction}: it resolves the target,
 * captures the old SRE/Blackout role, mutates, commits the old-role cleanup,
 * writes history, and — when a mutation stage fails — rolls the pre-change
 * role/faction/state back (fix-doc §11). The mutation backend, resolver and
 * hook seams are injectable so the transaction logic is unit-testable without
 * a launched game; core binds the catalog-based resolver at initialization.
 */
public final class RoleChangeServiceImpl implements RoleChangeApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleChangeApi");

    // Defaults to a no-op so the service is safe to construct in tests; core
    // binds the catalog-based resolver at initialization.
    private volatile Function<RoleKey, SRERole> resolver = key -> null;
    private volatile BiConsumer<RoleKey, ServerPlayer> lostNotifier =
            (key, player) -> RoleEventDispatcher.INSTANCE.dispatchOnLost(key, player);
    private volatile Function<ServerPlayer, RoleKey> currentRoleLookup = this::lookupCurrentRole;
    private final Map<UUID, List<RoleHistoryEntry>> timelines = new ConcurrentHashMap<>();
    private volatile BiConsumer<ServerPlayer, SRERole> sreClearer = RoleChangeServiceImpl::clearSreRole;
    private volatile StateResetHook stateResetter = RoleChangeServiceImpl::resetState;
    /** Per-transaction live data needed only after the reversible commit succeeds. */
    private final Map<UUID, PendingEffects> pendingEffects = new ConcurrentHashMap<>();

    private final RoleChangeTransaction<ServerPlayer> transaction;

    public RoleChangeServiceImpl() {
        this.transaction = new RoleChangeTransaction<>(
                new ProductionBackend(),
                key -> resolver.apply(key),
                player -> currentRoleLookup.apply(player),
                (key, player) -> { /* beforeLost: prep-only hook; core providers register via lostNotifier */ });
    }

    /** Binds the canonical-role resolver (used by core at runtime). */
    public void setResolver(Function<RoleKey, SRERole> resolver) {
        this.resolver = resolver;
    }

    /** Overrides the onLost dispatcher (used by tests; defaults to the central dispatcher). */
    public void setLostNotifier(BiConsumer<RoleKey, ServerPlayer> notifier) {
        this.lostNotifier = notifier == null ? (k, p) -> {} : notifier;
    }

    /** Overrides how the previous role is read (used by tests; defaults to {@link #current}). */
    public void setCurrentRoleLookup(Function<ServerPlayer, RoleKey> lookup) {
        this.currentRoleLookup = lookup == null ? p -> null : lookup;
    }

    /** Overrides SRE-role clearing (used by tests; defaults to the live game component). */
    public void setSreClearer(BiConsumer<ServerPlayer, SRERole> clearer) {
        this.sreClearer = clearer == null ? (p, r) -> {} : clearer;
    }

    /** Overrides ROLE_LOST state reset (used by tests; defaults to {@link RoleStateApi}). */
    public void setStateResetter(StateResetHook resetter) {
        this.stateResetter = resetter == null ? (p, r, c) -> {} : resetter;
    }

    /** Replaces the mutation backend (fix-doc §11.4 test seam). */
    public void setTransactionBackend(RoleChangeTransaction.Backend<ServerPlayer> backend) {
        this.transaction.setBackend(backend);
    }

    /** Forces a failure at one transaction stage (fix-doc §11.4 test seam). */
    public void setFaultInjector(Function<RoleChangeTransaction.Stage, RuntimeException> injector) {
        this.transaction.setFaultInjector(injector);
    }

    @FunctionalInterface
    public interface StateResetHook {
        void reset(@Nullable ServerPlayer player, @Nullable RoleKey role, ResetCause cause);
    }

    private static void resetState(@Nullable ServerPlayer player, @Nullable RoleKey role, ResetCause cause) {
        try {
            RoleStateApi.instance().reset(player, role, cause);
        } catch (Throwable t) {
            LOGGER.warn("role-state reset {} for {} failed", cause, role, t);
        }
    }

    /** Test-visible timeline for one player. */
    public List<RoleHistoryEntry> recordedTimeline(UUID playerId) {
        return List.copyOf(timelines.getOrDefault(playerId, List.of()));
    }

    public void recordTimeline(@Nullable UUID playerId, @Nullable RoleKey role, RoleChangeCause cause) {
        if (playerId == null || role == null) {
            return;
        }
        RoleSnapshotId snap = null;
        String display = null;
        String provider = null;
        try {
            var current = com.habitrain.core.role.snapshot.RoleSnapshotManager.INSTANCE.current();
            if (current != null) {
                snap = current.id();
                var er = current.find(role).orElse(null);
                if (er != null && er.role() != null) {
                    try {
                        display = er.role().getName().getString();
                    } catch (Throwable ignored) {
                    }
                    if (er.role().identifier() != null) {
                        provider = er.role().identifier().getNamespace();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        timelines.computeIfAbsent(playerId, id -> new ArrayList<>())
                .add(new RoleHistoryEntry(role, cause, System.currentTimeMillis(), snap, display, provider));
    }

    public void recordTimeline(@Nullable ServerPlayer player, @Nullable RoleKey role, RoleChangeCause cause) {
        if (player == null) {
            return;
        }
        recordTimeline(player.getUUID(), role, cause);
    }

    /**
     * Fires {@code onLost} for {@code previous} if it is non-null. Package-visible
     * so unit tests can exercise the hook without a live player or world.
     */
    public void notifyLost(@Nullable ServerPlayer player, @Nullable RoleKey previous) {
        if (previous == null) {
            return;
        }
        lostNotifier.accept(previous, player);
        // Reset after onLost so the hook can still read the departing role's state.
        stateResetter.reset(player, previous, ResetCause.ROLE_LOST);
    }

    /**
     * Fires {@code onLost} when the player's current role differs from {@code next}.
     * {@code next} may be {@code null} (removal).
     */
    public void notifyLostIfChanged(@Nullable ServerPlayer player, @Nullable RoleKey next) {
        RoleKey previous = currentRoleLookup.apply(player);
        if (previous != null && !previous.equals(next)) {
            notifyLost(player, previous);
        }
    }

    /** Resolves a key through the bound resolver (testable). */
    public SRERole resolve(RoleKey key) {
        return resolver.apply(key);
    }

    @Override
    public RoleChangeResult assign(ServerPlayer player, RoleKey role, RoleChangeOptions options) {
        RoleKey canonical = canonicalize(role);
        RoleChangeTransaction.Result r = transaction.assign(
                player, canonical, RoleChangeCause.ASSIGN,
                options.reinitialize(), options.recordTimeline(), options.addStats());
        return r.toPublic();
    }

    @Override
    public RoleChangeResult transform(ServerPlayer player, RoleKey role, RoleChangeCause cause) {
        RoleKey canonical = canonicalize(role);
        RoleChangeTransaction.Result r = transaction.assign(
                player, canonical, cause, false, true, true);
        return r.toPublic();
    }

    @Override
    public RoleChangeResult remove(ServerPlayer player, RoleChangeCause cause) {
        return transaction.remove(player, cause).toPublic();
    }

    @Override
    public RoleView current(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        UUID id = player.getUUID();
        RoleKey role = null;
        String faction = null;
        if (player.level() instanceof ServerLevel level) {
            try {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                SRERole r = game == null ? null : game.getRole(player);
                if (r != null && r.identifier() != null) {
                    role = RoleKey.of(r.identifier());
                }
            } catch (Throwable ignored) {}
            BlackoutRoleManager.Faction f = BlackoutRoleManager.getFaction(level, id);
            faction = f == null ? null : f.name();
        }
        return new RoleView(id, role, faction);
    }

    @Override
    public List<RoleHistoryEntry> history(ServerPlayer player) {
        if (player == null) {
            return List.of();
        }
        List<RoleHistoryEntry> recorded = timelines.get(player.getUUID());
        if (recorded != null && !recorded.isEmpty()) {
            return List.copyOf(recorded);
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(level);
        ResourceLocation roleId = history.get(player.getUUID());
        if (roleId == null) {
            return List.of();
        }
        return List.of(new RoleHistoryEntry(RoleKey.of(roleId), RoleChangeCause.OTHER, 0));
    }

    private @Nullable RoleKey lookupCurrentRole(ServerPlayer player) {
        RoleView view = current(player);
        return view == null ? null : view.role();
    }

    private static void clearSreRole(ServerPlayer player, SRERole ignored) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || game.getRoles() == null) {
                return;
            }
            SRERole old = game.getRoles().get(player.getUUID());
            game.getRoles().remove(player.getUUID());
            game.syncRoles();
            if (old != null) {
                try {
                    org.agmas.harpymodloader.events.ModdedRoleRemoved.EVENT.invoker()
                            .removeModdedRole(player, old);
                } catch (Throwable ignoredEx) {}
            }
        } catch (Throwable ignoredEx) {}
    }

    /** The catalog-based resolver used by core at runtime. */
    public static SRERole resolveViaCatalog(RoleKey key) {
        if (key == null) {
            return null;
        }
        return RoleCatalogApi.instance().find(key).map(EffectiveRole::role).orElse(null);
    }

    /**
     * Canonicalizes a requested v2 key before the transaction starts. Keeping
     * this at the service boundary means aliases cannot leak into state keys,
     * histories or a same-role comparison even if a custom resolver accepts
     * them directly.
     */
    private static RoleKey canonicalize(RoleKey requested) {
        if (requested == null) {
            return null;
        }
        try {
            RoleKey canonical = RoleCatalogApi.instance().canonicalize(requested.location());
            return canonical == null ? requested : canonical;
        } catch (Throwable ignored) {
            return requested;
        }
    }

    /**
     * Production {@link RoleChangeTransaction.Backend}: captures the current
     * SRE/Blackout role, mutates through the Blackout role manager's unified
     * entry points, and restores the captured role on rollback.
     */
    private final class ProductionBackend implements RoleChangeTransaction.Backend<ServerPlayer> {

        @Override
        public RoleChangeTransaction.Captured capture(ServerPlayer player) {
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return RoleChangeTransaction.Captured.none();
            }
            SRERole old = null;
            try {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                old = game == null ? null : game.getRole(player);
            } catch (Throwable ignored) {}
            String faction = null;
            try {
                BlackoutRoleManager.Faction f = BlackoutRoleManager.getFactionForEnd(level, player.getUUID());
                faction = f == null ? null : f.name();
            } catch (Throwable ignored) {}
            return new RoleChangeTransaction.Captured(old, faction);
        }

        @Override
        public void updateSre(ServerPlayer player, @Nullable SRERole role) {
            if (role == null) {
                sreClearer.accept(player, null);
            }
            // For an assignment the SRE write happens inside updateMode via
            // BlackoutRoleManager.reassignRole (which also fires the compatibility
            // events and REPLAY sync), so nothing to do here.
        }

        @Override
        public void updateMode(ServerPlayer player, @Nullable SRERole role,
                               boolean recordTimeline, boolean addStats) {
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            UUID id = player.getUUID();
            if (role == null) {
                BlackoutRoleManager.eliminate(level, id);
            } else {
                SRERole old = null;
                try {
                    SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                    old = game == null ? null : game.getRole(player);
                } catch (Throwable ignored) {
                }
                BlackoutRoleManager.prepareReassignRole(level, id, role, null);
                pendingEffects.put(id, new PendingEffects(old, role, recordTimeline, addStats));
            }
        }

        @Override
        public void initNew(ServerPlayer player, RoleKey roleKey) {
            if (roleKey == null) {
                return;
            }
            // Clear any stale managed state left from a previous life of this role.
            stateResetter.reset(player, roleKey, ResetCause.ROLE_ASSIGNED);
        }

        @Override
        public void commitOld(ServerPlayer player, RoleKey oldRoleKey) {
            if (oldRoleKey == null) {
                return;
            }
            lostNotifier.accept(oldRoleKey, player);
            stateResetter.reset(player, oldRoleKey, ResetCause.ROLE_LOST);
        }

        @Override
        public void writeHistory(ServerPlayer player, RoleKey roleKey, RoleChangeCause cause) {
            RoleChangeServiceImpl.this.recordTimeline(player, roleKey, cause);
        }

        @Override
        public void afterAssigned(ServerPlayer player, SRERole role) {
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            PendingEffects effects = pendingEffects.remove(player.getUUID());
            if (effects == null) {
                return;
            }
            BlackoutRoleManager.finishReassignRole(level, player.getUUID(), effects.oldRole(),
                    effects.newRole(), effects.recordTimeline(), effects.addStats());
        }

        @Override
        public void syncClient(ServerPlayer player) {
            // gameWorld.syncRoles() inside reassignRole pushes client-visible role state.
        }

        @Override
        public void rollback(ServerPlayer player, RoleChangeTransaction.Captured captured,
                             @Nullable SRERole attemptedRole) {
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            UUID id = player.getUUID();
            pendingEffects.remove(id);
            try {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                if (captured.hadRole()) {
                    if (game != null) {
                        game.addRole(id, captured.sreRole(), false);
                        game.syncRoles();
                    }
                    if (captured.hadFaction()) {
                        try {
                            BlackoutRoleManager.assignRole(level, id, captured.sreRole().getIdentifier(),
                                    BlackoutRoleManager.Faction.valueOf(captured.faction()));
                        } catch (Throwable ignored) {}
                    }
                } else {
                    sreClearer.accept(player, null);
                    try {
                        BlackoutRoleManager.eliminate(level, id);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                LOGGER.warn("role change rollback failed for {}", id, t);
            }
            if (attemptedRole != null && attemptedRole.getIdentifier() != null) {
                try {
                    stateResetter.reset(player, RoleKey.of(attemptedRole.getIdentifier()), ResetCause.ROLE_ASSIGNED);
                } catch (Throwable ignored) {}
            }
        }
    }

    private record PendingEffects(@Nullable SRERole oldRole, SRERole newRole,
                                  boolean recordTimeline, boolean addStats) {
    }
}
