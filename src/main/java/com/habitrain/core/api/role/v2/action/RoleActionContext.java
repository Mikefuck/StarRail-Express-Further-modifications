package com.habitrain.core.api.role.v2.action;

import com.habitrain.core.api.role.v2.RoleKey;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side invocation context for a managed role action.
 *
 * <p>Holds the bound role, the acting player id (nullable in tests), the raw
 * payload, the client prediction sequence, and — when the spec declares a
 * structured target — the platform-verified {@link RoleActionTarget}. The
 * handler receives the verified target and never re-parses untrusted bytes
 * (fix-doc §12.3). Live {@code ServerPlayer} / {@code MinecraftServer} handles
 * stay off this record so unit tests stay bootstrap-safe.
 */
public record RoleActionContext(
        RoleKey role,
        @Nullable UUID playerId,
        byte[] payload,
        int sequence,
        @Nullable UUID targetId,
        @Nullable RoleActionTarget target) {

    /** Source-compatible constructor for specs without a structured target. */
    public RoleActionContext(RoleKey role, @Nullable UUID playerId, byte[] payload, int sequence) {
        this(role, playerId, payload, sequence, null, null);
    }

    /** Legacy constructor carrying only a player target id. */
    public RoleActionContext(RoleKey role, @Nullable UUID playerId, byte[] payload, int sequence,
                             @Nullable UUID targetId) {
        this(role, playerId, payload, sequence, targetId, RoleActionTarget.ofPlayer(targetId));
    }

    public RoleActionContext {
        Objects.requireNonNull(role, "role");
        payload = payload == null ? new byte[0] : payload;
    }
}
