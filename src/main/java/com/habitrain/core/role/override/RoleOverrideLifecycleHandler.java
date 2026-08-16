package com.habitrain.core.role.override;

import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.role.snapshot.RoleSnapshotCompiler;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.TMMRoles;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class RoleOverrideLifecycleHandler {
    private RoleOverrideLifecycleHandler() {}

    public static void init() {
        com.habitrain.core.game.sre.roleoverride.SreRoleOverrideWinBridge.init();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RoleOverrideRegistry.INSTANCE.freeze();
            com.habitrain.core.role.extension.RoleExtensionRegistry.INSTANCE.freeze();
            com.habitrain.core.role.behavior.RoleHookRegistry.INSTANCE.freeze();
            com.habitrain.core.api.role.v2.state.RoleStateApi.instance().freeze();
            com.habitrain.core.api.role.v2.action.RoleActionApi.instance().freeze();
            com.habitrain.core.api.role.v2.capability.RoleCapabilityApi.instance().freeze();
            com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.start();
            // Lobby snapshot is compiled after config rebuild — see rebuildAfterConfigLoad().
        });
    }

    /** 由 LifecycleEventsRegistrar 在配置加载后调用。 */
    public static void rebuildAfterConfigLoad() {
        RoleOverrideEngine.getInstance().rebuild();
        publishSnapshotAfterRebuild();
    }

    /**
     * Compiles the current effective view. Outside a round this becomes the
     * lobby snapshot; mid-round it is queued for NEXT_ROUND activation.
     */
    public static void publishSnapshotAfterRebuild() {
        RoleSnapshotId id = new RoleSnapshotId(RoleOverrideEngine.getInstance().getSnapshotVersion());
        var compiled = RoleSnapshotCompiler.compile(id, TMMRoles.ROLES);
        if (RoleSnapshotManager.INSTANCE.round() != null) {
            RoleSnapshotManager.INSTANCE.queuePending(compiled);
        } else {
            RoleSnapshotManager.INSTANCE.setLobby(compiled);
        }
    }
}
