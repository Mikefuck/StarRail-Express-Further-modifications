package com.habitrain.core.client.mixin;

import com.habitrain.core.client.tailor.DollTailorClient;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class DollTailorInputMixin extends Input {
    @Inject(method = "tick", at = @At("RETURN"))
    private void habitrain$stopMovement(CallbackInfo ci) {
        if (!DollTailorClient.casting()) return;
        leftImpulse = forwardImpulse = 0; jumping = shiftKeyDown = false;
        up = down = left = right = false;
    }
}
