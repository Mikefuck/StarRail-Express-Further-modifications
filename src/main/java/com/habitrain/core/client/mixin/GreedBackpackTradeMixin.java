package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.GreedTradeSelectScreen;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Greed's anonymous player selector to the standard backpack screen. */
@Mixin(LimitedInventoryScreen.class)
public abstract class GreedBackpackTradeMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$addGreedTradeButton(CallbackInfo ci) {
        LimitedInventoryScreen self = (LimitedInventoryScreen) (Object) this;
        if (self.player == null) return;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(self.player.level());
            if (game == null || SevenSins.GREED == null || !game.isRole(self.player, SevenSins.GREED)) return;
        } catch (Throwable ignored) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Button button = Button.builder(
                Component.translatable("screen.habitrain_core.greed_trade.select_title"),
                pressed -> client.setScreen(new GreedTradeSelectScreen((Screen) (Object) this)))
                .bounds(6, client.getWindow().getGuiScaledHeight() - 46, 110, 20)
                .build();
        ((ScreenWidgetAccessor) (Object) this).habitrain$addRenderableWidget(button);
    }
}
