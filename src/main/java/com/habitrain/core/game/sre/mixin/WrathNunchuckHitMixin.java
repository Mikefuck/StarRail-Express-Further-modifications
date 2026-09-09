package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import io.wifi.starrailexpress.network.original.NunchuckHitPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right-click nunchuck packets bypass vanilla handleInteract and set velocity directly. */
@Mixin(value = NunchuckHitPayload.class, remap = false)
public abstract class WrathNunchuckHitMixin {
    @Inject(method = "onHurt", at = @At("HEAD"), cancellable = true)
    private static void habitrain$rejectBerserkHit(ServerPlayer attacker, Player target, int direction,
                                                 CallbackInfo ci) {
        if (WrathComponent.isMeleeImmune(target)) ci.cancel();
    }
}
