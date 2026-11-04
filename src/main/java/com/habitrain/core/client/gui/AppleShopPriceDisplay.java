package com.habitrain.core.client.gui;

import com.habitrain.core.game.sre.role.shop.ApplePriceState;
import com.habitrain.core.game.sre.role.shop.AppleShopComponent;
import com.habitrain.core.game.sre.role.shop.AppleShopSignature;
import com.habitrain.core.game.sre.role.shop.SnakeFruitPricePolicy;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Shared formatting for the ticket and original inventory shop. */
public final class AppleShopPriceDisplay {
    public interface OfferAccess {
        void habitrain$rememberAppleOffer(ApplePriceState.Offer<AppleShopSignature> offer);
    }

    private AppleShopPriceDisplay() {}

    public static ApplePriceState.Quote<AppleShopSignature> quote(
            io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen.StoreItemWidget widget,
            ShopEntry entry, int basePrice) {
        var player = Minecraft.getInstance().player;
        var offer = player == null ? null : AppleShopComponent.KEY.get(player).offer(widget.index, entry, basePrice);
        ((OfferAccess) widget).habitrain$rememberAppleOffer(offer);
        return offer == null ? null : offer.quote();
    }

    public static ApplePriceState.Quote<AppleShopSignature> quote(int index, ShopEntry entry, int basePrice) {
        var player = Minecraft.getInstance().player;
        return player == null ? null : AppleShopComponent.KEY.get(player).quote(index, entry, basePrice);
    }

    public static Component change(int normalPrice, int price) {
        return Component.literal(SnakeFruitPricePolicy.changeLabel(normalPrice, price))
                .withStyle(price < normalPrice ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    public static Component amount(int normalPrice, int price) {
        return Component.literal(price + " ").append(change(normalPrice, price));
    }
}
