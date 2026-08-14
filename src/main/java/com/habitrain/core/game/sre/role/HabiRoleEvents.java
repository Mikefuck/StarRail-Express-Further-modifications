package com.habitrain.core.game.sre.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.component.FlowerGirlComponent;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * World-level leftovers that are not role-scoped: pepper-spray use
 * (item identity, not current role) and mime hidden-body tick (walks
 * every level). Role-scoped assign / death / kill / corpse hooks live
 * in {@link HabiRoleHooks} and run through the v2 dispatcher.
 */
public final class HabiRoleEvents {
    private HabiRoleEvents() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        HabiRoleHooks.register();

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (world.isClientSide) return InteractionResultHolder.pass(stack);
            if (!HabiRoleItems.isPepperSpray(stack)) {
                return InteractionResultHolder.pass(stack);
            }
            if (!(player instanceof ServerPlayer)) {
                return InteractionResultHolder.pass(stack);
            }
            if (player.getCooldowns().isOnCooldown(stack.getItem())) {
                return InteractionResultHolder.pass(stack);
            }
            long until = world.getGameTime() + FlowerGirlComponent.MELEE_IMMUNE_SECONDS * 20L;
            FlowerGirlComponent.setMeleeImmune(player, until);
            world.playSound(
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.FIRE_EXTINGUISH,
                    SoundSource.PLAYERS,
                    1.0f,
                    1.2f
            );
            stack.shrink(1);
            player.getCooldowns().addCooldown(
                    Items.HONEY_BOTTLE,
                    FlowerGirlComponent.PEPPER_SPRAY_CD_SECONDS * 20
            );
            return InteractionResultHolder.sidedSuccess(stack, world.isClientSide());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MimeKillerComponent.tickHiddenBodies(server.getAllLevels());
        });

        HabiTrainCore.LOGGER.info("[HabiRoleEvents] leftover listeners + v2 hooks registered");
    }
}
