package com.habitrain.core.role.force;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleForceServiceImplTest {

    @Test
    void consumedUpstreamQueueRemovesStaleApiMarker() {
        UUID player = UUID.randomUUID();
        Set<UUID> queued = new HashSet<>();
        queued.add(player);

        assertFalse(RoleForceServiceImpl.reconcileQueueState(queued, player, false));
        assertFalse(queued.contains(player));
    }

    @Test
    void activeUpstreamQueueStillRejectsAnotherCard() {
        UUID player = UUID.randomUUID();
        Set<UUID> queued = new HashSet<>();
        queued.add(player);

        assertTrue(RoleForceServiceImpl.reconcileQueueState(queued, player, true));
        assertTrue(queued.contains(player));
    }
}
