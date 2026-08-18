package com.habitrain.core.role.config;

import com.habitrain.core.network.RoleManifestPayload;
import com.habitrain.core.network.RoleSnapshotPayload;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.override.RoleOverrideLifecycleHandler;
import net.minecraft.server.MinecraftServer;

/**
 * Shared server-side apply path for {@code roleExtensionsV2} config changes
 * (commands and the C2S page update): recompute the compiled entry statuses,
 * recompile the effective snapshot (queued as pending when a round is live,
 * otherwise the new lobby) and re-broadcast the manifest + entry snapshot so
 * clients see the change immediately (fix-doc §13.1/§14.2).
 */
public final class RoleConfigApplyService {

    private RoleConfigApplyService() {}

    public static void applyAndBroadcast(MinecraftServer server) {
        RoleExtensionRegistry.INSTANCE.recomputeCompiledEntries();
        RoleOverrideLifecycleHandler.publishSnapshotAfterRebuild();
        if (server != null) {
            // The client reports the definition hash from its latest snapshot.
            // Publish that snapshot first so a config change cannot make the
            // manifest receiver report the previous hash and fail-close role
            // actions until the next reconnect.
            RoleSnapshotPayload.broadcastToAll(server);
            RoleManifestPayload.broadcastToAll(server);
        }
    }
}
