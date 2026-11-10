package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server enforcement, including modified clients and packets sent before the overlay arrives. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DollTailorMovementLockMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = {"handleMovePlayer", "handlePlayerAction", "handleUseItem", "handleUseItemOn", "handleInteract"},
            at = @At("HEAD"), cancellable = true)
    private void habitrain$lockSewing(CallbackInfo ci) {
        if (player.server.isSameThread() && DollTailorService.isCasting(player)) ci.cancel();
    }
}
