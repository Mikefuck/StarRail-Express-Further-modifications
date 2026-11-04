package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.game.sre.modifier.virtue.TemperanceVirtue;
import com.habitrain.core.game.sre.role.shop.AppleShopComponent;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges purchases into v2 hooks, including successful zero-coin apple purchases. */
@Mixin(SREPlayerShopComponent.class)
public abstract class RoleShopHookMixin {
    @Shadow @Final private Player player;
    @Unique private ShopEntry habitrain$v2Entry;
    @Unique private int habitrain$v2Index = -1;
    @Unique private int habitrain$v2Price;
    @Unique private boolean habitrain$v2Succeeded;

    @Inject(method = "tryBuy", at = @At("HEAD"), remap = false)
    private void habitrain$v2CaptureBuy(int index, CallbackInfo ci) {
        this.habitrain$v2Index = index;
        this.habitrain$v2Entry = null;
        this.habitrain$v2Price = 0;
        this.habitrain$v2Succeeded = false;
    }

    @Redirect(method = "tryBuy", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/util/ShopEntry;canBuy(Lnet/minecraft/world/entity/player/Player;)Z",
            remap = false), remap = false)
    private boolean habitrain$v2GateBuy(ShopEntry entry, Player buyer) {
        this.habitrain$v2Entry = entry;
        if (!entry.canBuy(buyer)) return false;
        int price = AppleShopComponent.effectivePrice(buyer, this.habitrain$v2Index, entry,
                TemperanceVirtue.effectiveBuyPrice(buyer, entry));
        this.habitrain$v2Price = price;
        if (RoleEventDispatcher.INSTANCE.gateBuy(buyer, entry, this.habitrain$v2Index, price) == Decision.DENY) {
            entry.setFailedMessage(Component.translatable("message.tip.purchase_failed"));
            return false;
        }
        return true;
    }

    @Inject(method = "tryBuy", at = @At(value = "FIELD",
            target = "Lio/wifi/starrailexpress/cca/SREPlayerShopComponent;total_cost:I",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 1, remap = false)
    private void habitrain$v2CapturePurchaseSuccess(CallbackInfo ci) {
        this.habitrain$v2Succeeded = true;
    }

    @Inject(method = "tryBuy", at = @At("RETURN"), remap = false)
    private void habitrain$v2NotifyBuy(int index, CallbackInfo ci) {
        if (this.habitrain$v2Succeeded) {
            // Balance-delta detection misses free purchases and entries that grant coins themselves.
            TemperanceVirtue.onSuccessfulBuy(this.player, this.habitrain$v2Entry, this.habitrain$v2Price);
            RoleEventDispatcher.INSTANCE.notifyBuy(this.player, this.habitrain$v2Entry, index, this.habitrain$v2Price);
        }
        this.habitrain$v2Entry = null;
        this.habitrain$v2Index = -1;
        this.habitrain$v2Succeeded = false;
    }
}
