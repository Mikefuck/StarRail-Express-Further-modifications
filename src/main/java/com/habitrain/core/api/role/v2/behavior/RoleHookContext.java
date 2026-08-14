package com.habitrain.core.api.role.v2.behavior;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Read-only context handed to a role behavior hook.
 *
 * <p>Carries the canonical role the hook is registered for, the frozen snapshot
 * id, and the server (null on the client or in unit tests). Player/level are
 * passed as explicit hook parameters rather than stuffed into the context, so
 * each hook signature stays clear about which entity it concerns.
 */
public record RoleHookContext(RoleKey role, RoleSnapshotId snapshot, @Nullable MinecraftServer server) {

    public RoleHookContext {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
