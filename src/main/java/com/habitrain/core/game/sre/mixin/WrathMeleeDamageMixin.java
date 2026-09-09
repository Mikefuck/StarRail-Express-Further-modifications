package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class WrathMeleeDamageMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void habitrain$ignoreMeleeDamage(DamageSource source, float amount,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                && WrathComponent.isMeleeImmune((Player) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
