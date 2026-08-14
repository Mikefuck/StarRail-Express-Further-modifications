package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import io.wifi.starrailexpress.cca.DynamicShopComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridges {@code SREPlayerShopComponent.tryBuy} into the v2 dispatcher.
 *
 * <p>{@code canBuy} is redirected so a {@code DENY} from {@code allowBuy}
 * falls through the existing fail path. A successful debit (balance drop)
 * fires {@code onBuy} / {@code onAnyBuy}.
 */
@Mixin(SREPlayerShopComponent.class)
public abstract class RoleShopHookMixin {

    @Shadow @Final private Player player;

    @Unique private ShopEntry habitrain$v2Entry;
    @Unique private int habitrain$v2Index = -1;
    @Unique private int habitrain$v2BalanceBefore = Integer.MIN_VALUE;

    @Inject(method = "tryBuy", at = @At("HEAD"), remap = false)
    private void habitrain$v2CaptureBuy(int index, CallbackInfo ci) {
        this.habitrain$v2Index = index;
        this.habitrain$v2Entry = null;
        this.habitrain$v2BalanceBefore = ((SREPlayerShopComponent) (Object) this).balance;
    }

    @Redirect(
            method = "tryBuy",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/util/ShopEntry;canBuy(Lnet/minecraft/world/entity/player/Player;)Z",
                    remap = false
            ),
            remap = false
    )
    private boolean habitrain$v2GateBuy(ShopEntry entry, Player buyer) {
        this.habitrain$v2Entry = entry;
        if (!entry.canBuy(buyer)) {
            return false;
        }
        int price = 0;
        try {
            // The upstream debit uses DynamicShopComponent.effectivePrice(entry),
            // not the base ShopEntry.price(); price-gated hooks must see the
            // same number the balance is actually debited by.
            price = DynamicShopComponent.KEY.get(buyer).effectivePrice(entry);
        } catch (Throwable ignored) {
            try {
                price = entry.price();
            } catch (Throwable ignored2) {
            }
        }
        if (RoleEventDispatcher.INSTANCE.gateBuy(buyer, entry, this.habitrain$v2Index, price) == Decision.DENY) {
            try {
                entry.setFailedMessage(Component.translatable("message.tip.purchase_failed"));
            } catch (Throwable ignored) {
            }
            return false;
        }
        return true;
    }

    @Inject(method = "tryBuy", at = @At("RETURN"), remap = false)
    private void habitrain$v2NotifyBuy(int index, CallbackInfo ci) {
        if (this.habitrain$v2BalanceBefore == Integer.MIN_VALUE) {
            return;
        }
        int after = ((SREPlayerShopComponent) (Object) this).balance;
        if (after < this.habitrain$v2BalanceBefore) {
            int paid = this.habitrain$v2BalanceBefore - after;
            RoleEventDispatcher.INSTANCE.notifyBuy(this.player, this.habitrain$v2Entry, index, paid);
        }
        this.habitrain$v2Entry = null;
        this.habitrain$v2Index = -1;
        this.habitrain$v2BalanceBefore = Integer.MIN_VALUE;
    }
}
