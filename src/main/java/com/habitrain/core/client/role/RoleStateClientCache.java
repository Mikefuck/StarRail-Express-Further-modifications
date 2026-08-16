package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.StateScope;
import com.habitrain.core.network.RoleStateSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client mirror of server-synced role-state slots (fix-doc §10.4). Payloads are
 * kept opaque (bytes + version); decoding happens against the client-side
 * extension registry (Phase F handshake). Cleared on disconnect so a stale
 * world's state never leaks into the next server.
 *
 * <p>Identity is the full slot key: id/role/scope <em>plus</em> {@code worldKey}
 * and owner (audit P1-5) — two worlds or two players never overwrite each other.
 * "Latest" selection uses the server's monotonic {@code revision} (a send
 * counter), never the schema {@code dataVersion}. A {@code removed} payload
 * deletes the mirror (audit P0-1) instead of overwriting it with a stale copy.
 */
@Environment(EnvType.CLIENT)
public final class RoleStateClientCache {

    private static final Map<SlotRef, RoleStateSyncPayload> latest = new ConcurrentHashMap<>();
    private static final Map<SlotRef, RoleStateSyncPayload> staging = new ConcurrentHashMap<>();
    private static volatile long lastCompletedSnapshotId;
    private static volatile long snapshotId;
    private static volatile boolean snapshotInProgress;

    private RoleStateClientCache() {}

    /** Full identity of a mirrored slot: id/role/scope + world + owner. */
    public record SlotRef(ResourceLocation id, RoleKey role, StateScope scope,
                          @Nullable String worldKey, @Nullable UUID ownerPlayerId) {
    }

    public static void accept(RoleStateSyncPayload payload) {
        if (payload == null) {
            return;
        }
        if (payload.snapshotBegin()) {
            if (payload.snapshotId() <= lastCompletedSnapshotId) {
                return;
            }
            staging.clear();
            snapshotId = payload.snapshotId();
            snapshotInProgress = true;
            return;
        }
        if (payload.snapshotEnd()) {
            if (snapshotInProgress && payload.snapshotId() == snapshotId) {
                latest.clear();
                latest.putAll(staging);
                staging.clear();
                lastCompletedSnapshotId = snapshotId;
                snapshotInProgress = false;
            }
            return;
        }
        if (payload.snapshot()) {
            if (!snapshotInProgress || payload.snapshotId() != snapshotId) {
                return;
            }
            SlotRef ref = new SlotRef(payload.id(), payload.role(), payload.scope(),
                    payload.worldKey(), payload.ownerPlayerId());
            if (payload.removed()) {
                staging.remove(ref);
            } else {
                staging.put(ref, payload);
            }
            return;
        }
        // Ordinary incremental payload: apply to the committed cache and cancel
        // any in-progress staging so a later end cannot overwrite newer data.
        snapshotInProgress = false;
        staging.clear();
        SlotRef ref = new SlotRef(payload.id(), payload.role(), payload.scope(),
                payload.worldKey(), payload.ownerPlayerId());
        if (payload.removed()) {
            latest.remove(ref);
        } else {
            latest.put(ref, payload);
        }
    }

    /** Precise lookup for one full slot key (id/role/scope/world/owner). */
    public static @Nullable RoleStateSyncPayload forSlot(ResourceLocation id, RoleKey role,
                                                         StateScope scope,
                                                         @Nullable String worldKey,
                                                         @Nullable UUID ownerPlayerId) {
        if (id == null || role == null || scope == null) {
            return null;
        }
        return latest.get(new SlotRef(id, role, scope, worldKey, ownerPlayerId));
    }

    /**
     * The mirror for an id/role/scope with the highest server {@code revision}
     * (any world/owner), or {@code null}. Deterministic: a higher revision is
     * strictly a later send, so equal-dataVersion slots no longer tie.
     */
    public static @Nullable RoleStateSyncPayload latest(ResourceLocation id, RoleKey role,
                                                        StateScope scope) {
        if (id == null || role == null || scope == null) {
            return null;
        }
        RoleStateSyncPayload found = null;
        for (Map.Entry<SlotRef, RoleStateSyncPayload> e : latest.entrySet()) {
            SlotRef ref = e.getKey();
            if (ref.id().equals(id) && ref.role().equals(role) && ref.scope() == scope) {
                if (found == null || e.getValue().revision() > found.revision()) {
                    found = e.getValue();
                }
            }
        }
        return found;
    }

    /** Whether a mirror exists for an exact slot key. */
    public static boolean contains(ResourceLocation id, RoleKey role, StateScope scope,
                                   @Nullable String worldKey, @Nullable UUID ownerPlayerId) {
        return id != null && role != null && scope != null
                && latest.containsKey(new SlotRef(id, role, scope, worldKey, ownerPlayerId));
    }

    public static void clear() {
        latest.clear();
        staging.clear();
        lastCompletedSnapshotId = 0;
        snapshotId = 0;
        snapshotInProgress = false;
    }
}