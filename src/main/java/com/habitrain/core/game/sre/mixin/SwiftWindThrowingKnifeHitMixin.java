package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.component.SwiftWindComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.EntityHitResult;
import org.agmas.noellesroles.content.entity.ThrowingKnifeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A frenzy knife refreshes on player contact, including nonlethal hits. */
@Mixin(ThrowingKnifeEntity.class)
public abstract class SwiftWindThrowingKnifeHitMixin {
    @Inject(method = "onHitEntity", at = @At("TAIL"))
    private void habitrain$refreshOnPlayerHit(EntityHitResult hit, CallbackInfo ci) {
        ThrowingKnifeEntity knife = (ThrowingKnifeEntity) (Object) this;
        // Run after upstream kill callbacks so a refund cannot restore the cooldown.
        if (hit.getEntity() instanceof ServerPlayer target
                && knife.getOwner() instanceof ServerPlayer owner
                && !target.getUUID().equals(owner.getUUID())
                && SwiftWindComponent.isPsychoActive(owner)) {
            SwiftWindComponent.refreshPsychoKnifeCooldown(owner);
        }
    }
}
