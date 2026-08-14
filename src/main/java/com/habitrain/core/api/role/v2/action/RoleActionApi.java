package com.habitrain.core.api.role.v2.action;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Public managed-action service (v2 Role Extension platform, design §16.2).
 *
 * <p>Providers register a {@link RoleActionSpec} instead of their own
 * {@code CustomPacketPayload}. Core multiplexes one C2S and one S2C packet,
 * enforces size / rate / cooldown / current-role, and runs the handler on
 * the server thread.
 */
public interface RoleActionApi {

    static RoleActionApi instance() {
        return DefaultHolder.INSTANCE;
    }

    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleActionApi INSTANCE =
                new com.habitrain.core.role.action.RoleActionServiceImpl();
    }

    /**
     * Registers an action schema. Must be called during the registration phase
     * (before freeze).
     *
     * @return the registered spec
     * @throws IllegalStateException if the registry is frozen
     * @throws IllegalArgumentException on duplicate id or missing fields
     */
    RoleActionSpec register(RoleActionSpec spec);

    /** The spec bound to {@code id}, or {@code null} if it was never registered. */
    @Nullable RoleActionSpec spec(ResourceLocation id);

    /** Every registered spec, in registration order. */
    Collection<RoleActionSpec> specs();

    /** Specs bound to {@code role}. */
    List<RoleActionSpec> specsFor(RoleKey role);

    /**
     * Dispatches a C2S (or bidirectional) action after the platform gates.
     * {@code currentRole} is the player's live role; tests pass it directly
     * so they never need a launched game.
     */
    RoleActionResult dispatch(ResourceLocation actionId, @Nullable UUID playerId,
                              @Nullable RoleKey currentRole, byte[] payload, int sequence);

    /**
     * Live-player convenience for {@link #dispatch}. Resolves the current role
     * from the game world when possible; missing player → reject unknown.
     */
    RoleActionResult receiveC2S(@Nullable ServerPlayer player, ResourceLocation actionId,
                                byte[] payload, int sequence);

    /**
     * Pushes an S2C (or bidirectional) payload to one player. No-ops when the
     * spec is missing, C2S-only, or {@code player} is null.
     */
    void sendTo(@Nullable ServerPlayer player, ResourceLocation actionId, byte[] payload);

    /** Prevents further {@link #register} calls. Idempotent. */
    void freeze();

    /** Whether {@link #freeze()} has been called. */
    boolean isFrozen();
}
