package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.TicketShopRenderer;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen.StoreItemWidget;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StoreItemWidget.class)
public abstract class TicketShopItemMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderTicket(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        StoreItemWidget widget = (StoreItemWidget) (Object) this;
        if (widget.screen instanceof TicketShopRenderer.ScreenAccess access
                && access.habitrain$renderShopItem(widget, graphics, mouseX, mouseY)) {
            ci.cancel();
        }
    }
}
