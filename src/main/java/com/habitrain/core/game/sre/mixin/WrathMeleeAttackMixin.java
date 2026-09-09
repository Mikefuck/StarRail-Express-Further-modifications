package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reject melee before upstream item callbacks apply damage, stun or direct velocity. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class WrathMeleeAttackMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void habitrain$blockMelee(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (!player.server.isSameThread()) return;
        if (!(packet.getTarget(player.serverLevel()) instanceof Player target)
                || !WrathComponent.isMeleeImmune(target)) return;
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override public void onInteraction(InteractionHand hand) {}
            @Override public void onInteraction(InteractionHand hand, Vec3 location) {}
            @Override public void onAttack() { ci.cancel(); }
        });
    }
}
