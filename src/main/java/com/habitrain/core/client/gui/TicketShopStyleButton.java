package com.habitrain.core.client.gui;

import com.habitrain.core.client.config.ClientVisualPreferences;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Local shop-style action rendered as a stamp inside the in-game album. */
public final class TicketShopStyleButton extends Button {
    static final String KEY = "inventory.habitrain_core.ticket.shop.style";
    private final Runnable onChanged;

    public TicketShopStyleButton(Runnable onChanged) {
        super(0, 0, 0, 0, Component.empty(),
                button -> ((TicketShopStyleButton) button).toggleStyle(), DEFAULT_NARRATION);
        this.onChanged = onChanged;
        refreshLabel();
    }

    private void toggleStyle() {
        boolean decorated = ClientVisualPreferences.isDecoratedShopEnabled();
        if (ClientVisualPreferences.setDecoratedShopEnabled(!decorated)) {
            refreshLabel();
            onChanged.run();
        } else {
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(
                    Component.translatable(KEY + ".save_failed").withStyle(ChatFormatting.RED), true);
        }
    }

    private void refreshLabel() {
        setMessage(Component.translatable(KEY + (ClientVisualPreferences.isDecoratedShopEnabled()
                ? ".decorated" : ".original")));
    }

    Component description() {
        return Component.empty().append(getMessage()).append("\n")
                .append(Component.translatable(KEY + ".hint"));
    }
}
