package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import io.wifi.starrailexpress.network.original.SniperShootPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SniperShootPayload.Receiver.class, remap = false)
public abstract class TailorDollSniperMixin {
    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/SniperShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V", at = @At("HEAD"), cancellable = true)
    private void habi$locked(SniperShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (DollTailorService.isCasting(context.player())) ci.cancel();
    }

    /** Only runs after the original handler has accepted and paid for the shot. */
    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/SniperShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V", at = @At("TAIL"))
    private void habi$hit(SniperShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (payload.action() == SniperShootPayload.Action.SHOOT)
            DollTailorService.rangedHit(context.player(), payload.targetOrShooterId(), 200);
    }
}
