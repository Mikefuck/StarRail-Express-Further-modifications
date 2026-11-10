package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import io.wifi.starrailexpress.network.original.GunShootPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GunShootPayload.Receiver.class, remap = false)
public abstract class TailorDollGunMixin {
    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/GunShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$noSewingShot(GunShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (DollTailorService.isCasting(context.player())) ci.cancel();
    }
    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/GunShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V", at = @At("TAIL"))
    private void habitrain$shootDoll(GunShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        DollTailorService.gunHit(context.player(), payload.target());
    }
}
