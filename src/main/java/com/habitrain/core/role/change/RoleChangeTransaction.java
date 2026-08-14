package com.habitrain.core.role.change;

import com.habitrain.core.api.role.v2.RoleChangeCause;
import com.habitrain.core.api.role.v2.RoleChangeResult;
import com.habitrain.core.api.role.v2.RoleKey;
import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Transactional role change (fix-doc §11). Orchestrates the twelve stages and,
 * when a mutation stage fails, restores the pre-change SRE role, mode faction
 * and managed state through {@link Backend#rollback}.
 *
 * <p>Generic over the actor type {@code A} ({@code ServerPlayer} in production)
 * so unit tests drive the full stage sequence and rollback with a lightweight
 * fake actor and a fake backend (fix-doc §11.4). The world check is also
 * injectable — production requires a {@code ServerLevel}, tests can skip it.
 *
 * <p>Same-role requests are a no-op success: no lost/assigned hooks, no
 * duplicated items or stats, unless the caller opts into a reinitialize
 * (fix-doc §11.3).
 *
 * @param <A> the actor (player) type the backend mutates
 */
public final class RoleChangeTransaction<A> {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleChangeTx");

    /** The fix-doc §11.1 stages (SNAPSHOT lookup folded into VALIDATE). */
    public enum Stage {
        CANONICALIZE,       // resolve the requested role to a canonical SRERole
        VALIDATE,           // mode/map/roster/eligibility + idempotency
        CAPTURE,            // snapshot old SRE role / faction / managed state
        BEFORE_LOST,        // prep-only hooks; nothing irreversible
        UPDATE_SRE,         // write the new role to the SRE map
        UPDATE_MODE,        // update mode (Blackout) faction/role
        INIT_NEW,           // initialize new-role CCA, state, skills, items
        COMMIT_OLD,         // finalize old-role cleanup (onLost + state reset)
        WRITE_HISTORY,      // write timeline + stats
        AFTER_ASSIGNED,     // fire afterAssigned + compatibility events
        SYNC_CLIENT         // push client-visible state
    }

    /** Game-coupled mutations; production wires the live managers. */
    public interface Backend<A> {
        /** Snapshot of the pre-change role state used for rollback. */
        Captured capture(A actor);

        /** Writes a new role (or clears for a removal) to the SRE map. */
        void updateSre(A actor, @Nullable SRERole role);

        /**
         * Updates reversible mode (Blackout) role/faction data to match
         * {@code role}. It must not emit events, update replay/stats, or sync
         * clients; those belong to {@link #afterAssigned}/{@link #syncClient}
         * after the transaction is committed.
         */
        void updateMode(A actor, @Nullable SRERole role,
                        boolean recordTimeline, boolean addStats);

        /** Initializes the newly-assigned role (CCA / state / skills / items). */
        void initNew(A actor, RoleKey roleKey);

        /** Finalizes the departing role (onLost hook + ROLE_LOST state reset). */
        void commitOld(A actor, RoleKey oldRoleKey);

        /** Writes the role-history timeline entry (only after all mutations succeeded). */
        void writeHistory(A actor, RoleKey roleKey, RoleChangeCause cause);

        /** Fires post-assignment hooks / compatibility events after commit. */
        void afterAssigned(A actor, SRERole role);

        /** Pushes client-visible sync. */
        void syncClient(A actor);

        /** Restores the pre-change role/faction/state after a failed mutation. */
        void rollback(A actor, Captured captured, @Nullable SRERole attemptedRole);
    }

    /** Pre-change snapshot for rollback. */
    public record Captured(@Nullable SRERole sreRole, @Nullable String faction) {

        public static Captured none() {
            return new Captured(null, null);
        }

        public boolean hadRole() {
            return sreRole != null;
        }

        public boolean hadFaction() {
            return faction != null;
        }
    }

    public record Result(boolean success, @Nullable RoleKey role, RoleChangeCause cause,
                         @Nullable Stage failedStage, @Nullable String message) {

        public static Result ok(RoleKey role, RoleChangeCause cause) {
            return new Result(true, role, cause, null, null);
        }

        public static Result fail(Stage stage, String message, RoleChangeCause cause) {
            return new Result(false, null, cause, stage, message);
        }

        public RoleChangeResult toPublic() {
            if (success) {
                return RoleChangeResult.success(role, cause);
            }
            return RoleChangeResult.failure(message, cause,
                    failedStage == null ? null : failedStage.name());
        }
    }

    private final Function<RoleKey, SRERole> resolver;
    private final Function<A, RoleKey> currentRoleLookup;
    private final BiConsumer<RoleKey, A> beforeLost;

    private volatile Function<Stage, RuntimeException> faultInjector = stage -> null;
    private volatile Backend<A> backend;
    private volatile Function<A, Boolean> levelCheck = actor -> actor != null;

    public RoleChangeTransaction(Backend<A> backend,
                                 Function<RoleKey, SRERole> resolver,
                                 Function<A, RoleKey> currentRoleLookup,
                                 BiConsumer<RoleKey, A> beforeLost) {
        this.backend = backend == null ? noopBackend() : backend;
        this.resolver = resolver;
        this.currentRoleLookup = currentRoleLookup;
        this.beforeLost = beforeLost == null ? (k, p) -> { } : beforeLost;
    }

    /** Test seam: replace the mutation backend (fix-doc §11.4). */
    public void setBackend(Backend<A> backend) {
        this.backend = backend == null ? noopBackend() : backend;
    }

    /** Test seam: override the VALIDATE world check (null restores the lenient default). */
    public void setLevelCheck(Function<A, Boolean> check) {
        this.levelCheck = check == null ? actor -> actor != null : check;
    }

    /** Test seam: force a failure at one stage (fix-doc §11.4). */
    public void setFaultInjector(Function<Stage, RuntimeException> injector) {
        this.faultInjector = injector == null ? s -> null : injector;
    }

    /**
     * Assigns/transforms {@code actor} to {@code target}. {@code reinitialize}
     * re-runs the full chain even when the actor already holds the target role.
     */
    public Result assign(A actor, RoleKey target, RoleChangeCause cause,
                         boolean reinitialize, boolean recordTimeline, boolean addStats) {
        // 1. CANONICALIZE
        if (actor == null || target == null) {
            return Result.fail(Stage.CANONICALIZE, "player and role are required", cause);
        }
        SRERole newRole = resolver.apply(target);
        if (newRole == null) {
            return Result.fail(Stage.CANONICALIZE, "role not found: " + target, cause);
        }
        // A resolver may accept a legacy alias. From this point on the
        // transaction must use the role object's canonical identity for all
        // comparisons, state resets, history and client-visible results.
        RoleKey canonical = newRole.identifier() == null ? target : RoleKey.of(newRole.identifier());
        // 2. VALIDATE
        if (!Boolean.TRUE.equals(levelCheck.apply(actor))) {
            return Result.fail(Stage.VALIDATE, "player is not on a ServerLevel", cause);
        }
        RoleKey current = currentRoleLookup.apply(actor);
        if (!reinitialize && current != null && current.equals(canonical)) {
            return Result.ok(canonical, cause); // same-role no-op success
        }
        Stage last = Stage.CAPTURE;
        Captured captured = Captured.none();
        try {
            last = Stage.CAPTURE;
            throwIf(last);
            captured = backend.capture(actor);
            last = Stage.BEFORE_LOST;
            throwIf(last);
            beforeLost.accept(current, actor);
            last = Stage.UPDATE_SRE;
            throwIf(last);
            backend.updateSre(actor, newRole);
            last = Stage.UPDATE_MODE;
            throwIf(last);
            backend.updateMode(actor, newRole, recordTimeline, addStats);
            last = Stage.INIT_NEW;
            throwIf(last);
            backend.initNew(actor, canonical);
            // All remaining stages are externally visible (events, history,
            // stats/replay and network sync). Preflight their fault-injection
            // seam before running even one of them, so an injected late-stage
            // failure cannot leave an irreversible partial transition behind.
            last = Stage.COMMIT_OLD;
            throwIf(last);
            last = Stage.WRITE_HISTORY;
            throwIf(last);
            last = Stage.AFTER_ASSIGNED;
            throwIf(last);
            last = Stage.SYNC_CLIENT;
            throwIf(last);

            // Once the reversible SRE/mode mutation succeeded, post-commit
            // notifications must not turn a completed role change into a
            // reported failure. Providers are isolated by the backend; any
            // unexpected listener/sync exception is logged and the canonical
            // committed state remains authoritative.
            if (current != null) {
                postCommit(Stage.COMMIT_OLD, () -> backend.commitOld(actor, current));
            }
            if (recordTimeline) {
                postCommit(Stage.WRITE_HISTORY, () -> backend.writeHistory(actor, canonical, cause));
            }
            postCommit(Stage.AFTER_ASSIGNED, () -> backend.afterAssigned(actor, newRole));
            postCommit(Stage.SYNC_CLIENT, () -> backend.syncClient(actor));
            return Result.ok(canonical, cause);
        } catch (RuntimeException failure) {
            LOGGER.warn("role change {} -> {} failed at {}", actor, canonical, last, failure);
            rollbackIfMutated(actor, captured, newRole, last);
            return Result.fail(last, failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage(), cause);
        }
    }

    /** Removes an actor's role. Rollback restores the captured role on failure. */
    public Result remove(A actor, RoleChangeCause cause) {
        if (actor == null) {
            return Result.fail(Stage.CANONICALIZE, "player is required", cause);
        }
        if (!Boolean.TRUE.equals(levelCheck.apply(actor))) {
            return Result.fail(Stage.VALIDATE, "player is not on a ServerLevel", cause);
        }
        RoleKey current = currentRoleLookup.apply(actor);
        Stage last = Stage.CAPTURE;
        Captured captured = Captured.none();
        try {
            last = Stage.CAPTURE;
            throwIf(last);
            captured = backend.capture(actor);
            last = Stage.BEFORE_LOST;
            throwIf(last);
            beforeLost.accept(current, actor);
            last = Stage.UPDATE_SRE;
            throwIf(last);
            backend.updateSre(actor, null);
            last = Stage.UPDATE_MODE;
            throwIf(last);
            backend.updateMode(actor, null, false, false);
            // See assign(): test faults for every post-commit stage are
            // checked before irreversible work begins.
            last = Stage.COMMIT_OLD;
            throwIf(last);
            last = Stage.WRITE_HISTORY;
            throwIf(last);
            last = Stage.SYNC_CLIENT;
            throwIf(last);

            if (current != null) {
                postCommit(Stage.COMMIT_OLD, () -> backend.commitOld(actor, current));
                postCommit(Stage.WRITE_HISTORY, () -> backend.writeHistory(actor, current, cause));
            }
            postCommit(Stage.SYNC_CLIENT, () -> backend.syncClient(actor));
            return Result.ok(null, cause);
        } catch (RuntimeException failure) {
            LOGGER.warn("role remove {} failed at {}", actor, last, failure);
            rollbackIfMutated(actor, captured, null, last);
            return Result.fail(last, failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage(), cause);
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void throwIf(Stage stage) {
        RuntimeException t = faultInjector.apply(stage);
        if (t != null) {
            throw t;
        }
    }

    /** Rolls back only when a mutation stage actually started (UPDATE_SRE onward). */
    private void rollbackIfMutated(A actor, Captured captured, @Nullable SRERole attempted, Stage last) {
        if (last == Stage.CAPTURE || last == Stage.BEFORE_LOST) {
            return; // nothing was mutated
        }
        try {
            backend.rollback(actor, captured, attempted);
        } catch (Throwable rb) {
            LOGGER.error("role change rollback failed for {}", actor, rb);
        }
    }

    /** Runs an irreversible observer after the transition is committed. */
    private void postCommit(Stage stage, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable t) {
            LOGGER.warn("role change post-commit {} failed; committed role state is retained", stage, t);
        }
    }

    private static <A> Backend<A> noopBackend() {
        return new Backend<A>() {
            @Override
            public Captured capture(A actor) {
                return Captured.none();
            }

            @Override
            public void updateSre(A actor, @Nullable SRERole role) { }

            @Override
            public void updateMode(A actor, @Nullable SRERole role,
                                   boolean recordTimeline, boolean addStats) { }

            @Override
            public void initNew(A actor, RoleKey roleKey) { }

            @Override
            public void commitOld(A actor, RoleKey oldRoleKey) { }

            @Override
            public void writeHistory(A actor, RoleKey roleKey, RoleChangeCause cause) { }

            @Override
            public void afterAssigned(A actor, SRERole role) { }

            @Override
            public void syncClient(A actor) { }

            @Override
            public void rollback(A actor, Captured captured, @Nullable SRERole attemptedRole) { }
        };
    }
}
