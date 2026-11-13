package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.agmas.noellesroles.gunfx.GunTracers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GunTracers.class, remap = false)
abstract class ReplayGunTracerMixin {
    @Inject(method = "broadcast", at = @At("HEAD"))
    private static void habitrain$captureShot(ServerPlayer player, Entity hit, double range, CallbackInfo ci) {
        MatchReplayRecorder.shot(player, hit, range, false);
    }
}
