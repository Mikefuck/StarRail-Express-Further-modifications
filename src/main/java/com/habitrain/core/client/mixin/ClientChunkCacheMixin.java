package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.SceneProjectionDiagnostics;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the server-supplied chunk view radius without reading private renderer state. */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "updateViewRadius", at = @At("HEAD"))
    private void habitrain$captureServerViewDistance(int viewDistance, CallbackInfo ci) {
        SceneProjectionDiagnostics.setServerViewDistanceChunks(viewDistance);
    }
}
