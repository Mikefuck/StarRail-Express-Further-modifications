package com.habitrain.core.client.mixin;

import com.habitrain.core.client.tailor.DollTailorClient;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class DollTailorOutlineColorMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void habitrain$useProtectedColor(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof TailorDollEntity doll) {
            var color = DollTailorClient.highlight(doll);
            if (color.isPresent()) cir.setReturnValue(color.getAsInt());
        }
    }
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void habitrain$holdCamera(double yaw, double pitch, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if ((Object) this == net.minecraft.client.Minecraft.getInstance().player && DollTailorClient.casting()) ci.cancel();
    }
}
