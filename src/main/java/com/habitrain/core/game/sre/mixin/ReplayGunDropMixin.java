package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import io.wifi.starrailexpress.network.PacketTracker;
import io.wifi.starrailexpress.network.original.GunDropPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketTracker.class, remap = false)
abstract class ReplayGunDropMixin {
    @Inject(method = "sendToClient", at = @At("HEAD"))
    private static void habitrain$markGunDrop(ServerPlayer player, CustomPacketPayload payload, CallbackInfo ci) {
        if (payload instanceof GunDropPayload) MatchReplayRecorder.gunDrop(player);
    }
}
