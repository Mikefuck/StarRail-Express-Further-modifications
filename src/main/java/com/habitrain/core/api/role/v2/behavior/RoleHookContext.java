package com.habitrain.core.api.role.v2.behavior;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Read-only context handed to a role behavior hook.
 *
 * <p>Carries the canonical role the hook is registered for, the frozen snapshot
 * id, the server (null on the client or in unit tests), and optional
 * level/entity fields that are populated when the dispatch site knows them.
 * Explicit hook parameters still carry the primary player/level arguments so
 * each signature stays clear; the context is an escape hatch for providers
 * that need the unified view. Win hooks additionally receive the proposed
 * win name / loose flag / locked outcome through the same context.
 */
public record RoleHookContext(
        RoleKey role,
        RoleSnapshotId snapshot,
        @Nullable MinecraftServer server,
        @Nullable ServerLevel level,
        @Nullable ServerPlayer holder,
        @Nullable ServerPlayer killer,
        @Nullable ServerPlayer victim,
        @Nullable ServerPlayer target,
        Map<String, Object> metadata,
        @Nullable String proposedWin,
        boolean looseWin,
        @Nullable WinOutcome winOutcome) {

    public RoleHookContext {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(snapshot, "snapshot");
        if (metadata == null) {
            metadata = Map.of();
        } else {
            metadata = Map.copyOf(metadata);
        }
    }

    /** Minimal constructor retained for tests and simple dispatch sites. */
    public RoleHookContext(RoleKey role, RoleSnapshotId snapshot, @Nullable MinecraftServer server) {
        this(role, snapshot, server, null, null, null, null, null, Map.of(),
                null, false, null);
    }

    /** Convenience constructor with a server and optional level. */
    public RoleHookContext(RoleKey role, RoleSnapshotId snapshot, @Nullable MinecraftServer server,
                           @Nullable ServerLevel level) {
        this(role, snapshot, server, level, null, null, null, null, Map.of(),
                null, false, null);
    }

    /** Convenience constructor for combat/kill dispatch sites. */
    public RoleHookContext(RoleKey role, RoleSnapshotId snapshot, @Nullable MinecraftServer server,
                           @Nullable ServerLevel level, @Nullable ServerPlayer holder,
                           @Nullable ServerPlayer killer, @Nullable ServerPlayer victim,
                           @Nullable ServerPlayer target) {
        this(role, snapshot, server, level, holder, killer, victim, target, Map.of(),
                null, false, null);
    }
}
