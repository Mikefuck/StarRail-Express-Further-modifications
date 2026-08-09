package com.habitrain.core.role.override;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class RoleOverrideLifecycleHandler {
    private RoleOverrideLifecycleHandler() {}

    public static void init() {
        com.habitrain.core.game.sre.roleoverride.SreRoleOverrideWinBridge.init();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RoleOverrideRegistry.INSTANCE.freeze();
            // rebuild() 在 LifecycleEventsRegistrar 的 SERVER_STARTED handler 中
            // 于 ConfigManager.getInstance().load() 之后调用，确保配置已加载。
        });
    }

    /** 由 LifecycleEventsRegistrar 在配置加载后调用。 */
    public static void rebuildAfterConfigLoad() {
        RoleOverrideEngine.getInstance().rebuild();
    }
}
