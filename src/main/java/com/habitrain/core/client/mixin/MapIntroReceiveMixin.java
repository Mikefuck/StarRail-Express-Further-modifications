package com.habitrain.core.client.mixin;

import com.habitrain.core.client.cache.ClientMapIntroCache;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe actual inbound packets before Fabric dispatch, never record construction/encoding. */
@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 1500)
public abstract class MapIntroReceiveMixin {
    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"))
    private void habitrain$receiveIntro(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!(packet.payload() instanceof MapIntroSyncPayload payload)
                || !((Object) this instanceof ClientPacketListener expected)) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (com.habitrain.core.client.ClientConnectionSession.isCurrent(expected)
                    && client.getConnection() == expected && expected.getConnection().isConnected()) {
                ClientMapIntroCache.update(payload);
            }
        });
    }
}
