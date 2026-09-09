package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla's tiny sleeping box misses most of the visible body. Keep a hittable lying body on both sides. */
@Mixin(Player.class)
public abstract class SlothSleepHitboxMixin {
    @Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
    private void habitrain$sleepingBody(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (pose == Pose.SLEEPING && SlothComponent.isSleepingSloth((Player) (Object) this)) {
            cir.setReturnValue(EntityDimensions.scalable(1.8F, 0.6F).withEyeHeight(0.2F));
        }
    }
}
