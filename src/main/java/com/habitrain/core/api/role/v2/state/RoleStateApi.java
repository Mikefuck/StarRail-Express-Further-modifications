package com.habitrain.core.api.role.v2.state;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Public role-state service (v2 Role Extension platform).
 *
 * <p>Providers register a {@link RoleStateSpec} (schema) rather than a new
 * CCA {@code ComponentKey}. Values live under {@code provider/role/state-key}
 * and are reset by the platform on {@link ResetCause} events. {@link StateScope#WORLD}/
 * {@link StateScope#ROUND} slots are isolated by world key; {@link StateScope#PLAYER}
 * slots follow the player across worlds.
 *
 * <p>Registration is NOT a public write surface (audit P1-2): schemas are
 * registered through the provider-scoped {@code RoleExtensionRegistrar}
 * transaction, which records provider/entry ownership so the v2 config's
 * provider/entry gates apply at runtime. While a schema's provider or entry is
 * disabled its stored values are {@code retained but inaccessible}: reads
 * return the default, writes and syncs are no-ops, and the retained values
 * resume under their normal lifecycle once re-enabled.
 */
public interface RoleStateApi {

    static RoleStateApi instance() {
        return DefaultHolder.INSTANCE;
    }

    /** Lazily-bound default instance; avoids touching the game on class load. */
    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleStateApi INSTANCE =
                new com.habitrain.core.role.state.RoleStateServiceImpl();
    }

    /** The spec bound to {@code key}, or {@code null} if it was never registered. */
    @Nullable <T> RoleStateSpec<T> spec(RoleStateKey<T> key);

    /** Every registered spec, in registration order. */
    Collection<RoleStateSpec<?>> specs();

    /** Specs bound to {@code role}. */
    List<RoleStateSpec<?>> specsFor(RoleKey role);

    /**
     * Reads a player-scoped value. World/round keys ignore {@code player} and
     * use the slot of the player's current world (null when unknown). Missing
     * values return the spec default (which may itself be {@code null}).
     */
    @Nullable <T> T get(RoleStateKey<T> key, @Nullable ServerPlayer player);

    /** Same as {@link #get(RoleStateKey, ServerPlayer)} keyed by UUID and a null world. */
    @Nullable <T> T get(RoleStateKey<T> key, @Nullable UUID playerId);

    /**
     * World-aware read: {@code worldKey} resolves the {@link StateScope#WORLD}/
     * {@link StateScope#ROUND} slot of that world (ignored for PLAYER scope).
     * {@code null} addresses the default/unknown-world bucket.
     */
    @Nullable <T> T get(RoleStateKey<T> key, @Nullable UUID playerId,
                         @Nullable ResourceLocation worldKey);

    /** Writes a player-scoped value. {@code null} stores a present-but-null slot. */
    <T> void set(RoleStateKey<T> key, @Nullable ServerPlayer player, @Nullable T value);

    /** Same as {@link #set(RoleStateKey, ServerPlayer, Object)} keyed by UUID. */
    <T> void set(RoleStateKey<T> key, @Nullable UUID playerId, @Nullable T value);

    /** World-aware write, mirroring {@link #get(RoleStateKey, UUID, ResourceLocation)}. */
    <T> void set(RoleStateKey<T> key, @Nullable UUID playerId,
                 @Nullable ResourceLocation worldKey, @Nullable T value);

    /**
     * Drops every stored slot whose spec lists {@code cause}.
     * Player-scoped causes ({@link ResetCause#ROLE_LOST},
     * {@link ResetCause#ROLE_ASSIGNED}) require a non-null {@code playerId}
     * and only clear that player's slots for {@code role} (or every role
     * when {@code role} is {@code null}).
     */
    void reset(@Nullable UUID playerId, @Nullable RoleKey role, ResetCause cause);

    /** Convenience for {@link #reset(UUID, RoleKey, ResetCause)} with a live player. */
    default void reset(@Nullable ServerPlayer player, @Nullable RoleKey role, ResetCause cause) {
        reset(player == null ? null : player.getUUID(), role, cause);
    }

    /** Prevents further {@link #register} calls. Idempotent. */
    void freeze();

    /** Whether {@link #freeze()} has been called. */
    boolean isFrozen();
}
