package com.habitrain.core.client.mixin;

import com.habitrain.core.client.tailor.DollTailorClient;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Minecraft.class, priority = 900)
public abstract class DollTailorGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
    private void habitrain$replaceVanillaGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (DollTailorClient.hidden(entity)) cir.setReturnValue(false);
        else if (entity instanceof TailorDollEntity doll) cir.setReturnValue(DollTailorClient.highlight(doll).isPresent());
    }
}
