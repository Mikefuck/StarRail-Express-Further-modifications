package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import io.wifi.starrailexpress.network.original.KnifeStabPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KnifeStabPayload.Receiver.class, remap = false)
public abstract class TailorDollKnifeMixin {
    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/KnifeStabPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$stabDoll(KnifeStabPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (DollTailorService.isCasting(context.player())) { ci.cancel(); return; }
        if (context.player().serverLevel().getEntity(payload.target()) instanceof TailorDollEntity doll) {
            DollTailorService.knifeHit(context.player(), doll);
            ci.cancel();
        }
    }
}
