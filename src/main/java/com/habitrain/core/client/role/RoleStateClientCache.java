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
 */
@Environment(EnvType.CLIENT)
public final class RoleStateClientCache {

    private static final Map<SlotRef, RoleStateSyncPayload> latest = new ConcurrentHashMap<>();

    private RoleStateClientCache() {}

    /** Identity of a mirrored slot: id/role/scope plus the owner (player) it belongs to. */
    public record SlotRef(ResourceLocation id, RoleKey role, StateScope scope,
                          @Nullable UUID ownerPlayerId) {
    }

    public static void accept(RoleStateSyncPayload payload) {
        if (payload == null) {
            return;
        }
        latest.put(new SlotRef(payload.id(), payload.role(), payload.scope(),
                payload.ownerPlayerId()), payload);
    }

    /** The most recent mirror for an id/role/scope (any owner), or {@code null}. */
    public static @Nullable RoleStateSyncPayload latest(ResourceLocation id, RoleKey role,
                                                        StateScope scope) {
        if (id == null || role == null || scope == null) {
            return null;
        }
        RoleStateSyncPayload found = null;
        for (Map.Entry<SlotRef, RoleStateSyncPayload> e : latest.entrySet()) {
            SlotRef ref = e.getKey();
            if (ref.id().equals(id) && ref.role().equals(role) && ref.scope() == scope) {
                if (found == null
                        || e.getValue().dataVersion() > found.dataVersion()) {
                    found = e.getValue();
                }
            }
        }
        return found;
    }

    public static void clear() {
        latest.clear();
    }
}
