package com.habitrain.core.client.gui;

import com.habitrain.core.network.GreedTradeActionPayload;
import com.habitrain.core.network.GreedTradePromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Anonymous, double-confirm trade dialog. The partner identity is never inferred client-side. */
public final class GreedTradePromptScreen extends Screen {
    private final Screen parent;
    private final GreedTradePromptPayload offer;
    private boolean answered;

    public GreedTradePromptScreen(Screen parent, GreedTradePromptPayload offer) {
        super(Component.translatable("screen.habitrain_core.greed_trade.title"));
        this.parent = parent;
        this.offer = offer;
    }

    @Override
    protected void init() {
        int x = width / 2;
        int y = height / 2 + 35;
        addRenderableWidget(Button.builder(
                Component.translatable("screen.habitrain_core.greed_trade.confirm"),
                button -> answer("confirm"))
                .bounds(x - 104, y, 100, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.habitrain_core.greed_trade.cancel"),
                button -> answer("cancel"))
                .bounds(x + 4, y, 100, 20).build());
    }

    private void answer(String action) {
        if (answered) return;
        answered = true;
        ClientPlayNetworking.send(new GreedTradeActionPayload(action, offer.sessionId()));
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        if (!answered) answer("cancel");
        else if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 140;
        int top = height / 2 - 70;
        graphics.fillGradient(left, top, left + 280, top + 140, 0xEE17130E, 0xEE2A2114);
        graphics.renderOutline(left, top, 280, 140, 0xFFD6A84B);
        graphics.drawCenteredString(font, title, width / 2, top + 14, 0xFFFFD977);
        graphics.drawCenteredString(font,
                Component.translatable("screen.habitrain_core.greed_trade.partner", offer.partnerLabel()),
                width / 2, top + 39, 0xFFE7E0D2);
        graphics.drawCenteredString(font,
                Component.translatable("screen.habitrain_core.greed_trade.offer",
                        offer.side(), offer.itemId(), offer.price()),
                width / 2, top + 58, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("screen.habitrain_core.greed_trade.double_confirm"),
                width / 2, top + 78, 0xFFB8AD99);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
