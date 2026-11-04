package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.modifier.virtue.TemperanceVirtue;
import com.habitrain.core.game.sre.role.shop.AppleShopComponent;
import io.wifi.starrailexpress.cca.DynamicShopComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Normal price = base → temperance → DynamicShop; red apples override only eligible coin quotes. */
@Mixin(SREPlayerShopComponent.class)
public abstract class TemperanceShopMixin {
    @Shadow @Final private Player player;
    @Unique private int habitrain$shopIndex = -1;

    @Inject(method = "tryBuy", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$captureShopIndex(int index, CallbackInfo ci) {
        this.habitrain$shopIndex = index;
        if (!AppleShopComponent.KEY.get(player).canPurchase(index)) ci.cancel();
    }

    @Redirect(method = "tryBuy", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/cca/DynamicShopComponent;effectivePrice(Lio/wifi/starrailexpress/util/ShopEntry;)I",
            remap = false), remap = false, require = 1)
    private int habitrain$temperancePrice(DynamicShopComponent dyn, ShopEntry entry) {
        return AppleShopComponent.effectivePrice(this.player, this.habitrain$shopIndex, entry,
                TemperanceVirtue.effectiveBuyPrice(this.player, entry));
    }
}
