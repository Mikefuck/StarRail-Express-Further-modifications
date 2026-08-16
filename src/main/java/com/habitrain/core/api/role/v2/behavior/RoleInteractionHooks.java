package com.habitrain.core.api.role.v2.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Item / entity / block interaction hooks for a role.
 *
 * <p>Returning a non-{@code PASS} {@link InteractionResult} consumes the
 * interaction (first consume wins). {@link InteractionResult#PASS} defers
 * to the next hook / vanilla. Hooks run on the server thread only. All three
 * methods are wired to global Fabric listeners by core (audit P1-5).
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
     * Called when the holder right-clicks an entity. {@code target} is the
     * clicked entity (never {@code null} from the global listener). Return
     * {@link InteractionResult#PASS} to leave the interaction alone.
     */
    default InteractionResult useEntity(ServerPlayer player, @Nullable Entity target,
                                        InteractionHand hand, RoleHookContext ctx) {
        return InteractionResult.PASS;
    }

    /**
     * Called when the holder right-clicks a block. {@code hit} is the block
     * hit result (never {@code null} from the global listener). Return
     * {@link InteractionResult#PASS} to leave the interaction alone.
     */
    default InteractionResult useBlock(ServerPlayer player, @Nullable BlockHitResult hit,
                                       InteractionHand hand, RoleHookContext ctx) {
        return InteractionResult.PASS;
    }

    /**
     * Called when the holder attacks an entity. Return
     * {@link InteractionResult#PASS} to leave the attack alone; a non-PASS
     * result consumes the attack.
     */
    default InteractionResult attackEntity(ServerPlayer player, Entity target,
                                           InteractionHand hand, RoleHookContext ctx) {
        return InteractionResult.PASS;
    }

    /**
     * Called when the holder attacks a block. Return
     * {@link InteractionResult#PASS} to leave the attack alone; a non-PASS
     * result consumes the attack.
     */
    default InteractionResult attackBlock(ServerPlayer player, BlockPos pos,
                                          InteractionHand hand, RoleHookContext ctx) {
        return InteractionResult.PASS;
    }

    /** Called when the holder breaks a block (after a successful break). */
    default void breakBlock(ServerPlayer player, BlockPos pos, RoleHookContext ctx) {}
}
