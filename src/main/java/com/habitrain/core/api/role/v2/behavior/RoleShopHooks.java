package com.habitrain.core.api.role.v2.behavior;

import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Shop purchase hooks for a role.
 *
 * <p>{@link #allowBuy} returns a {@link Decision}; {@code DENY} cancels the
 * purchase (the existing fail path still runs). {@link #onBuy} fires after a
 * successful debit. {@link #onAnyBuy} is broadcast to every subscribed role
 * so observer roles (e.g. a capitalist) can watch other players buy.
 */
public interface RoleShopHooks {

    /**
     * Whether the buyer may purchase this entry. Return {@link Decision#DENY}
     * to cancel. {@code price} is the quoted/effective price when known,
     * otherwise the entry's base price.
     */
    default Decision allowBuy(ServerPlayer buyer, @Nullable ShopEntry entry,
                              int index, int price, RoleHookContext ctx) {
        return Decision.PASS;
    }

    /** Called after the buyer successfully pays and receives the entry. */
    default void onBuy(ServerPlayer buyer, @Nullable ShopEntry entry,
                       int index, int price, RoleHookContext ctx) {}

    /**
     * Called when any player successfully buys, for every registered shop
     * hook. {@code ctx.role()} is the subscribed role, not the buyer's.
     */
    default void onAnyBuy(ServerPlayer buyer, @Nullable ShopEntry entry,
                          int index, int price, RoleHookContext ctx) {}
}
