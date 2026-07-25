package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import io.wifi.starrailexpress.api.impl.KnifeChargeableItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 卖花女被动：持有花束的玩家蓄力 +0.3s（+6 tick）。
 */
@Mixin(value = KnifeChargeableItem.class, remap = false)
public class BouquetChargeMixin {

    @Inject(method = "getMaxChargeTime", at = @At("RETURN"), cancellable = true, remap = false)
    private void habitrain$bouquetCharge(ItemStack stack, Player player, CallbackInfoReturnable<Integer> cir) {
        if (player != null && HabiRoleItems.playerHasBouquet(player)) {
            cir.setReturnValue(cir.getReturnValueI() + 6);
        }
    }
}
