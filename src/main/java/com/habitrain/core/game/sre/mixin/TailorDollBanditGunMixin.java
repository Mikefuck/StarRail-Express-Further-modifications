package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.agmas.noellesroles.packet.BanditRevolverShootPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BanditRevolverShootPayload.class, remap = false)
public abstract class TailorDollBanditGunMixin {
    // Bandit's handler nests its cooldown guard rather than returning; attach inside the accepted shot.
    @Inject(method = "extracted", at = @At("HEAD"), cancellable = true)
    private static void habi$locked(ServerPlayer player, Entity target, CallbackInfo ci) {
        if (DollTailorService.isCasting(player)) ci.cancel();
    }
    @Inject(method = "extracted", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", ordinal = 0, remap = true))
    private static void habi$hit(ServerPlayer player, Entity target, CallbackInfo ci) {
        if (target != null) DollTailorService.gunHit(player, target.getId());
    }
}
