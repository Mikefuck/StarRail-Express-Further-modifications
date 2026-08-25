package com.habitrain.core.role.snapshot;

import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import com.habitrain.core.role.override.RoleOverrideTickApplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the compiled role snapshots and drives {@code NEXT_ROUND} activation.
 *
 * <p>Three slots: {@code lobby} (the prepared snapshot for the next round),
 * {@code round} (the snapshot fixed at round start — never changed mid-round) and
 * {@code pending} (a snapshot queued from a mid-round config change). {@link #current()}
 * returns the round snapshot while a round is live, the just-ended settlement
 * snapshot until pending is promoted, otherwise the lobby snapshot.
 * {@link #lastEnded()} exposes the settlement slot for settlement readers.
 * {@link #activatePending()} promotes the pending snapshot to the lobby on the
 * next round boundary.
 */
public final class RoleSnapshotManager {

    public static final RoleSnapshotManager INSTANCE = new RoleSnapshotManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleSnapshotManager");

    private volatile @Nullable RoleSnapshot lobby;
    private volatile @Nullable RoleSnapshot round;
    private volatile @Nullable RoleSnapshot pending;
    /** Held after {@link #endRound()} so settlement still reads the ended catalog. */
    private volatile @Nullable RoleSnapshot settlement;
    private boolean activatePendingScheduled;

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
        this.settlement = null;
        this.round = lobby;
        RoleRuntimeOverlayApplier.activate(round);
        RoleOverrideTickApplier.captureRound();
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
        this.settlement = null;
        RoleOverrideTickApplier.releaseRound();
    }

    /**
     * Marks {@link #activatePending()} to run once on the next server tick so later
     * {@code OnGameEnd} listeners still see the just-ended catalog.
     */
    public synchronized void scheduleActivatePendingNextTick() {
        this.activatePendingScheduled = true;
    }

    /**
     * Consumes a scheduled pending activation. Returns {@code true} when an
     * activation ran (callers should re-broadcast lobby snapshot/manifest).
     */
    public synchronized boolean tickActivatePendingIfDue() {
        if (!activatePendingScheduled) {
            return false;
        }
        activatePendingScheduled = false;
        activatePending();
        return true;
    }

    /** Clears the round snapshot (called at round end). */
    public synchronized void endRound() {
        this.settlement = this.round != null ? this.round : this.lobby;
        this.round = null;
    }

    /** The snapshot to read from now: the round snapshot if live, else settlement, else lobby. */
    public @Nullable RoleSnapshot current() {
        if (round != null) {
            return round;
        }
        if (settlement != null) {
            return settlement;
        }
        return lobby;
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

    /** The just-ended settlement snapshot, or {@code null} when none is held. */
    public @Nullable RoleSnapshot lastEnded() {
        return settlement;
    }

    /** Clears every slot. Used by unit tests so a leftover lobby cannot leak. */
    public synchronized void clear() {
        this.lobby = null;
        this.round = null;
        this.pending = null;
        this.settlement = null;
        this.activatePendingScheduled = false;
        RoleOverrideTickApplier.discardRoundFreeze();
    }
}
