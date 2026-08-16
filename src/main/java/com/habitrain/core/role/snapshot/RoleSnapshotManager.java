package com.habitrain.core.role.snapshot;

import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the compiled role snapshots and drives {@code NEXT_ROUND} activation.
 *
 * <p>Three slots: {@code lobby} (the prepared snapshot for the next round),
 * {@code round} (the snapshot fixed at round start — never changed mid-round) and
 * {@code pending} (a snapshot queued from a mid-round config change). {@link #current()}
 * returns the round snapshot while a round is live, otherwise the lobby snapshot.
 * {@link #activatePending()} promotes the pending snapshot to the lobby on the
 * next round boundary.
 */
public final class RoleSnapshotManager {

    public static final RoleSnapshotManager INSTANCE = new RoleSnapshotManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleSnapshotManager");

    private volatile @Nullable RoleSnapshot lobby;
    private volatile @Nullable RoleSnapshot round;
    private volatile @Nullable RoleSnapshot pending;

    private RoleSnapshotManager() {}

    /** Sets the prepared lobby snapshot (compiled at server start / config load). */
    public synchronized void setLobby(RoleSnapshot snapshot) {
        this.lobby = snapshot;
        RoleSnapshotArchive.INSTANCE.put(snapshot);
        RoleRuntimeOverlayApplier.activate(snapshot);
        LOGGER.info("Lobby snapshot set: {}", snapshot.id());
    }

    /** Fixes the round snapshot from the current lobby (called at round start). */
    public synchronized void beginRound() {
        this.round = lobby;
        RoleRuntimeOverlayApplier.activate(round);
        LOGGER.info("Round snapshot fixed: {}", round == null ? "none" : round.id());
    }

    /** Queues a snapshot compiled from a mid-round config change (activates NEXT_ROUND). */
    public synchronized void queuePending(RoleSnapshot snapshot) {
        this.pending = snapshot;
        RoleSnapshotArchive.INSTANCE.put(snapshot);
        // Deliberately NOT applied now: mid-round gameplay changes take effect
        // NEXT_ROUND via activatePending().
        LOGGER.info("Pending snapshot queued: {}", snapshot.id());
    }

    /** Promotes the pending snapshot to the lobby (called at the next round boundary). */
    public synchronized void activatePending() {
        if (pending != null) {
            this.lobby = pending;
            this.pending = null;
            RoleRuntimeOverlayApplier.activate(lobby);
            LOGGER.info("Pending snapshot activated as lobby: {}", lobby.id());
        }
    }

    /** Clears the round snapshot (called at round end). */
    public synchronized void endRound() {
        this.round = null;
    }

    /** The snapshot to read from now: the round snapshot if live, else the lobby. */
    public @Nullable RoleSnapshot current() {
        return round != null ? round : lobby;
    }

    public @Nullable RoleSnapshot lobby() {
        return lobby;
    }

    public @Nullable RoleSnapshot round() {
        return round;
    }

    public @Nullable RoleSnapshot pending() {
        return pending;
    }

    /** Clears every slot. Used by unit tests so a leftover lobby cannot leak. */
    public synchronized void clear() {
        this.lobby = null;
        this.round = null;
        this.pending = null;
    }
}
