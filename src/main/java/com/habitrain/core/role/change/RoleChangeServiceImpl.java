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
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
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
 * Default {@link RoleChangeApi} implementation, using SRE role state and the v2 catalog for
 * canonical role resolution.
 *
 * <p>Every change is a {@link RoleChangeTransaction}: it resolves the target,
 * captures the old SRE role, mutates, commits the old-role cleanup,
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
    /** Per-player timeline cap; without it timelines grow unboundedly with
     *  player count × role changes over a long-running server (review M3). */
    private static final int MAX_TIMELINE_ENTRIES = 64;
    private volatile StateResetHook stateResetter = RoleChangeServiceImpl::resetState;
    /** Per-transaction live data needed only after the reversible commit succeeds. */
    private final Map<UUID, PendingEffects> pendingEffects = new ConcurrentHashMap<>();
    /** Reentrancy guard per player UUID (ROLE-RT-01). */
    private final java.util.Set<UUID> inFlightPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, java.util.Set<RoleKey>> pendingCooldownClears = new ConcurrentHashMap<>();

    static {
        com.habitrain.core.task.ClearableHandlerRegistry.register(() -> {
            try {
                if (RoleChangeApi.instance() instanceof RoleChangeServiceImpl impl) {
                    impl.clearSessionState();
                }
            } catch (Throwable failure) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(failure);
                LOGGER.error("Failed to clear role-change session state", failure);
            }
        });
    }

    public void clearSessionState() {
        timelines.clear();
        pendingEffects.clear();
        inFlightPlayers.clear();
    }

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
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
            LOGGER.warn("role-state reset {} for {} failed", cause, role, t);
        }
    }

    /** Test-visible timeline for one player. */
    public List<RoleHistoryEntry> recordedTimeline(UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        List<RoleHistoryEntry> list = timelines.get(playerId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
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
                    } catch (Throwable e) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(e);
                        LOGGER.debug("Failed to resolve role display name for {}", role, e);
                    }
                    if (er.role().identifier() != null) {
                        provider = er.role().identifier().getNamespace();
                    }
                }
            }
        } catch (Throwable e) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(e);
            LOGGER.debug("Failed to read snapshot metadata for timeline {}", role, e);
        }
        // try 块内被重新赋值的变量不是 effectively final，lambda 捕获前先拷贝一份。
        final RoleSnapshotId snapRef = snap;
        final String displayRef = display;
        final String providerRef = provider;
        timelines.compute(playerId, (id, list) -> {
            List<RoleHistoryEntry> entries = list == null ? new ArrayList<>() : list;
            synchronized (entries) {
                entries.add(new RoleHistoryEntry(role, cause, System.currentTimeMillis(), snapRef, displayRef, providerRef));
                if (entries.size() > MAX_TIMELINE_ENTRIES) {
                    entries.subList(0, entries.size() - MAX_TIMELINE_ENTRIES).clear();
                }
            }
            return entries;
        });
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
        clearActionCooldowns(player, previous);
    }

    private void clearActionCooldowns(@Nullable ServerPlayer player, @Nullable RoleKey role) {
        if (player == null) {
            return;
        }
        if (inFlightPlayers.contains(player.getUUID())) {
            if (role != null) pendingCooldownClears.computeIfAbsent(player.getUUID(), id -> new java.util.LinkedHashSet<>()).add(role);
            return;
        }
        try {
            if (com.habitrain.core.api.role.v2.action.RoleActionApi.instance()
                    instanceof com.habitrain.core.role.action.RoleActionServiceImpl actionSvc) {
                actionSvc.clearCooldownsForRole(player.getUUID(), role);
            }
        } catch (Throwable e) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(e);
            LOGGER.debug("Failed to clear action cooldowns for {}", role, e);
        }
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
        if (options == null) {
            return new RoleChangeResult(false, "options are required", canonical, RoleChangeCause.ASSIGN, "VALIDATE");
        }
        UUID playerId = player == null ? null : player.getUUID();
        if (playerId != null && !inFlightPlayers.add(playerId)) {
            LOGGER.warn("Rejected reentrant role assign for player {} -> {}", playerId, canonical);
            return RoleChangeResult.failure("reentrant role change is not allowed", RoleChangeCause.ASSIGN, "VALIDATE");
        }
        boolean committed = false;
        try {
            RoleChangeTransaction.Result r = transaction.assign(
                    player, canonical, RoleChangeCause.ASSIGN,
                    options.reinitialize(), options.recordTimeline(), options.addStats());
            com.habitrain.core.role.behavior.RuntimeHookGates.INSTANCE.invalidate();
            committed = r.success();
            return r.toPublic();
        } finally {
            if (playerId != null) {
                finishTransition(playerId, committed);
            }
        }
    }

    @Override
    public RoleChangeResult transform(ServerPlayer player, RoleKey role, RoleChangeCause cause) {
        return transform(player, role, cause, RoleChangeOptions.defaults());
    }

    @Override
    public RoleChangeResult transform(ServerPlayer player, RoleKey role, RoleChangeCause cause,
                                      RoleChangeOptions options) {
        RoleKey canonical = canonicalize(role);
        if (options == null) {
            return new RoleChangeResult(false, "options are required", canonical, cause, "VALIDATE");
        }
        if (cause == RoleChangeCause.FORCED_RANDOM && player != null && canonical != null) {
            ForcedRandomRoleChangePolicy.Assessment assessment =
                    ForcedRandomRoleChangePolicy.assess(player);
            if (!assessment.allowed()) {
                ForcedRandomRoleChangePolicy.logDenial("RoleChangeApi", player, assessment);
                return new RoleChangeResult(false, assessment.diagnosticMessage(), canonical, cause,
                        "SAFETY_CHECK");
            }
        }
        UUID playerId = player == null ? null : player.getUUID();
        if (playerId != null && !inFlightPlayers.add(playerId)) {
            LOGGER.warn("Rejected reentrant role transform for player {} -> {}", playerId, canonical);
            return RoleChangeResult.failure("reentrant role change is not allowed", cause, "VALIDATE");
        }
        boolean committed = false;
        try {
            RoleChangeTransaction.Result r = transaction.assign(
                    player, canonical, cause, options.reinitialize(),
                    options.recordTimeline(), options.addStats());
            com.habitrain.core.role.behavior.RuntimeHookGates.INSTANCE.invalidate();
            committed = r.success();
            return r.toPublic();
        } finally {
            if (playerId != null) {
                finishTransition(playerId, committed);
            }
        }
    }

    @Override
    public RoleChangeResult remove(ServerPlayer player, RoleChangeCause cause) {
        UUID playerId = player == null ? null : player.getUUID();
        if (playerId != null && !inFlightPlayers.add(playerId)) {
            LOGGER.warn("Rejected reentrant role remove for player {}", playerId);
            return RoleChangeResult.failure("reentrant role change is not allowed", cause, "VALIDATE");
        }
        boolean committed = false;
        try {
            RoleChangeResult res = transaction.remove(player, cause).toPublic();
            com.habitrain.core.role.behavior.RuntimeHookGates.INSTANCE.invalidate();
            committed = res.success();
            return res;
        } finally {
            if (playerId != null) {
                finishTransition(playerId, committed);
            }
        }
    }

    private void finishTransition(UUID playerId, boolean committed) {
        try {
            ((com.habitrain.core.role.state.RoleStateServiceImpl) RoleStateApi.instance())
                    .finishPlayerTransaction(playerId, committed);
            var roles = pendingCooldownClears.remove(playerId);
            if (committed && roles != null) {
                var actions = (com.habitrain.core.role.action.RoleActionServiceImpl)
                        com.habitrain.core.api.role.v2.action.RoleActionApi.instance();
                roles.forEach(role -> actions.clearCooldownsForRole(playerId, role));
            }
        } catch (Throwable failure) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(failure);
            LOGGER.error("Failed to finalize managed role state for {} (committed={})", playerId, committed, failure);
        } finally {
            pendingCooldownClears.remove(playerId);
            pendingEffects.remove(playerId);
            inFlightPlayers.remove(playerId);
        }
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
                    faction = deriveFactionName(r);
                }
            } catch (Throwable e) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(e);
                LOGGER.debug("current(player) role lookup failed for {}", id, e);
            }
        }
        return new RoleView(id, role, faction);
    }

    private static @Nullable String deriveFactionName(@Nullable SRERole r) {
        if (r == null) {
            return null;
        }
        for (com.habitrain.core.api.role.v2.RoleFaction f : com.habitrain.core.api.role.v2.RoleFaction.values()) {
            if (f.matches(r)) {
                return f.name();
            }
        }
        return null;
    }

    @Override
    public List<RoleHistoryEntry> history(ServerPlayer player) {
        if (player == null) {
            return List.of();
        }
        List<RoleHistoryEntry> recorded = timelines.get(player.getUUID());
        if (recorded != null) {
            synchronized (recorded) {
                if (!recorded.isEmpty()) {
                    return List.copyOf(recorded);
                }
            }
        }
        RoleView view = current(player);
        return view == null || view.role() == null ? List.of()
                : List.of(new RoleHistoryEntry(view.role(), RoleChangeCause.OTHER, 0));
    }

    private @Nullable RoleKey lookupCurrentRole(ServerPlayer player) {
        RoleView view = current(player);
        return view == null ? null : view.role();
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
        } catch (Throwable failure) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(failure);
            LOGGER.warn("Failed to canonicalize requested role {}", requested, failure);
            return requested;
        }
    }

    /**
     * Production {@link RoleChangeTransaction.Backend}: captures the current
     * SRE role, stages SRE role changes before publishing
     * assignment effects, and restores the captured role on rollback.
     */
    private final class ProductionBackend implements RoleChangeTransaction.Backend<ServerPlayer> {

        @Override
        public RoleChangeTransaction.Captured capture(ServerPlayer player) {
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return RoleChangeTransaction.Captured.none();
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null) throw new IllegalStateException("Cannot capture role: SRE world component is missing");
            SRERole old = game.getRole(player);
            String faction = null;
            ((com.habitrain.core.role.state.RoleStateServiceImpl) RoleStateApi.instance())
                    .beginPlayerTransaction(player.getUUID());
            return new RoleChangeTransaction.Captured(old, faction);
        }

        @Override
        public void updateSre(ServerPlayer player, @Nullable SRERole role) {
            if (role == null) {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.serverLevel());
                if (game == null) throw new IllegalStateException("Missing SRE game component");
                SRERole old = game.getRoles().remove(player.getUUID());
                pendingEffects.put(player.getUUID(), new PendingEffects(old, null, false, false));
            }
            // Assignments are staged in updateMode; effects publish only after commit.
        }

        @Override
        public void updateMode(ServerPlayer player, @Nullable SRERole role,
                               boolean recordTimeline, boolean addStats) {
            if (player == null || role == null) return;
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.serverLevel());
            if (game == null) throw new IllegalStateException("Missing SRE game component");
            SRERole old = game.getRole(player);
            game.addRole(player.getUUID(), role, false);
            pendingEffects.put(player.getUUID(), new PendingEffects(old, role, recordTimeline, addStats));
        }

        @Override
        public void initNew(ServerPlayer player, RoleKey roleKey) {
            if (roleKey == null) {
                return;
            }
            // Clear any stale managed state left from a previous life of this role.
            stateResetter.reset(player, roleKey, ResetCause.ROLE_ASSIGNED);
            clearActionCooldowns(player, roleKey);
        }

        @Override
        public void commitOld(ServerPlayer player, RoleKey oldRoleKey) {
            if (oldRoleKey == null) {
                return;
            }
            SpecialRoleExitCleanup.clear(player);
            lostNotifier.accept(oldRoleKey, player);
            stateResetter.reset(player, oldRoleKey, ResetCause.ROLE_LOST);
            clearActionCooldowns(player, oldRoleKey);
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
            com.habitrain.core.game.sre.role.SreRoleAssignmentEffects.finishReassignRole(level, player.getUUID(), effects.oldRole(),
                    effects.newRole(), effects.recordTimeline(), effects.addStats());
        }

        @Override
        public void syncClient(ServerPlayer player) {
            PendingEffects effects = pendingEffects.remove(player.getUUID());
            if (effects != null && effects.newRole() == null) {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.serverLevel());
                if (effects.oldRole() != null) {
                    try {
                        org.agmas.harpymodloader.events.ModdedRoleRemoved.EVENT.invoker()
                                .removeModdedRole(player, effects.oldRole());
                    } finally {
                        game.syncRoles();
                    }
                } else game.syncRoles();
            }
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
                } else {
                    if (game != null) game.getRoles().remove(id);
                }
            } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.warn("role change rollback failed for {}", id, t);
            }
        }
    }

    private record PendingEffects(@Nullable SRERole oldRole, @Nullable SRERole newRole,
                                  boolean recordTimeline, boolean addStats) {
    }
}
