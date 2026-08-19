package com.habitrain.core.api.role.v2;

import com.habitrain.core.role.change.RoleChangeServiceImpl;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 {@link RoleChangeApi} public types and the transaction failure
 * paths of {@link RoleChangeServiceImpl} that do not require a launched game.
 */
class RoleChangeApiTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");

    // ------------------------------------------------------------------
    // RoleChangeResult
    // ------------------------------------------------------------------

    @Test
    void successResultCarriesRoleAndCause() {
        RoleChangeResult r = RoleChangeResult.success(ROLE, RoleChangeCause.CONVERSION);
        assertTrue(r.success());
        assertNull(r.message());
        assertEquals(ROLE, r.role());
        assertEquals(RoleChangeCause.CONVERSION, r.cause());
    }

    @Test
    void failureResultCarriesMessage() {
        RoleChangeResult r = RoleChangeResult.failure("boom", RoleChangeCause.REMOVE);
        assertFalse(r.success());
        assertEquals("boom", r.message());
        assertNull(r.role());
        assertEquals(RoleChangeCause.REMOVE, r.cause());
    }

    // ------------------------------------------------------------------
    // RoleChangeOptions
    // ------------------------------------------------------------------

    @Test
    void defaultOptionsRecordTimelineAndStats() {
        RoleChangeOptions o = RoleChangeOptions.defaults();
        assertTrue(o.recordTimeline());
        assertTrue(o.addStats());
    }

    @Test
    void silentOptionsSkipBoth() {
        RoleChangeOptions o = RoleChangeOptions.silent();
        assertFalse(o.recordTimeline());
        assertFalse(o.addStats());
    }

    // ------------------------------------------------------------------
    // RoleChangeCause
    // ------------------------------------------------------------------

    @Test
    void causesCoverExpectedTransitions() {
        assertEquals(7, RoleChangeCause.values().length);
        assertTrue(RoleChangeCause.valueOf("ASSIGN") != null);
        assertTrue(RoleChangeCause.valueOf("CONVERSION") != null);
        assertTrue(RoleChangeCause.valueOf("FORCED_RANDOM") != null);
        assertTrue(RoleChangeCause.valueOf("SHERIFF_ELECTION") != null);
        assertTrue(RoleChangeCause.valueOf("REVIVE") != null);
        assertTrue(RoleChangeCause.valueOf("REMOVE") != null);
        assertTrue(RoleChangeCause.valueOf("OTHER") != null);
    }

    // ------------------------------------------------------------------
    // Service failure paths (no game required)
    // ------------------------------------------------------------------

    @Test
    void transformRejectsNullPlayer() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        RoleChangeResult r = svc.transform(null, ROLE, RoleChangeCause.CONVERSION);
        assertFalse(r.success());
        assertEquals("player and role are required", r.message());
    }

    @Test
    void transformRejectsNullRole() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        RoleChangeResult r = svc.transform(null, null, RoleChangeCause.CONVERSION);
        assertFalse(r.success());
    }

    @Test
    void historyEntryKeepsSnapshotAndDisplay() {
        RoleSnapshotId snap = new RoleSnapshotId(3);
        RoleHistoryEntry e = new RoleHistoryEntry(ROLE, RoleChangeCause.ASSIGN, 10L, snap, "替罪羊", "habitrain_core");
        assertEquals(ROLE, e.role());
        assertEquals(snap, e.snapshot());
        assertEquals("替罪羊", e.displayName());
        assertEquals("habitrain_core", e.provider());
        RoleHistoryEntry compat = new RoleHistoryEntry(ROLE, RoleChangeCause.OTHER, 0L);
        assertNull(compat.snapshot());
    }

    @Test
    void assignRejectsNullPlayer() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        RoleChangeResult r = svc.assign(null, ROLE, RoleChangeOptions.defaults());
        assertFalse(r.success());
    }

    @Test
    void removeRejectsNullPlayer() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        RoleChangeResult r = svc.remove(null, RoleChangeCause.REMOVE);
        assertFalse(r.success());
    }

    // ------------------------------------------------------------------
    // Resolver binding
    // ------------------------------------------------------------------

    @Test
    void resolverIsUsedForResolution() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        SRERole role = new NormalRole(ROLE.location(), 0xFF0000, true, false,
                SRERole.MoodType.REAL, 20, false);
        svc.setResolver(key -> key.equals(ROLE) ? role : null);

        assertEquals(role, svc.resolve(ROLE));
        assertNull(svc.resolve(RoleKey.of("othermod", "missing")));
    }

    @Test
    void defaultResolverReturnsNull() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        assertNull(svc.resolve(ROLE), "default resolver must be a safe no-op outside a game");
    }

    @Test
    void notifyLostInvokesHookForPreviousRole() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        AtomicReference<RoleKey> lost = new AtomicReference<>();
        svc.setLostNotifier((key, player) -> lost.set(key));
        RoleKey previous = RoleKey.of("habitrain_core", "old");
        svc.notifyLost(null, previous);
        assertEquals(previous, lost.get());
    }

    @Test
    void notifyLostIfChangedSkipsWhenRoleUnchanged() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        AtomicReference<RoleKey> lost = new AtomicReference<>();
        svc.setLostNotifier((key, player) -> lost.set(key));
        svc.setCurrentRoleLookup(player -> ROLE);
        svc.notifyLostIfChanged(null, ROLE);
        assertNull(lost.get(), "same role must not fire onLost");
    }

    @Test
    void notifyLostIfChangedFiresWhenRoleChanges() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        AtomicReference<RoleKey> lost = new AtomicReference<>();
        svc.setLostNotifier((key, player) -> lost.set(key));
        RoleKey previous = RoleKey.of("habitrain_core", "old");
        svc.setCurrentRoleLookup(player -> previous);
        svc.notifyLostIfChanged(null, ROLE);
        assertEquals(previous, lost.get());
    }

    @Test
    void recordTimelineKeepsOrderedEntries() {
        RoleChangeServiceImpl svc = new RoleChangeServiceImpl();
        java.util.UUID id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        RoleKey first = RoleKey.of("habitrain_core", "old");
        svc.recordTimeline(id, first, RoleChangeCause.ASSIGN);
        svc.recordTimeline(id, ROLE, RoleChangeCause.CONVERSION);
        var history = svc.recordedTimeline(id);
        assertEquals(2, history.size());
        assertEquals(first, history.get(0).role());
        assertEquals(RoleChangeCause.ASSIGN, history.get(0).cause());
        assertEquals(ROLE, history.get(1).role());
        assertEquals(RoleChangeCause.CONVERSION, history.get(1).cause());
        assertTrue(history.get(1).timestamp() >= history.get(0).timestamp());
    }
}
