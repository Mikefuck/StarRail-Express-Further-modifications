package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.agmas.noellesroles.init.ModItems;
import org.agmas.noellesroles.init.ModPacketsReciever;
import org.agmas.noellesroles.packet.TryThrowItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enforce Swift Wind's full item cooldown before upstream consumes or throws a knife. */
@Mixin(value = ModPacketsReciever.class, remap = false)
public abstract class SwiftWindThrowCooldownMixin {
    // Match the receiver by its unique descriptor, not the compiler-generated lambda number.
    // Both argument types belong to mods, so this selector is identical in production.
    @Inject(method = "(Lorg/agmas/noellesroles/packet/TryThrowItemPacket;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void habitrain$guardThrowReceiver(TryThrowItemPacket payload,
            ServerPlayNetworking.Context context, CallbackInfo ci) {
        var player = context.player();
        // Upstream only rejects the last 20 ticks. Check every remaining tick here,
        // including Q-key requests which bypass the normal item-use cooldown gate.
        if (player.getMainHandItem().is(ModItems.THROWING_KNIFE)
                && HabiRoles.isHabiRole(player, HabiRoles.SWIFT_WIND)
                && player.getCooldowns().isOnCooldown(ModItems.THROWING_KNIFE)) {
            ci.cancel();
        }
    }
}
