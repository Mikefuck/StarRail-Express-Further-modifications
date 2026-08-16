package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.action.RoleActionResult;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E client action-result session tests (fix-doc §12.5): per-sequence
 * callbacks, concurrent requests, timeout, disconnect completion, push
 * delivery and defensive copies. The network sender and clock are injected so
 * no Fabric networking is touched.
 */
class RoleActionClientSessionTest {

    private static final ResourceLocation PICK = ResourceLocation.parse("habitrain_core:pick");

    private RoleActionClientSession session;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        session = RoleActionClientSession.INSTANCE;
        session.clear();
        now = new AtomicLong(1_000_000L);
        session.setClock(now::get);
        session.setTimeoutMs(10_000L);
        session.setSender((id, seq, payload) -> { });
    }

    @Test
    void resultResolvesPendingBySequence() {
        AtomicInteger sentSeq = new AtomicInteger(-1);
        session.setSender((id, seq, payload) -> sentSeq.set(seq));
        AtomicReference<RoleActionResult> got = new AtomicReference<>();
        session.send(PICK, new byte[]{1, 2}, (id, r) -> got.set(r));

        int seq = sentSeq.get();
        assertTrue(seq >= 0);
        session.onResult(PICK, seq, true, RoleActionResult.OK, new byte[]{9});

        assertNotNull(got.get());
        assertTrue(got.get().ok());
        assertEquals(9, got.get().payload()[0]);
        assertEquals(0, session.pendingCount(), "resolved request leaves no pending entry");
    }

    @Test
    void concurrentRequestsResolveIndependently() {
        List<Integer> sentSeqs = new ArrayList<>();
        session.setSender((id, seq, payload) -> sentSeqs.add(seq));
        AtomicReference<RoleActionResult> first = new AtomicReference<>();
        AtomicReference<RoleActionResult> second = new AtomicReference<>();
        session.send(PICK, new byte[]{1}, (id, r) -> first.set(r));
        session.send(PICK, new byte[]{2}, (id, r) -> second.set(r));
        assertEquals(2, session.pendingCount());

        // Resolve out of order — each callback gets its own result.
        session.onResult(PICK, sentSeqs.get(1), true, RoleActionResult.OK, new byte[]{20});
        session.onResult(PICK, sentSeqs.get(0), false, RoleActionResult.TIMEOUT, new byte[]{});
        assertTrue(second.get().ok());
        assertFalse(first.get().ok());
        assertEquals(0, session.pendingCount());
    }

    @Test
    void timeoutCompletesPending() {
        session.setTimeoutMs(500);
        AtomicReference<RoleActionResult> got = new AtomicReference<>();
        session.send(PICK, new byte[]{1}, (id, r) -> got.set(r));
        now.addAndGet(1_000);
        session.tick();
        assertNotNull(got.get());
        assertEquals(RoleActionResult.TIMEOUT, got.get().reasonKey());
        assertEquals(0, session.pendingCount());
    }

    @Test
    void noTimeoutBeforeDeadline() {
        session.setTimeoutMs(10_000);
        AtomicInteger fired = new AtomicInteger();
        session.send(PICK, new byte[]{1}, (id, r) -> fired.incrementAndGet());
        now.addAndGet(9_000);
        session.tick();
        assertEquals(0, fired.get(), "in-flight request must not time out early");
        assertEquals(1, session.pendingCount());
    }

    @Test
    void clearCompletesAllWithDisconnected() {
        AtomicInteger disconnected = new AtomicInteger();
        session.send(PICK, new byte[]{1}, (id, r) -> {
            if (RoleActionResult.DISCONNECTED.equals(r.reasonKey())) {
                disconnected.incrementAndGet();
            }
        });
        session.send(PICK, new byte[]{2}, (id, r) -> {
            if (RoleActionResult.DISCONNECTED.equals(r.reasonKey())) {
                disconnected.incrementAndGet();
            }
        });
        session.clear();
        assertEquals(2, disconnected.get());
        assertEquals(0, session.pendingCount());
    }

    @Test
    void lateResultForUnknownRequestIsDropped() {
        // A response that never matched a sent request must not crash or fire anything.
        session.onResult(PICK, 999, true, RoleActionResult.OK, new byte[]{});
        assertEquals(0, session.pendingCount());
    }

    @Test
    void pushDeliveredAndRemovedWithListener() {
        AtomicReference<byte[]> got = new AtomicReference<>();
        java.util.function.Consumer<byte[]> listener = got::set;
        session.addPushListener(PICK, listener);
        session.onPush(PICK, new byte[]{5, 6});
        assertNotNull(got.get());
        assertEquals(2, got.get().length);
        assertEquals(5, got.get()[0]);

        session.removePushListener(PICK, listener);
        got.set(null);
        session.onPush(PICK, new byte[]{7});
        assertNull(got.get(), "removed listener must not receive further pushes");
    }

    @Test
    void payloadsAreDefensivelyCopied() {
        AtomicInteger sentSeq = new AtomicInteger(-1);
        AtomicReference<byte[]> sent = new AtomicReference<>();
        session.setSender((id, seq, payload) -> {
            sentSeq.set(seq);
            sent.set(payload);
        });

        byte[] original = new byte[]{1, 2, 3};
        AtomicReference<RoleActionResult> got = new AtomicReference<>();
        session.send(PICK, original, (id, r) -> got.set(r));
        original[0] = 99; // mutate the caller's array after send
        assertEquals(1, sent.get()[0], "sender must receive a copy, not the caller's array");

        byte[] response = new byte[]{8, 9};
        session.onResult(PICK, sentSeq.get(), true, RoleActionResult.OK, response);
        response[0] = 77; // mutate the response array after delivery
        assertEquals(8, got.get().payload()[0], "callback must receive a defensive copy");
    }
}
