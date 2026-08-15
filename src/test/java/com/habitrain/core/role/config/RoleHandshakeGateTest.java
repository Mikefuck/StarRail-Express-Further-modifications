package com.habitrain.core.role.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit P1-4: the server-authoritative handshake gate must fail closed
 * (no report / missing provider / API mismatch / hash mismatch → actions
 * blocked) while OK and presentation-only DEGRADED stay allowed.
 */
class RoleHandshakeGateTest {

    private static final String API = "2.0";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final RoleManifest SERVER = new RoleManifest(
            API,
            List.of(new RoleProviderManifest("habitrain_dlc", "1.2.0", true)),
            Set.of("add", "action"),
            "serverHash",
            "lobby-1",
            null,
            "presHash",
            "{}");

    @BeforeEach
    void setUp() {
        RoleHandshakeGate.INSTANCE.setManifestSupplier(() -> SERVER);
        RoleHandshakeGate.INSTANCE.clear(PLAYER);
    }

    private static ClientManifest client(boolean hasPresentation, String definitionHash) {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("habitrain_dlc", "1.2.0");
        return new ClientManifest(API, versions, hasPresentation, definitionHash, null);
    }

    @Test
    void noReportFailsClosed() {
        assertEquals(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER,
                RoleHandshakeGate.INSTANCE.resultFor(PLAYER).status());
        assertFalse(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER));
        assertNotNull(RoleHandshakeGate.INSTANCE.blockReason(PLAYER));
    }

    @Test
    void okReportAllowsActions() {
        RoleHandshakeGate.INSTANCE.record(PLAYER, client(true, "serverHash"));
        assertEquals(RoleHandshakeStatus.OK, RoleHandshakeGate.INSTANCE.resultFor(PLAYER).status());
        assertTrue(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER));
        assertNull(RoleHandshakeGate.INSTANCE.blockReason(PLAYER));
    }

    @Test
    void missingRequiredProviderBlocks() {
        RoleHandshakeGate.INSTANCE.record(PLAYER,
                new ClientManifest(API, Map.of(), true, "serverHash", null));
        assertEquals(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER,
                RoleHandshakeGate.INSTANCE.resultFor(PLAYER).status());
        assertFalse(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER));
    }

    @Test
    void apiMismatchBlocks() {
        RoleHandshakeGate.INSTANCE.record(PLAYER,
                new ClientManifest("1.9", Map.of("habitrain_dlc", "1.2.0"), true, "serverHash", null));
        assertEquals(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER,
                RoleHandshakeGate.INSTANCE.resultFor(PLAYER).status());
        assertFalse(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER));
    }

    @Test
    void definitionHashMismatchBlocks() {
        RoleHandshakeGate.INSTANCE.record(PLAYER, client(true, "staleHash"));
        assertEquals(RoleHandshakeStatus.HASH_MISMATCH,
                RoleHandshakeGate.INSTANCE.resultFor(PLAYER).status());
        assertFalse(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER));
        assertNotNull(RoleHandshakeGate.INSTANCE.blockReason(PLAYER));
    }

    @Test
    void presentationDegradeStillAllowsActions() {
        RoleHandshakeGate.INSTANCE.record(PLAYER,
                new ClientManifest(API, Map.of("habitrain_dlc", "1.2.0"), false, null, "stalePres"));
        assertEquals(RoleHandshakeStatus.DEGRADED_CLIENT_EXTENSION,
                RoleHandshakeGate.INSTANCE.resultFor(PLAYER).status());
        assertTrue(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER),
                "presentation-only degradation must degrade, not block, actions");
    }

    @Test
    void clearRemovesReportAndBlocksAgain() {
        RoleHandshakeGate.INSTANCE.record(PLAYER, client(true, "serverHash"));
        assertTrue(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER));
        RoleHandshakeGate.INSTANCE.clear(PLAYER);
        assertFalse(RoleHandshakeGate.INSTANCE.isActionAllowed(PLAYER),
                "a cleared report must fail closed again");
    }
}
