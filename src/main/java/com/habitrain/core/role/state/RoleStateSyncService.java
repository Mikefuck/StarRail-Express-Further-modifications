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

    public void setRecipients(RecipientProvider provider) {
        this.recipients = provider == null ? EMPTY_RECIPIENTS : provider;
    }

    public void setSender(Sender sender) {
        this.sender = sender == null ? NOOP_SENDER : sender;
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
        RoleStateSyncPayload payload = new RoleStateSyncPayload(
                spec.id(), spec.role(), spec.scope(), slot.worldKey(), spec.dataVersion(), encoded,
                slot.playerId());
        for (UUID to : resolveRecipients(policy, slot)) {
            try {
                sender.send(to, payload);
            } catch (Throwable t) {
                LOGGER.warn("state sync {} -> {} failed", slot, to, t);
            }
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
