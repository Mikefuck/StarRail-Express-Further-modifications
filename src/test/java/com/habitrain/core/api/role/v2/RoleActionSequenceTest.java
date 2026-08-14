package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.action.ActionTargetCodec;
import com.habitrain.core.api.role.v2.action.RoleActionResult;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.role.action.RoleActionServiceImpl;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E action tests (fix-doc §12.2/§12.3): sequence windowing with replay /
 * stale / wraparound, per-player isolation, disconnect cleanup, structured
 * PLAYER_UUID target decoding, and the builder contract tying distance/LOS to a
 * declared target decoder.
 */
class RoleActionSequenceTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final ResourceLocation PICK = ResourceLocation.parse("habitrain_core:pick");
    private static final ResourceLocation SECOND = ResourceLocation.parse("habitrain_core:second");

    private RoleActionServiceImpl store;

    @BeforeEach
    void setUp() {
        store = new RoleActionServiceImpl();
    }

    private static RoleActionSpec ok(RoleActionSpec.Builder b) {
        return b.handler(ctx -> RoleActionResult.success()).build();
    }

    private static byte[] uuidBytes(UUID id) {
        byte[] out = new byte[16];
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (56 - i * 8));
            out[8 + i] = (byte) (lsb >>> (56 - i * 8));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Sequence / replay (§12.2)
    // ------------------------------------------------------------------

    @Test
    void duplicateSequenceIsReplay() {
        store.register(ok(RoleActionSpec.of(PICK).role(ROLE)));
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 5).ok());
        RoleActionResult replay = store.dispatch(PICK, PLAYER, ROLE, new byte[0], 5);
        assertEquals(RoleActionResult.REPLAY, replay.reasonKey());
    }

    @Test
    void farBehindSequenceIsStale() {
        store.register(ok(RoleActionSpec.of(PICK).role(ROLE)));
        store.dispatch(PICK, PLAYER, ROLE, new byte[0], 100);
        RoleActionResult stale = store.dispatch(PICK, PLAYER, ROLE, new byte[0], 0);
        assertEquals(RoleActionResult.STALE, stale.reasonKey());
    }

    @Test
    void integerWraparoundReadsAsNew() {
        store.register(ok(RoleActionSpec.of(PICK).role(ROLE)));
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], Integer.MAX_VALUE).ok());
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], Integer.MIN_VALUE).ok(),
                "a one-step signed wraparound must be treated as a new request");
    }

    @Test
    void sequenceTrackedPerPlayerAndPerAction() {
        store.register(ok(RoleActionSpec.of(PICK).role(ROLE)));
        store.register(ok(RoleActionSpec.of(SECOND).role(ROLE)));
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 7).ok());
        assertTrue(store.dispatch(PICK, OTHER, ROLE, new byte[0], 7).ok(),
                "same sequence for another player must be new");
        assertTrue(store.dispatch(SECOND, PLAYER, ROLE, new byte[0], 7).ok(),
                "same sequence for another action must be new");
    }

    @Test
    void disconnectClearsPlayerSequenceWindow() {
        store.register(ok(RoleActionSpec.of(PICK).role(ROLE)));
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 9).ok());
        store.onPlayerDisconnect(PLAYER);
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, new byte[0], 9).ok(),
                "after disconnect the same sequence is fresh again");
    }

    // ------------------------------------------------------------------
    // Structured target (§12.3)
    // ------------------------------------------------------------------

    @Test
    void playerUuidTargetDecodedIntoContext() {
        UUID target = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        AtomicReference<UUID> seen = new AtomicReference<>();
        store.register(RoleActionSpec.of(PICK).role(ROLE)
                .targetDecoder(ActionTargetCodec.PLAYER_UUID)
                .handler(ctx -> {
                    seen.set(ctx.targetId());
                    return RoleActionResult.success();
                })
                .build());
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, uuidBytes(target), 1).ok());
        assertEquals(target, seen.get(), "handler must receive the platform-decoded target");
    }

    @Test
    void distanceOrLosRequiresPlayerUuidTarget() {
        assertThrows(IllegalStateException.class, () -> ok(RoleActionSpec.of(PICK)
                .role(ROLE).maxDistance(10)));
        assertThrows(IllegalStateException.class, () -> ok(RoleActionSpec.of(PICK)
                .role(ROLE).requireLineOfSight(true)));
    }

    @Test
    void targetAliveRequiresPlayerUuidTarget() {
        assertThrows(IllegalStateException.class, () -> ok(RoleActionSpec.of(PICK)
                .role(ROLE).requireTargetAlive(true)));
        // With a PLAYER_UUID target the flag is allowed.
        store.register(ok(RoleActionSpec.of(PICK).role(ROLE)
                .targetDecoder(ActionTargetCodec.PLAYER_UUID)
                .requireTargetAlive(true)));
    }

    @Test
    void playerUuidTargetWithoutRangeChecksIsAllowed() {
        // A PLAYER_UUID target with no distance/LOS is fine (no-range actions).
        store.register(ok(RoleActionSpec.of(PICK).role(ROLE)
                .targetDecoder(ActionTargetCodec.PLAYER_UUID)));
        assertTrue(store.dispatch(PICK, PLAYER, ROLE, uuidBytes(OTHER), 1).ok());
    }
}
