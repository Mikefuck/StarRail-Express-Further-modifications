package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class SlothLivingSleepMixin {
    @Inject(method = "stopSleeping", at = @At("HEAD"), cancellable = true)
    private void habitrain$keepInducedSleep(CallbackInfo ci) {
        if ((Object) this instanceof Player player && SlothComponent.isSleepingSloth(player)) {
            ci.cancel();
        }
    }
}
