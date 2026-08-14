package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.action.RoleActionApi;
import com.habitrain.core.api.role.v2.action.RoleActionContext;
import com.habitrain.core.api.role.v2.action.RoleActionDirection;
import com.habitrain.core.api.role.v2.action.RoleActionResult;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.role.action.RoleActionServiceImpl;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 {@link RoleActionApi}: schema registration, size / rate /
 * cooldown / current-role gates, handler isolation, freeze, and the
 * {@code /habitrain roleapi actions} formatter. Uses a dedicated
 * {@link RoleActionServiceImpl} for store tests so the process singleton
 * stays clean; singleton tests reset via {@code clear(true)}.
 */
class RoleActionApiTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final RoleKey OTHER = RoleKey.of("habitrain_core", "other_role");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final ResourceLocation PICK = ResourceLocation.parse("habitrain_core:pick");

    private RoleActionServiceImpl store;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        store = new RoleActionServiceImpl();
        now = new AtomicLong(1_000_000L);
        store.setClock(now::get);
        ((RoleActionServiceImpl) RoleActionApi.instance()).clear(true);
    }

    @AfterEach
    void tearDown() {
        ((RoleActionServiceImpl) RoleActionApi.instance()).clear(true);
    }

    @Test
    void builderRequiresRoleAndHandlerForC2s() {
        assertThrows(IllegalStateException.class,
                () -> RoleActionSpec.of("habitrain_core", "pick").handler(ctx -> RoleActionResult.success()).build());
        assertThrows(IllegalStateException.class,
                () -> RoleActionSpec.of("habitrain_core", "pick").role(ROLE).build());
    }

    @Test
    void s2cMayOmitHandler() {
        RoleActionSpec spec = RoleActionSpec.of("habitrain_core", "prompt")
                .role(ROLE)
                .direction(RoleActionDirection.S2C)
                .build();
        assertEquals(RoleActionDirection.S2C, spec.direction());
        assertNull(spec.handler());
    }

    @Test
    void dispatchUnknownRejects() {
        RoleActionResult result = store.dispatch(PICK, PLAYER, ROLE, new byte[0], 1);
        assertFalse(result.ok());
        assertEquals(RoleActionResult.UNKNOWN, result.reasonKey());
    }

    @Test
    void dispatchWrongRoleRejects() {
        store.register(pick(ctx -> RoleActionResult.success()));
        RoleActionResult result = store.dispatch(PICK, PLAYER, OTHER, new byte[0], 1);
        assertFalse(result.ok());
        assertEquals(RoleActionResult.WRONG_ROLE, result.reasonKey());
    }

    @Test
    void dispatchTooLargeRejects() {
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .maxBytes(2)
                .handler(ctx -> RoleActionResult.success())
                .build());
        RoleActionResult result = store.dispatch(PICK, PLAYER, ROLE, new byte[]{1, 2, 3}, 1);
        assertFalse(result.ok());
        assertEquals(RoleActionResult.TOO_LARGE, result.reasonKey());
    }

    @Test
    void dispatchRateThenAllowsAfterWindow() {
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .ratePerSecond(1)
                .handler(ctx -> RoleActionResult.success())
                .build());
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 1).ok());
        RoleActionResult limited = store.dispatch(PICK, PLAYER, ROLE, new byte[0], 2);
        assertFalse(limited.ok());
        assertEquals(RoleActionResult.RATE, limited.reasonKey());
        now.addAndGet(1_001L);
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 3).ok());
    }

    @Test
    void rejectedHandlerAttemptsConsumeRateWindow() {
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .ratePerSecond(1)
                .handler(ctx -> RoleActionResult.reject(RoleActionResult.HANDLER))
                .build());
        assertEquals(RoleActionResult.HANDLER,
                store.dispatch(PICK, PLAYER, ROLE, new byte[0], 1).reasonKey());
        assertEquals(RoleActionResult.RATE,
                store.dispatch(PICK, PLAYER, ROLE, new byte[0], 2).reasonKey(),
                "failed provider calls must not bypass the action rate limit");
    }

    @Test
    void dispatchCooldownBlocksUntilElapsed() {
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .ratePerSecond(20)
                .cooldownTicks(20)
                .handler(ctx -> RoleActionResult.success())
                .build());
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 1).ok());
        RoleActionResult cooling = store.dispatch(PICK, PLAYER, ROLE, new byte[0], 2);
        assertFalse(cooling.ok());
        assertEquals(RoleActionResult.COOLDOWN, cooling.reasonKey());
        now.addAndGet(1_000L);
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 3).ok());
    }

    @Test
    void cooldownRejectDoesNotConsumeRateWindow() {
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .ratePerSecond(1)
                .cooldownTicks(20)
                .handler(ctx -> RoleActionResult.success())
                .build());
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 1).ok());
        assertEquals(RoleActionResult.COOLDOWN,
                store.dispatch(PICK, PLAYER, ROLE, new byte[0], 2).reasonKey());
        assertEquals(RoleActionResult.COOLDOWN,
                store.dispatch(PICK, PLAYER, ROLE, new byte[0], 3).reasonKey());
        now.addAndGet(1_000L);
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 4).ok(),
                "cooldown rejects must not exhaust the rate window");
    }

    @Test
    void successCarriesPayloadBytes() {
        byte[] body = {1, 2, 3};
        RoleActionResult result = RoleActionResult.success(body);
        assertTrue(result.ok());
        assertEquals(3, result.payload().length);
        assertEquals(1, result.payload()[0]);
    }

    @Test
    void handlerReceivesPayloadAndSequence() {
        AtomicReference<RoleActionContext> seen = new AtomicReference<>();
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .handler(ctx -> {
                    seen.set(ctx);
                    return RoleActionResult.success();
                })
                .build());
        byte[] payload = {9, 8};
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, payload, 17).ok());
        assertEquals(ROLE, seen.get().role());
        assertEquals(PLAYER, seen.get().playerId());
        assertEquals(17, seen.get().sequence());
        assertEquals(2, seen.get().payload().length);
        assertEquals(9, seen.get().payload()[0]);
    }

    @Test
    void handlerThrowableIsIsolated() {
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .handler(ctx -> {
                    throw new IllegalStateException("boom");
                })
                .build());
        RoleActionResult result = store.dispatch(PICK, PLAYER, ROLE, new byte[0], 1);
        assertFalse(result.ok());
        assertEquals(RoleActionResult.HANDLER, result.reasonKey());
        assertEquals("IllegalStateException", result.detail());
    }

    @Test
    void s2cOnlyDispatchIsRejected() {
        store.register(RoleActionSpec.of(PICK)
                .role(ROLE)
                .direction(RoleActionDirection.S2C)
                .build());
        RoleActionResult result = store.dispatch(PICK, PLAYER, ROLE, new byte[0], 1);
        assertFalse(result.ok());
        assertEquals(RoleActionResult.WRONG_DIRECTION, result.reasonKey());
    }

    @Test
    void sendToC2sOnlyIsNoOp() {
        AtomicInteger hits = new AtomicInteger();
        store.setS2cSender((player, id, payload) -> hits.incrementAndGet());
        store.register(pick(ctx -> RoleActionResult.success()));
        store.sendTo(null, PICK, new byte[]{1});
        assertEquals(0, hits.get());
    }

    @Test
    void freezeRejectsFurtherRegistration() {
        store.register(pick(ctx -> RoleActionResult.success()));
        store.freeze();
        assertTrue(store.isFrozen());
        assertThrows(IllegalStateException.class, () -> store.register(
                RoleActionSpec.of("habitrain_core", "other").role(ROLE).handler(ctx -> RoleActionResult.success()).build()));
    }

    @Test
    void duplicateIdIsRejected() {
        store.register(pick(ctx -> RoleActionResult.success()));
        assertThrows(IllegalArgumentException.class, () -> store.register(pick(ctx -> RoleActionResult.success())));
    }

    @Test
    void diagnosticsListRegisteredSpecs() {
        RoleActionServiceImpl svc = (RoleActionServiceImpl) RoleActionApi.instance();
        svc.register(pick(ctx -> RoleActionResult.success()));
        List<String> lines = RoleDiagnosticsCommands.actions(ROLE.toString());
        assertEquals("actions role=" + ROLE, lines.getFirst());
        assertTrue(lines.stream().anyMatch(l -> l.contains("habitrain_core:pick") && l.contains("dir=C2S")));
    }

    @Test
    void diagnosticsEmptyWhenNothingRegistered() {
        List<String> lines = RoleDiagnosticsCommands.actions(null);
        assertEquals("actions", lines.getFirst());
        assertEquals("  (none)", lines.get(1));
    }

    private static RoleActionSpec pick(com.habitrain.core.api.role.v2.action.RoleActionHandler handler) {
        return RoleActionSpec.of(PICK)
                .role(ROLE)
                .handler(handler)
                .build();
    }
}
