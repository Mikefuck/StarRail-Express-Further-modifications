package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.modifier.virtue.TemperanceVirtue;
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

/**
 * 节制：tryBuy 时把 DynamicShop.effectivePrice(entry) 替换为
 * base → temperance → DynamicShop(id, tempered)。
 * 成功购买后记录 lastPrice。
 */
@Mixin(SREPlayerShopComponent.class)
public abstract class TemperanceShopMixin {

    @Shadow @Final private Player player;

    @Unique
    private ShopEntry habitrain$pendingEntry;
    @Unique
    private int habitrain$pendingPrice = -1;

    /**
     * Replace DynamicShop.effectivePrice(ShopEntry) call inside tryBuy with temperance stack.
     */
    @Redirect(
            method = "tryBuy",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/cca/DynamicShopComponent;effectivePrice(Lio/wifi/starrailexpress/util/ShopEntry;)I",
                    remap = false
            ),
            remap = false,
            require = 1
    )
    private int habitrain$temperancePrice(DynamicShopComponent dyn, ShopEntry entry) {
        this.habitrain$pendingEntry = entry;
        int quoted = TemperanceVirtue.effectiveBuyPrice(this.player, entry);
        this.habitrain$pendingPrice = quoted;
        return quoted;
    }

    /**
     * After tryBuy returns, if temperance held and we still have pending price that matches a
     * successful debit, record last price. Detect success via balance change is fragile;
     * instead record when pending price was set and balance was reduced — simpler: always
     * record on RETURN if entry non-null and temperance and player paid (balance was enough
     * path). We re-check by seeing if entry is still set and onBuy likely succeeded by
     * comparing stored price with last known — use HEAD snapshot of balance.
     */
    @Unique
    private int habitrain$balanceBefore = Integer.MIN_VALUE;

    @Inject(method = "tryBuy", at = @At("HEAD"), remap = false)
    private void habitrain$captureBalance(int index, CallbackInfo ci) {
        this.habitrain$balanceBefore = ((SREPlayerShopComponent) (Object) this).balance;
        this.habitrain$pendingEntry = null;
        this.habitrain$pendingPrice = -1;
    }

    @Inject(method = "tryBuy", at = @At("RETURN"), remap = false)
    private void habitrain$recordTemperancePurchase(int index, CallbackInfo ci) {
        if (this.habitrain$pendingEntry == null || this.habitrain$pendingPrice < 0) return;
        if (!TemperanceVirtue.hasTemperance(this.player)) return;
        int after = ((SREPlayerShopComponent) (Object) this).balance;
        // Successful buy debits balance by quoted price (or more if other hooks); accept any decrease.
        if (this.habitrain$balanceBefore != Integer.MIN_VALUE && after < this.habitrain$balanceBefore) {
            int paid = this.habitrain$balanceBefore - after;
            TemperanceVirtue.onSuccessfulBuy(this.player, this.habitrain$pendingEntry, paid);
        }
        this.habitrain$pendingEntry = null;
        this.habitrain$pendingPrice = -1;
        this.habitrain$balanceBefore = Integer.MIN_VALUE;
    }
}
