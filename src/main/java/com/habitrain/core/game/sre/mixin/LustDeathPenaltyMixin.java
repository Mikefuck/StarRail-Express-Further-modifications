package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.LustIdentityRestriction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.agmas.noellesroles.component.DeathPenaltyComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Combines independent sources at the existing spectator-visibility gate, on both sides. */
@Mixin(value = DeathPenaltyComponent.class, remap = false)
public abstract class LustDeathPenaltyMixin implements LustIdentityRestriction {
    @Unique private boolean habitrain$lustIdentityHidden;
    @Shadow public abstract void sync();

    @Override public boolean habitrain$isLustIdentityHidden() { return habitrain$lustIdentityHidden; }

    @Override public void habitrain$setLustIdentityHidden(boolean hidden) {
        if (habitrain$lustIdentityHidden == hidden) return;
        habitrain$lustIdentityHidden = hidden;
        sync();
    }

    @Inject(method = "hasPenalty()Z", at = @At("RETURN"), cancellable = true)
    private void habitrain$includeLust(CallbackInfoReturnable<Boolean> cir) {
        if (habitrain$lustIdentityHidden) cir.setReturnValue(true);
    }

    // Upstream owns only its timer/camera/position. Its automatic clear must neither
    // erase Lust nor mistake Lust's independent flag for an expired upstream timer.
    @Redirect(method = {"check", "serverTick"}, at = @At(value = "INVOKE",
            target = "Lorg/agmas/noellesroles/component/DeathPenaltyComponent;hasPenalty()Z"))
    private boolean habitrain$checkOnlyUpstreamPenalty(DeathPenaltyComponent penalty) {
        return penalty.penaltyExpiry != 0 && penalty.hasPenalty();
    }

    @Inject(method = "writeToSyncNbt", at = @At("RETURN"))
    private void habitrain$writeLust(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        tag.putBoolean(LustIdentityRestriction.SYNC_KEY, habitrain$lustIdentityHidden);
    }

    @Inject(method = "readFromSyncNbt", at = @At("RETURN"))
    private void habitrain$readLust(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        habitrain$lustIdentityHidden = tag.getBoolean(LustIdentityRestriction.SYNC_KEY);
    }
}
