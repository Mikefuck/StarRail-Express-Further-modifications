package com.habitrain.core.role.override;

import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.role.snapshot.RoleSnapshotCompiler;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.role.snapshot.RoleSnapshotVersions;
import io.wifi.starrailexpress.api.TMMRoles;

public final class RoleOverrideLifecycleHandler {
    private RoleOverrideLifecycleHandler() {}

    public static void init() {
        com.habitrain.core.game.sre.roleoverride.SreRoleOverrideWinBridge.init();
        // Freeze runs in rebuildAfterConfigLoad after ConfigManager.load (Fabric
        // SERVER_STARTED order follows registration; early freeze here would skip
        // compile). Downstream must register via habitrain:role_extensions
        // entrypoint / onInitialize, not SERVER_STARTED.
    }

    /** 由 LifecycleEventsRegistrar 在配置加载后调用。 */
    public static void rebuildAfterConfigLoad() {
        com.habitrain.core.internal.CoreBootstrap.run(() -> {
            RoleOverrideRegistry.INSTANCE.freeze();
            com.habitrain.core.role.extension.RoleExtensionRegistry.INSTANCE.freeze();
            com.habitrain.core.role.behavior.RoleHookRegistry.INSTANCE.freeze();
            com.habitrain.core.api.role.v2.state.RoleStateApi.instance().freeze();
            com.habitrain.core.api.role.v2.action.RoleActionApi.instance().freeze();
            com.habitrain.core.api.role.v2.capability.RoleCapabilityApi.instance().freeze();
        });
        RoleOverrideEngine.getInstance().rebuild();
        publishSnapshotAfterRebuild();
        com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.start();
    }

    /**
     * Compiles the current effective view. Outside a round this becomes the
     * lobby snapshot; mid-round it is queued for NEXT_ROUND activation.
     */
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
