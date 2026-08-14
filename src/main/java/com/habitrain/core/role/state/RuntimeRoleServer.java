package com.habitrain.core.role.state;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime holder for the live {@link MinecraftServer} used by the CCA store and
 * the role-state sync wiring. Bound at SERVER_STARTED and cleared at
 * SERVER_STOPPING so an integrated server restart inside the same JVM does not
 * leak a stale reference (fix-doc §20.2).
 */
public final class RuntimeRoleServer {

    public static final RuntimeRoleServer INSTANCE = new RuntimeRoleServer();

    private volatile MinecraftServer server;

    private RuntimeRoleServer() {}

    public void bind(MinecraftServer server) {
        this.server = server;
    }

    public void unbind() {
        this.server = null;
    }

    public @Nullable MinecraftServer server() {
        return server;
    }
}
