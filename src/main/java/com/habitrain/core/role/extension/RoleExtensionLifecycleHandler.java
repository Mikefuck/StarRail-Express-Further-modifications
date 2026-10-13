package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.role.snapshot.RoleSnapshotCompiler;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.role.snapshot.RoleSnapshotVersions;
import io.wifi.starrailexpress.api.TMMRoles;

/** Freezes v2 declarations and publishes their effective lobby or pending snapshot. */
public final class RoleExtensionLifecycleHandler {
    private RoleExtensionLifecycleHandler() {}

    /** Called after the server configuration and all provider declarations are loaded. */
    public static void rebuildAfterConfigLoad() {
        com.habitrain.core.internal.CoreLifecycleScope.run(() -> {
            RoleExtensionRegistry.INSTANCE.freeze();
            com.habitrain.core.role.behavior.RoleHookRegistry.INSTANCE.freeze();
            com.habitrain.core.api.role.v2.state.RoleStateApi.instance().freeze();
            com.habitrain.core.api.role.v2.action.RoleActionApi.instance().freeze();
            com.habitrain.core.api.role.v2.capability.RoleCapabilityApi.instance().freeze();
        });
        RoleExtensionRegistry.INSTANCE.recomputeCompiledEntries();
        publishSnapshotAfterRebuild();
        com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.start();
    }

    /** Lobby changes activate immediately; changes during a round wait for the next round. */
    public static void publishSnapshotAfterRebuild() {
        RoleSnapshotId id = RoleSnapshotVersions.nextPublished();
        var compiled = RoleSnapshotCompiler.compile(id, TMMRoles.ROLES);
        if (RoleSnapshotManager.INSTANCE.round() != null) {
            RoleSnapshotManager.INSTANCE.queuePending(compiled);
        } else {
            RoleSnapshotManager.INSTANCE.setLobby(compiled);
        }
    }
}
