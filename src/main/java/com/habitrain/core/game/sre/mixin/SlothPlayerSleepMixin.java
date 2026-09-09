package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Player.class, ServerPlayer.class})
public abstract class SlothPlayerSleepMixin {
    @Inject(method = "stopSleepInBed", at = @At("HEAD"), cancellable = true)
    private void habitrain$preventWake(boolean immediately, boolean updateLevel, CallbackInfo ci) {
        if (SlothComponent.isSleepingSloth((Player) (Object) this)) {
            ci.cancel();
        }
    }
}
