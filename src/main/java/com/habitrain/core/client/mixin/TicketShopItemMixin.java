package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.AppleShopPriceDisplay;
import com.habitrain.core.client.gui.TicketShopRenderer;
import com.habitrain.core.game.sre.role.shop.ApplePriceState;
import com.habitrain.core.game.sre.role.shop.AppleShopSignature;
import com.habitrain.core.network.AppleShopBuyPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import io.wifi.starrailexpress.cca.DynamicShopComponent;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen.StoreItemWidget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(StoreItemWidget.class)
public abstract class TicketShopItemMixin extends Button implements AppleShopPriceDisplay.OfferAccess {
    @Unique private ApplePriceState.Quote<AppleShopSignature> habitrain$applePrice;
    @Unique private ApplePriceState.Offer<AppleShopSignature> habitrain$renderedOffer;

    protected TicketShopItemMixin(int x, int y, int width, int height, Component message,
                                  OnPress onPress, CreateNarration narration) {
        super(x, y, width, height, message, onPress, narration);
    }

    @Override public void habitrain$rememberAppleOffer(ApplePriceState.Offer<AppleShopSignature> offer) {
        habitrain$renderedOffer = offer;
    }

    @Override public void onPress() {
        if (habitrain$renderedOffer == null) {
            super.onPress();
            return;
        }
        StoreItemWidget widget = (StoreItemWidget) (Object) this;
        ClientPlayNetworking.send(new AppleShopBuyPayload(widget.index,
                habitrain$renderedOffer.revision(), habitrain$renderedOffer.frame()));
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderTicket(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        habitrain$applePrice = null;
        habitrain$renderedOffer = null;
        StoreItemWidget widget = (StoreItemWidget) (Object) this;
        if (widget.screen instanceof TicketShopRenderer.ScreenAccess access
                && access.habitrain$renderShopItem(widget, graphics, mouseX, mouseY)) {
            ci.cancel();
        }
    }

    @Redirect(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/cca/DynamicShopComponent;effectivePrice(Lnet/minecraft/resources/ResourceLocation;I)I",
            remap = false))
    private int habitrain$applePrice(DynamicShopComponent dynamic, ResourceLocation item, int base) {
        StoreItemWidget widget = (StoreItemWidget) (Object) this;
        var entries = widget.screen.getShopEntries();
        var entry = widget.index >= 0 && widget.index < entries.size() ? entries.get(widget.index) : widget.entry;
        habitrain$applePrice = AppleShopPriceDisplay.quote(widget, entry, base);
        return habitrain$applePrice == null ? dynamic.effectivePrice(item, base) : habitrain$applePrice.price();
    }

    @Redirect(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V"))
    private void habitrain$inlinePriceChange(GuiGraphics graphics, Font font, List<Component> lines, int x, int y) {
        if (habitrain$applePrice == null) {
            graphics.renderComponentTooltip(font, lines, x, y);
            return;
        }
        Component amount = AppleShopPriceDisplay.amount(habitrain$applePrice.normalPrice(), habitrain$applePrice.price());
        StoreItemWidget widget = (StoreItemWidget) (Object) this;
        int width = font.width(amount);
        float scale = Math.min(1.0f, 32.0f / (width + 6));
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(widget.getX() + 8 - width * scale / 2, widget.getY() - 14, 400);
            graphics.pose().scale(scale, scale, 1);
            graphics.fill(-2, -2, width + 2, font.lineHeight + 2, 0xE0100010);
            graphics.drawString(font, amount, 0, 0, 0xFFFFFFFF);
        } finally {
            graphics.pose().popPose();
        }
    }
}
