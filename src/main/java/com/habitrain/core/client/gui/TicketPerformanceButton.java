package com.habitrain.core.client.gui;

import com.habitrain.core.client.config.ClientVisualPreferences;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Local action shared by the inventory stamp and the in-game album; never drawn as a standalone button. */
public final class TicketPerformanceButton extends Button {
    static final String KEY = "inventory.habitrain_core.ticket.mode";

    public TicketPerformanceButton() {
        super(0, 0, 0, 0, Component.empty(),
                button -> ((TicketPerformanceButton) button).toggleMode(), DEFAULT_NARRATION);
        refreshLabel();
    }

    public void toggleMode() {
        boolean enabled = ClientVisualPreferences.isLightweightInventory();
        if (!ClientVisualPreferences.setLightweightInventory(!enabled)) {
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(
                    Component.translatable("inventory.habitrain_core.ticket.mode.save_failed").withStyle(ChatFormatting.RED), true);
        }
        refreshLabel();
    }

    private void refreshLabel() {
        boolean light = ClientVisualPreferences.isLightweightInventory();
        setMessage(Component.translatable("inventory.habitrain_core.ticket.mode." + (light ? "light" : "full")));
    }

    Component description() {
        return Component.empty().append(getMessage()).append("\n")
                .append(Component.translatable("inventory.habitrain_core.ticket.mode.hint"));
    }
}
