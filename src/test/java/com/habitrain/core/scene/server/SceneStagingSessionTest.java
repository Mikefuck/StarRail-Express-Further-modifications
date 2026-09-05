package com.habitrain.core.scene.server;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SceneStagingSessionTest {
    private final UUID requester = UUID.randomUUID();
    private final SceneAssetDescriptor descriptor = new SceneAssetDescriptor(
            "a".repeat(64), 200L, 100L, 1, 1, "registry", 1L);

    @Test
    void exactIdentityAndCurrentToolContextAreRequired() {
        SceneStagingSession session = session(2_000L);
        assertEquals(SceneStagingSession.ValidationFailure.NONE,
                validate(session, requester, true, "stage", "map1", descriptor.sha256(),
                        "tool", "map1", "minecraft:overworld", 1_000L));
        assertEquals(SceneStagingSession.ValidationFailure.IDENTITY_MISMATCH,
                validate(session, requester, true, "stage", "map1", "b".repeat(64),
                        "tool", "map1", "minecraft:overworld", 1_000L));
        assertEquals(SceneStagingSession.ValidationFailure.TOOL_SESSION_CHANGED,
                validate(session, requester, true, "stage", "map1", descriptor.sha256(),
                        "other", "map1", "minecraft:overworld", 1_000L));
        assertEquals(SceneStagingSession.ValidationFailure.MAP_CHANGED,
                validate(session, requester, true, "stage", "map1", descriptor.sha256(),
                        "tool", "map2", "minecraft:overworld", 1_000L));
    }

    @Test
    void expiryPermissionLossAndDimensionChangeBlockPromotion() {
        SceneStagingSession session = session(2_000L);
        assertEquals(SceneStagingSession.ValidationFailure.EXPIRED,
                validate(session, requester, true, "stage", "map1", descriptor.sha256(),
                        "tool", "map1", "minecraft:overworld", 2_000L));
        assertEquals(SceneStagingSession.ValidationFailure.PERMISSION_LOST,
                validate(session, requester, false, "stage", "map1", descriptor.sha256(),
                        "tool", "map1", "minecraft:overworld", 1_000L));
        assertEquals(SceneStagingSession.ValidationFailure.DIMENSION_CHANGED,
                validate(session, requester, true, "stage", "map1", descriptor.sha256(),
                        "tool", "map1", "minecraft:the_nether", 1_000L));
    }

    @Test
    void strictReportMustSucceedAndMatchRegistryFingerprint() {
        SceneStagingSession session = session(2_000L);
        assertFalse(session.acceptStrictReport(false, "registry", "vanilla"));
        assertFalse(session.reportAccepted());
        assertFalse(session.acceptStrictReport(true, "tampered", "vanilla"));
        assertFalse(session.reportAccepted());
        assertTrue(session.acceptStrictReport(true, "registry", "sodium=false"));
        assertTrue(session.reportAccepted());
        assertEquals("sodium=false", session.clientEnvironment());
    }

    private SceneStagingSession session(long expiresAt) {
        return new SceneStagingSession("stage", requester, "map1", "minecraft:overworld",
                "tool", descriptor, expiresAt);
    }

    private static SceneStagingSession.ValidationFailure validate(
            SceneStagingSession session, UUID actor, boolean op, String stage, String map,
            String hash, String tool, String editorMap, String dimension, long now) {
        return session.validate(actor, op, stage, map, hash, tool, editorMap, dimension, now);
    }
}
