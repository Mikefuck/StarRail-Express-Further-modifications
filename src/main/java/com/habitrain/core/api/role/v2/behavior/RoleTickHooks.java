package com.habitrain.core.api.role.v2.behavior;

import net.minecraft.server.MinecraftServer;

/**
 * Per-tick hook for a role. Runs on the server thread at the end of each server
 * tick, isolated by the dispatcher. Providers should keep work cheap; heavy
 * per-tick logic belongs in a scheduled slot rather than here.
 */
public interface RoleTickHooks {

    /** Called at the end of each server tick while the role is enabled. */
    default void onServerTick(MinecraftServer server, RoleHookContext ctx) {}

    /**
     * Scheduling tier for {@link #onServerTick}: the hook fires when the server
     * tick count is a multiple of this value (fix-doc §11.4). Recommended tiers
     * are 1, 5, 10 and 20 ticks. The default 1 keeps the previous every-tick
     * behaviour; return a larger value only for coarse, non-time-critical work.
     */
    default int tickInterval() {
        return 1;
    }
}
