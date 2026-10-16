package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.agmas.noellesroles.init.events.NRDeathEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(value = NRDeathEvents.class, remap = false)
public abstract class LustDeathEventsMixin {
    @Inject(method = "handleDeathPenalty(Lnet/minecraft/world/level/Level;Ljava/util/List;ZZ)V", at = @At("RETURN"))
    private static void habitrain$applyLustRestriction(Level level, List<Player> victims,
                                                       boolean ignoreDoctor, boolean ignoreLooseEnd, CallbackInfo ci) {
        LustComponent.applyDeathRestrictions(level, victims);
    }
}
