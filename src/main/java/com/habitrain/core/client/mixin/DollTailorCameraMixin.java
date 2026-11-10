package com.habitrain.core.client.mixin;

import com.habitrain.core.client.tailor.DollTailorClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class DollTailorCameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow public abstract float getYRot();
    @Shadow public abstract float getXRot();
    @Inject(method = "setup", at = @At("TAIL"))
    private void habitrain$sewingBreath(CallbackInfo ci) {
        float offset = DollTailorClient.cameraPitch(0);
        if (offset != 0) setRotation(getYRot(), getXRot() + offset);
    }
}
