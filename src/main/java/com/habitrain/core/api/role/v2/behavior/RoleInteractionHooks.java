package com.habitrain.core.api.role.v2.behavior;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Item / entity / block interaction hooks for a role.
 *
 * <p>Returning a non-{@code PASS} {@link InteractionResult} consumes the
 * interaction (first consume wins). {@link InteractionResult#PASS} defers
 * to the next hook / vanilla. Hooks run on the server thread only.
 */
public interface RoleInteractionHooks {

    /**
     * Called when the holder uses an item. Return {@link InteractionResult#PASS}
     * to leave the interaction alone.
     */
    default InteractionResult useItem(ServerPlayer player, ItemStack stack,
                                      InteractionHand hand, RoleHookContext ctx) {
        return InteractionResult.PASS;
    }

    /**
     * Reserved: entity-use interactions. Default is a no-op so providers can
     * implement this incrementally; the dispatcher wires it when a listener
     * is added.
     */
    default InteractionResult useEntity(ServerPlayer player, @Nullable Object target,
                                        InteractionHand hand, RoleHookContext ctx) {
        return InteractionResult.PASS;
    }

    /**
     * Reserved: block-use interactions. Default is a no-op so providers can
     * implement this incrementally; the dispatcher wires it when a listener
     * is added.
     */
    default InteractionResult useBlock(ServerPlayer player, @Nullable Object hit,
                                       InteractionHand hand, RoleHookContext ctx) {
        return InteractionResult.PASS;
    }
}
