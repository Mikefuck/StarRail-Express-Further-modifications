package com.habitrain.core.client.role;

import com.habitrain.core.role.config.ClientManifest;
import com.habitrain.core.role.config.RoleHandshakeStatus;
import com.habitrain.core.role.config.RoleManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleHandshakeStateTest {

    @AfterEach
    void reset() {
        RoleHandshakeState.INSTANCE.reset();
    }

    @Test
    void newerSnapshotRecoversManifestThatInitiallySawOldHash() {
        RoleManifest server = new RoleManifest(
                "2.0", List.of(), Set.of("add"), "new-hash",
                "snapshot-new", null, "presentation", "{}");
        ClientManifest oldSnapshot = new ClientManifest(
                "2.0", Map.of(), true, "old-hash", null, Set.of());
        ClientManifest newSnapshot = new ClientManifest(
                "2.0", Map.of(), true, "new-hash", null, Set.of());

        RoleHandshakeState.INSTANCE.accept(server, oldSnapshot);
        assertEquals(RoleHandshakeStatus.HASH_MISMATCH,
                RoleHandshakeState.INSTANCE.status());

        RoleHandshakeState.INSTANCE.onSnapshotUpdated(newSnapshot);
        assertEquals(RoleHandshakeStatus.OK, RoleHandshakeState.INSTANCE.status(),
                "receiving the matching snapshot must clear the stale hash mismatch");
    }
}
