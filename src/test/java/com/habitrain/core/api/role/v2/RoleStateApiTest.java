package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.state.Persistence;
import com.habitrain.core.api.role.v2.state.ResetCause;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import com.habitrain.core.api.role.v2.state.RoleStateKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.api.role.v2.state.StateScope;
import com.habitrain.core.api.role.v2.state.SyncPolicy;
import com.habitrain.core.role.change.RoleChangeServiceImpl;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import com.habitrain.core.role.state.RoleStateServiceImpl;
import com.mojang.serialization.Codec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 {@link RoleStateApi}: schema registration, in-memory get/set,
 * default values, ROLE_LOST / ROUND_END reset, freeze, and the
 * {@code /habitrain roleapi state} formatter. Uses a dedicated
 * {@link RoleStateServiceImpl} for store tests so the process singleton
 * stays clean; singleton tests reset via {@code clear(true)}.
 */
class RoleStateApiTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final RoleKey OTHER = RoleKey.of("habitrain_core", "other_role");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private RoleStateServiceImpl store;

    @BeforeEach
    void setUp() {
        store = new RoleStateServiceImpl();
        ((RoleStateServiceImpl) RoleStateApi.instance()).clear(true);
    }

    @AfterEach
    void tearDown() {
        ((RoleStateServiceImpl) RoleStateApi.instance()).clear(true);
    }

    @Test
    void builderRequiresRole() {
        assertThrows(IllegalStateException.class,
                () -> RoleStateSpec.of("habitrain_core", "souls", Integer.class).build());
        assertThrows(NullPointerException.class,
                () -> RoleStateSpec.of("habitrain_core", "souls", Integer.class).type(null));
    }

    @Test
    void playerScopeDefaultsToRoleLostReset() {
        RoleStateSpec<Integer> spec = RoleStateSpec.of("habitrain_core", "souls", Integer.class)
                .role(ROLE)
                .defaultValue(() -> 0)
                .build();
        assertEquals(StateScope.PLAYER, spec.scope());
        assertEquals(Persistence.ROUND, spec.persistence());
        assertEquals(SyncPolicy.NONE, spec.sync());
        assertTrue(spec.resetOn().contains(ResetCause.ROLE_LOST));
        assertTrue(spec.resetOn().contains(ResetCause.ROUND_END));
    }

    @Test
    void worldScopeWithoutExplicitResetStaysUntilManual() {
        RoleStateSpec<Integer> spec = RoleStateSpec.of("habitrain_core", "pool", Integer.class)
                .role(ROLE)
                .scope(StateScope.WORLD)
                .persistence(Persistence.WORLD)
                .codec(Codec.INT)
                .defaultValue(() -> 0)
                .build();
        assertTrue(spec.resetOn().isEmpty());
    }

    @Test
    void getReturnsDefaultUntilWritten() {
        RoleStateKey<Integer> key = store.register(souls());
        assertEquals(0, store.get(key, PLAYER));
        store.set(key, PLAYER, 7);
        assertEquals(7, store.get(key, PLAYER));
        assertEquals(0, store.get(key, OTHER_PLAYER), "other player still sees the default");
    }

    @Test
    void storedNullIsDistinctFromDefault() {
        RoleStateKey<Integer> key = store.register(souls());
        store.set(key, PLAYER, null);
        assertNull(store.get(key, PLAYER));
    }

    @Test
    void playerSetRequiresPlayerId() {
        RoleStateKey<Integer> key = store.register(souls());
        assertThrows(IllegalArgumentException.class, () -> store.set(key, (UUID) null, 1));
    }

    @Test
    void worldSlotIsSharedAcrossPlayers() {
        RoleStateKey<Integer> key = store.register(RoleStateSpec.of("habitrain_core", "pool", Integer.class)
                .role(ROLE)
                .scope(StateScope.WORLD)
                .persistence(Persistence.WORLD)
                .codec(Codec.INT)
                .resetOn(ResetCause.MANUAL)
                .defaultValue(() -> 0)
                .build());
        store.set(key, PLAYER, 3);
        assertEquals(3, store.get(key, OTHER_PLAYER));
        assertEquals(3, store.get(key, (UUID) null));
    }

    @Test
    void roleLostClearsOnlyThatPlayersRole() {
        RoleStateKey<Integer> souls = store.register(souls());
        RoleStateKey<Integer> other = store.register(RoleStateSpec.of("habitrain_core", "marks", Integer.class)
                .role(OTHER)
                .defaultValue(() -> 0)
                .build());
        store.set(souls, PLAYER, 9);
        store.set(souls, OTHER_PLAYER, 4);
        store.set(other, PLAYER, 2);

        store.reset(PLAYER, ROLE, ResetCause.ROLE_LOST);

        assertEquals(0, store.get(souls, PLAYER));
        assertEquals(4, store.get(souls, OTHER_PLAYER));
        assertEquals(2, store.get(other, PLAYER));
    }

    @Test
    void roundEndClearsRoundPersistenceAndRoundScope() {
        RoleStateKey<Integer> souls = store.register(souls());
        RoleStateKey<Integer> roundFlag = store.register(RoleStateSpec.of("habitrain_core", "flag", Integer.class)
                .role(ROLE)
                .scope(StateScope.ROUND)
                .defaultValue(() -> -1)
                .build());
        RoleStateKey<Integer> worldPool = store.register(RoleStateSpec.of("habitrain_core", "pool", Integer.class)
                .role(ROLE)
                .scope(StateScope.WORLD)
                .persistence(Persistence.WORLD)
                .codec(Codec.INT)
                .resetOn(ResetCause.MANUAL)
                .defaultValue(() -> 0)
                .build());
        store.set(souls, PLAYER, 5);
        store.set(roundFlag, PLAYER, 1);
        store.set(worldPool, PLAYER, 8);

        store.reset((UUID) null, null, ResetCause.ROUND_END);

        assertEquals(0, store.get(souls, PLAYER));
        assertEquals(-1, store.get(roundFlag, PLAYER));
        assertEquals(8, store.get(worldPool, PLAYER), "WORLD/PERMANENT without ROUND_END must survive");
    }

    @Test
    void freezeRejectsFurtherRegistration() {
        store.register(souls());
        store.freeze();
        assertTrue(store.isFrozen());
        assertThrows(IllegalStateException.class, () -> store.register(
                RoleStateSpec.of("habitrain_core", "other", Integer.class).role(ROLE).build()));
    }

    @Test
    void duplicateIdAndRoleIsRejected() {
        store.register(souls());
        assertThrows(IllegalArgumentException.class, () -> store.register(souls()));
    }

    @Test
    void notifyLostResetsAfterOnLostHook() {
        RoleChangeServiceImpl change = new RoleChangeServiceImpl();
        java.util.concurrent.atomic.AtomicInteger order = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hookAt = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<ResetCause> resetCause = new AtomicReference<>();
        change.setLostNotifier((role, player) -> hookAt.set(order.incrementAndGet()));
        change.setStateResetter((player, role, cause) -> {
            resetCause.set(cause);
            order.incrementAndGet();
        });
        change.notifyLost(null, ROLE);
        assertEquals(1, hookAt.get(), "onLost must run first so the hook can read departing state");
        assertEquals(2, order.get(), "ROLE_LOST reset must run after onLost");
        assertEquals(ResetCause.ROLE_LOST, resetCause.get());
    }

    @Test
    void diagnosticsListRegisteredSpecsAndValues() {
        RoleStateServiceImpl svcStore = (RoleStateServiceImpl) RoleStateApi.instance();
        RoleStateKey<Integer> key = svcStore.register(souls());
        svcStore.set(key, PLAYER, 6);

        List<String> lines = RoleDiagnosticsCommands.state(PLAYER, ROLE.toString());
        assertEquals("state player=" + PLAYER + " role=" + ROLE, lines.getFirst());
        assertTrue(lines.stream().anyMatch(l -> l.contains("habitrain_core:souls") && l.contains("= 6")));
    }

    @Test
    void diagnosticsEmptyWhenNothingRegistered() {
        List<String> lines = RoleDiagnosticsCommands.state(null, null);
        assertEquals("state", lines.getFirst());
        assertEquals("  (none)", lines.get(1));
    }

    private static RoleStateSpec<Integer> souls() {
        return RoleStateSpec.of("habitrain_core", "souls", Integer.class)
                .role(ROLE)
                .defaultValue(() -> 0)
                .build();
    }
}
