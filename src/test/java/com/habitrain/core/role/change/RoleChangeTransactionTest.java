package com.habitrain.core.role.change;

import com.habitrain.core.api.role.v2.RoleChangeCause;
import com.habitrain.core.api.role.v2.RoleKey;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E role-change transaction tests (fix-doc §18.6): normal stage chain,
 * same-role no-op, reinitialize, rollback on every mutation-stage failure, no
 * history on rollback, and removal rollback. The transaction is generic over
 * the actor type, so a plain {@link Actor} record drives the logic — no game
 * is launched.
 */
class RoleChangeTransactionTest {

    /** Minimal actor substitute for unit tests. */
    record Actor(UUID id) {
        static Actor of(String uuid) {
            return new Actor(UUID.fromString(uuid));
        }
    }

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final RoleKey OLD = RoleKey.of("habitrain_core", "old_role");
    private static final RoleKey MISSING = RoleKey.of("habitrain_core", "missing");
    private static final SRERole NEW_ROLE =
            new NormalRole(ROLE.location(), 0xFF0000, true, false, SRERole.MoodType.REAL, 20, false);
    private static final SRERole OLD_ROLE =
            new NormalRole(OLD.location(), 0x00FF00, false, false, SRERole.MoodType.REAL, 20, false);

    private FakeBackend backend;
    private RoleChangeTransaction<Actor> tx;
    private Actor fake;

    @BeforeEach
    void setUp() {
        backend = new FakeBackend();
        tx = new RoleChangeTransaction<>(
                backend,
                key -> key.equals(ROLE) ? NEW_ROLE : null,
                player -> OLD,
                (key, player) -> { });
        fake = Actor.of("00000000-0000-0000-0000-000000000001");
    }

    // ------------------------------------------------------------------
    // Happy path / idempotency (§11.1 / §11.3)
    // ------------------------------------------------------------------

    @Test
    void normalChainRunsEveryStageInOrder() {
        RoleChangeTransaction.Result r = tx.assign(fake, ROLE, RoleChangeCause.CONVERSION, false, true, true);
        assertTrue(r.success());
        assertEquals(List.of(
                        RoleChangeTransaction.Stage.UPDATE_SRE,
                        RoleChangeTransaction.Stage.UPDATE_MODE,
                        RoleChangeTransaction.Stage.INIT_NEW,
                        RoleChangeTransaction.Stage.COMMIT_OLD,
                        RoleChangeTransaction.Stage.WRITE_HISTORY,
                        RoleChangeTransaction.Stage.AFTER_ASSIGNED,
                        RoleChangeTransaction.Stage.SYNC_CLIENT),
                backend.mutatedStages,
                "every mutation stage must run, in fix-doc §11.1 order");
    }

    @Test
    void sameRoleIsNoOpWithoutTouchingBackend() {
        RoleChangeTransaction<Actor> txSame = new RoleChangeTransaction<>(
                backend, key -> NEW_ROLE, player -> ROLE, (key, player) -> { });
        RoleChangeTransaction.Result r = txSame.assign(fake, ROLE, RoleChangeCause.ASSIGN, false, true, true);
        assertTrue(r.success());
        assertTrue(backend.mutatedStages.isEmpty(), "same-role no-op must not fire any mutation");
    }

    @Test
    void reinitializeRunsChainOnSameRole() {
        RoleChangeTransaction<Actor> txSame = new RoleChangeTransaction<>(
                backend, key -> NEW_ROLE, player -> ROLE, (key, player) -> { });
        RoleChangeTransaction.Result r = txSame.assign(fake, ROLE, RoleChangeCause.ASSIGN, true, true, true);
        assertTrue(r.success());
        assertFalse(backend.mutatedStages.isEmpty(), "reinitialize must re-run the full chain");
    }

    @Test
    void missingRoleFailsCanonicalize() {
        RoleChangeTransaction.Result r = tx.assign(fake, MISSING, RoleChangeCause.ASSIGN, false, true, true);
        assertFalse(r.success());
        assertEquals(RoleChangeTransaction.Stage.CANONICALIZE, r.failedStage());
        assertEquals("role not found: " + MISSING, r.message());
    }

    // ------------------------------------------------------------------
    // Rollback (§11.2)
    // ------------------------------------------------------------------

    @Test
    void failureAtUpdateModeRollsBack() {
        tx.setFaultInjector(stage -> stage == RoleChangeTransaction.Stage.UPDATE_MODE
                ? new IllegalStateException("boom") : null);
        RoleChangeTransaction.Result r = tx.assign(fake, ROLE, RoleChangeCause.CONVERSION, false, true, true);
        assertFalse(r.success());
        assertEquals(RoleChangeTransaction.Stage.UPDATE_MODE, r.failedStage());
        assertTrue(backend.rolledBack, "a failed mutation must roll back");
        assertEquals("boom", r.toPublic().message());
        assertEquals("UPDATE_MODE", r.toPublic().phase(), "the failing stage is surfaced on the public result");
    }

    @Test
    void failureBeforeAnyMutationDoesNotRollBack() {
        tx.setFaultInjector(stage -> stage == RoleChangeTransaction.Stage.BEFORE_LOST
                ? new IllegalStateException("prep") : null);
        RoleChangeTransaction.Result r = tx.assign(fake, ROLE, RoleChangeCause.CONVERSION, false, true, true);
        assertFalse(r.success());
        assertFalse(backend.rolledBack, "a pre-mutation failure must not roll back");
    }

    @Test
    void failureAtWriteHistoryRollsBackAndWritesNoHistory() {
        tx.setFaultInjector(stage -> stage == RoleChangeTransaction.Stage.WRITE_HISTORY
                ? new IllegalStateException("history") : null);
        RoleChangeTransaction.Result r = tx.assign(fake, ROLE, RoleChangeCause.CONVERSION, false, true, true);
        assertFalse(r.success());
        assertTrue(backend.rolledBack);
        assertFalse(backend.mutatedStages.contains(RoleChangeTransaction.Stage.WRITE_HISTORY),
                "history must only be written after all mutations succeed");
    }

    @Test
    void failureAtAfterAssignedRollsBack() {
        tx.setFaultInjector(stage -> stage == RoleChangeTransaction.Stage.AFTER_ASSIGNED
                ? new IllegalStateException("events") : null);
        RoleChangeTransaction.Result r = tx.assign(fake, ROLE, RoleChangeCause.CONVERSION, false, true, true);
        assertFalse(r.success());
        assertTrue(backend.rolledBack);
    }

    @Test
    void rollbackReceivesCapturedOldRole() {
        tx.setFaultInjector(stage -> stage == RoleChangeTransaction.Stage.INIT_NEW
                ? new IllegalStateException("init") : null);
        tx.assign(fake, ROLE, RoleChangeCause.CONVERSION, false, true, true);
        assertEquals(OLD_ROLE, backend.rollbackCaptured.sreRole(), "rollback must restore the captured old role");
        assertEquals("GOOD", backend.rollbackCaptured.faction());
    }

    // ------------------------------------------------------------------
    // Removal (§11.1/§11.2)
    // ------------------------------------------------------------------

    @Test
    void removeRunsChainAndSucceeds() {
        RoleChangeTransaction.Result r = tx.remove(fake, RoleChangeCause.REMOVE);
        assertTrue(r.success());
        assertNull(r.role());
        assertEquals(List.of(
                        RoleChangeTransaction.Stage.UPDATE_SRE,
                        RoleChangeTransaction.Stage.UPDATE_MODE,
                        RoleChangeTransaction.Stage.COMMIT_OLD,
                        RoleChangeTransaction.Stage.WRITE_HISTORY,
                        RoleChangeTransaction.Stage.SYNC_CLIENT),
                backend.mutatedStages);
    }

    @Test
    void removeFailureRollsBack() {
        tx.setFaultInjector(stage -> stage == RoleChangeTransaction.Stage.UPDATE_MODE
                ? new IllegalStateException("mode") : null);
        RoleChangeTransaction.Result r = tx.remove(fake, RoleChangeCause.REMOVE);
        assertFalse(r.success());
        assertTrue(backend.rolledBack);
    }

    @Test
    void removeNullPlayerFailsCanonicalize() {
        RoleChangeTransaction.Result r = tx.remove(null, RoleChangeCause.REMOVE);
        assertFalse(r.success());
        assertEquals(RoleChangeTransaction.Stage.CANONICALIZE, r.failedStage());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Records which mutation stages ran and whether rollback was invoked. */
    static class FakeBackend implements RoleChangeTransaction.Backend<Actor> {

        final List<RoleChangeTransaction.Stage> mutatedStages = new ArrayList<>();
        boolean rolledBack;
        RoleChangeTransaction.Captured rollbackCaptured = RoleChangeTransaction.Captured.none();
        RoleChangeTransaction.Captured captured = new RoleChangeTransaction.Captured(OLD_ROLE, "GOOD");

        @Override
        public RoleChangeTransaction.Captured capture(Actor actor) {
            return captured;
        }

        @Override
        public void updateSre(Actor actor, SRERole role) {
            mutatedStages.add(RoleChangeTransaction.Stage.UPDATE_SRE);
        }

        @Override
        public void updateMode(Actor actor, SRERole role, boolean recordTimeline, boolean addStats) {
            mutatedStages.add(RoleChangeTransaction.Stage.UPDATE_MODE);
        }

        @Override
        public void initNew(Actor actor, RoleKey roleKey) {
            mutatedStages.add(RoleChangeTransaction.Stage.INIT_NEW);
        }

        @Override
        public void commitOld(Actor actor, RoleKey oldRoleKey) {
            mutatedStages.add(RoleChangeTransaction.Stage.COMMIT_OLD);
        }

        @Override
        public void writeHistory(Actor actor, RoleKey roleKey, RoleChangeCause cause) {
            mutatedStages.add(RoleChangeTransaction.Stage.WRITE_HISTORY);
        }

        @Override
        public void afterAssigned(Actor actor, SRERole role) {
            mutatedStages.add(RoleChangeTransaction.Stage.AFTER_ASSIGNED);
        }

        @Override
        public void syncClient(Actor actor) {
            mutatedStages.add(RoleChangeTransaction.Stage.SYNC_CLIENT);
        }

        @Override
        public void rollback(Actor actor, RoleChangeTransaction.Captured captured, SRERole attemptedRole) {
            rolledBack = true;
            rollbackCaptured = captured;
        }
    }
}
