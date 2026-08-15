package com.habitrain.core.role.state;

import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.api.role.v2.state.SyncPolicy;
import com.habitrain.core.network.RoleStateSyncPayload;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Computes the recipient set for a changed state slot from its
 * {@link SyncPolicy} and pushes one {@link RoleStateSyncPayload} per
 * recipient (fix-doc §10.4). The player index and the network sender are
 * injectable so the recipient math is unit-testable without a game.
 *
 * <p>{@link SyncPolicy#NONE} and {@link SyncPolicy#SERVER_ONLY} never send.
 * {@link SyncPolicy#OWNER} reaches only the owning player;
 * {@link SyncPolicy#OWNER_AND_TRACKING} adds the players currently
 * tracking the owner (e.g. spectators following them); {@link SyncPolicy#ALL}
 * reaches every online player.
 *
 * <p>Every send carries a monotonic {@code revision} (audit P1-5) so a client
 * can pick the latest mirror deterministically. Removals (audit P0-1) reuse the
 * same recipient math but emit a {@code removed} payload, so a deleted slot is
 * deleted on the client instead of lingering with a stale value.
 */
public final class RoleStateSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleStateSync");

    /** Supplies the online player index for recipient resolution. */
    public interface RecipientProvider {
        /** Every online player on the server. */
        Collection<UUID> allOnline();

        /** Online players whose current world matches {@code worldKey} (may be null = unknown). */
        Collection<UUID> inWorld(@Nullable String worldKey);

        /**
         * Online players currently tracking {@code playerId} this round — i.e.
         * spectators whose camera follows that player. Defaults to empty so
         * simple providers only need {@link #allOnline()} and {@link #inWorld}.
         */
        default Collection<UUID> trackersOf(@Nullable UUID playerId) {
            return List.of();
        }
    }

    /** Delivers one payload to one player (resolves {@code ServerPlayer} + sends). */
    public interface Sender {
        void send(UUID playerId, RoleStateSyncPayload payload);
    }

    private static final RecipientProvider EMPTY_RECIPIENTS = new RecipientProvider() {
        @Override
        public Collection<UUID> allOnline() {
            return List.of();
        }

        @Override
        public Collection<UUID> inWorld(@Nullable String worldKey) {
            return List.of();
        }
    };

    private static final Sender NOOP_SENDER = (playerId, payload) -> { };

    private volatile RecipientProvider recipients = EMPTY_RECIPIENTS;
    private volatile Sender sender = NOOP_SENDER;
    private final AtomicLong revisionSeq = new AtomicLong();

    public void setRecipients(RecipientProvider provider) {
        this.recipients = provider == null ? EMPTY_RECIPIENTS : provider;
    }

    public void setSender(Sender sender) {
        this.sender = sender == null ? NOOP_SENDER : sender;
    }

    /** Next monotonic send revision (client "latest" tie-breaker). */
    public long nextRevision() {
        return revisionSeq.incrementAndGet();
    }

    /**
     * Called after a slot is written. {@code encoded} is the codec-encoded
     * payload already size-checked against the spec.
     */
    public void onChanged(RoleStateSpec<?> spec, StateSlotKey slot, @Nullable byte[] encoded) {
        if (spec == null || slot == null) {
            return;
        }
        SyncPolicy policy = spec.sync();
        if (policy == SyncPolicy.NONE || policy == SyncPolicy.SERVER_ONLY) {
            return;
        }
        RoleStateSyncPayload payload = RoleStateSyncPayload.value(
                spec.id(), spec.role(), spec.scope(), slot.worldKey(), spec.dataVersion(),
                encoded, slot.playerId(), revisionSeq.incrementAndGet());
        for (UUID to : resolveRecipients(policy, slot)) {
            try {
                sender.send(to, payload);
            } catch (Throwable t) {
                LOGGER.warn("state sync {} -> {} failed", slot, to, t);
            }
        }
    }

    /**
     * Called after a slot is deleted (reset / round-end / world-unload, audit
     * P0-1). Broadcasts a {@code removed} payload to the same recipients the
     * value would have reached, so clients drop the slot instead of keeping a
     * stale copy.
     */
    public void onRemoved(RoleStateSpec<?> spec, StateSlotKey slot) {
        if (spec == null || slot == null) {
            return;
        }
        SyncPolicy policy = spec.sync();
        if (policy == SyncPolicy.NONE || policy == SyncPolicy.SERVER_ONLY) {
            return;
        }
        RoleStateSyncPayload payload = RoleStateSyncPayload.removed(
                spec.id(), spec.role(), spec.scope(), slot.worldKey(), spec.dataVersion(),
                slot.playerId(), revisionSeq.incrementAndGet());
        for (UUID to : resolveRecipients(policy, slot)) {
            try {
                sender.send(to, payload);
            } catch (Throwable t) {
                LOGGER.warn("state removal {} -> {} failed", slot, to, t);
            }
        }
    }

    /**
     * Whether {@code playerId} is entitled to this slot under its
     * {@link SyncPolicy}. Used by the late-join full sync (audit P0-2): the
     * server pushes every existing slot to a joining player, filtered here so
     * {@code OWNER}/{@code OWNER_AND_TRACKING}/{@code ALL} work and
     * {@code NONE}/{@code SERVER_ONLY} never leak.
     */
    public boolean isRecipient(RoleStateSpec<?> spec, StateSlotKey slot, @Nullable UUID playerId) {
        if (spec == null || slot == null || playerId == null) {
            return false;
        }
        SyncPolicy policy = spec.sync();
        switch (policy) {
            case OWNER:
                return slot.playerId() != null && slot.playerId().equals(playerId);
            case OWNER_AND_TRACKING:
                if (slot.playerId() != null) {
                    return slot.playerId().equals(playerId)
                            || recipients.trackersOf(slot.playerId()).contains(playerId);
                }
                return recipients.inWorld(slot.worldKey()).contains(playerId);
            case ALL:
                return recipients.allOnline().contains(playerId);
            case NONE:
            case SERVER_ONLY:
            default:
                return false;
        }
    }

    /**
     * Sends one existing slot to a single player (late-join / reconnect full
     * sync, audit P0-2), subject to the same permission filter as
     * {@link #isRecipient}. Non-snapshot variant (single-slot push).
     */
    public void sendTo(@Nullable UUID playerId, RoleStateSpec<?> spec, StateSlotKey slot,
                       @Nullable byte[] encoded) {
        sendTo(playerId, spec, slot, encoded, false, 0);
    }

    /**
     * Sends one existing slot to a single player as part of a permission-
     * filtered full snapshot ({@code snapshot=true}, review P2): the client
     * drops stale mirrors on the snapshot begin payload, so a player who
     * re-tracks a target (or re-enters a world) no longer keeps mirrors for
     * slots the server no longer holds.
     */
    public void sendTo(@Nullable UUID playerId, RoleStateSpec<?> spec, StateSlotKey slot,
                       @Nullable byte[] encoded, boolean snapshot, long snapshotId) {
        if (!isRecipient(spec, slot, playerId)) {
            return;
        }
        try {
            sender.send(playerId, snapshot
                    ? RoleStateSyncPayload.snapshotValue(
                            spec.id(), spec.role(), spec.scope(), slot.worldKey(),
                            spec.dataVersion(), encoded, slot.playerId(),
                            revisionSeq.incrementAndGet(), snapshotId)
                    : RoleStateSyncPayload.value(
                            spec.id(), spec.role(), spec.scope(), slot.worldKey(),
                            spec.dataVersion(), encoded, slot.playerId(),
                            revisionSeq.incrementAndGet()));
        } catch (Throwable t) {
            LOGGER.warn("state full-sync {} -> {} failed", slot, playerId, t);
        }
    }

    /** Sends the explicit start of a full snapshot batch. */
    public void beginSnapshot(@Nullable UUID playerId, long snapshotId) {
        if (playerId == null) {
            return;
        }
        try {
            sender.send(playerId, RoleStateSyncPayload.snapshotBegin(
                    snapshotId, revisionSeq.incrementAndGet()));
        } catch (Throwable t) {
            LOGGER.warn("state snapshot begin -> {} failed", playerId, t);
        }
    }

    /** Sends the explicit end of a full snapshot batch. */
    public void endSnapshot(@Nullable UUID playerId, long snapshotId) {
        if (playerId == null) {
            return;
        }
        try {
            sender.send(playerId, RoleStateSyncPayload.snapshotEnd(
                    snapshotId, revisionSeq.incrementAndGet()));
        } catch (Throwable t) {
            LOGGER.warn("state snapshot end -> {} failed", playerId, t);
        }
    }

    private Collection<UUID> resolveRecipients(SyncPolicy policy, StateSlotKey slot) {
        switch (policy) {
            case OWNER: {
                if (slot.playerId() == null) {
                    return List.of();
                }
                return List.of(slot.playerId());
            }
            case OWNER_AND_TRACKING: {
                LinkedHashSet<UUID> out = new LinkedHashSet<>();
                UUID owner = slot.playerId();
                if (owner != null) {
                    // PLAYER scope: owner plus whoever is spectating/tracking them.
                    out.add(owner);
                    out.addAll(recipients.trackersOf(owner));
                } else {
                    // WORLD/ROUND scope carries no owner; fall back to world membership.
                    out.addAll(recipients.inWorld(slot.worldKey()));
                }
                return out;
            }
            case ALL:
                return recipients.allOnline();
            case NONE:
            case SERVER_ONLY:
            default:
                return List.of();
        }
    }
}