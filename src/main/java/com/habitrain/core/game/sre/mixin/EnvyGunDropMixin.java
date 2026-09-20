package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.util.BrokenGunDropUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Envy's victims keep normal gun drops even when the server enables broken gun drops. */
@Mixin(BrokenGunDropUtils.class)
public abstract class EnvyGunDropMixin {
    @Inject(method = "shouldBreakVictimGunOnKillerKill", at = @At("HEAD"), cancellable = true)
    private static void habitrain$keepNormalGun(SREGameWorldComponent world, Player victim,
                                                Player killer, ItemStack stack,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (killer != null && HabiRoles.isHabiRole(killer, SevenSins.ENVY)) {
            cir.setReturnValue(false);
        }
    }
}
