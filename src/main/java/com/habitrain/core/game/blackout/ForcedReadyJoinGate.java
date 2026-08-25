package com.habitrain.core.game.blackout;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copy of the UUID list passed to {@code GameUtils.setForcedReadyPlayers}.
 *
 * <p>SRE clears its own forced-ready set inside {@code initializeGame} /
 * abort. This snapshot must survive that clear so STARTING/INITIATING
 * reconnects of rostered players are not spectator-teleported, while
 * late joiners (absent from the snapshot) are.</p>
 */
public final class ForcedReadyJoinGate {

    private static final Set<UUID> SNAPSHOT = ConcurrentHashMap.newKeySet();

    private ForcedReadyJoinGate() {}

    /** Replace the snapshot. {@code null} or empty clears it. */
    public static void snapshot(Collection<UUID> ids) {
        SNAPSHOT.clear();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (UUID id : ids) {
            if (id != null) {
                SNAPSHOT.add(id);
            }
        }
    }

    public static void clear() {
        SNAPSHOT.clear();
    }

    public static boolean contains(UUID id) {
        return id != null && SNAPSHOT.contains(id);
    }

    /** Test/debug copy. */
    public static Set<UUID> view() {
        return Set.copyOf(SNAPSHOT);
    }
}
