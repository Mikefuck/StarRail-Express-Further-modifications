package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.agmas.noellesroles.content.item.ZeroOneFiveShootPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ZeroOneFiveShootPayload.Receiver.class, remap = false)
public abstract class TailorDollZeroOneFiveMixin {
    @Inject(method = "receive(Lorg/agmas/noellesroles/content/item/ZeroOneFiveShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V", at = @At("HEAD"), cancellable = true)
    private void habi$locked(ZeroOneFiveShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (DollTailorService.isCasting(context.player())) ci.cancel();
    }
    @Inject(method = "receive(Lorg/agmas/noellesroles/content/item/ZeroOneFiveShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V", at = @At("TAIL"))
    private void habi$hit(ZeroOneFiveShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        DollTailorService.rangedHit(context.player(), payload.target(), 30);
    }
}
