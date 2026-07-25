package com.habitrain.core.role.override;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class RoleOverrideLifecycleHandler {
    private RoleOverrideLifecycleHandler() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RoleOverrideRegistry.INSTANCE.freeze();
            RoleOverrideEngine.getInstance().rebuild();
        });
    }
}
