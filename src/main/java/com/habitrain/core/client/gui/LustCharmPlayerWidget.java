package com.habitrain.core.client.gui;

import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.network.LustActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;

/** Existing backpack player tiles, with selected and dead states. */
public final class LustCharmPlayerWidget extends Button {
    private final LustComponent.CharmEntry entry;
    private final boolean selected;

    public LustCharmPlayerWidget(int x, int y, LustComponent.CharmEntry entry) {
        super(x, y, 16, 16, Component.literal(entry.name()), button -> {
            ClientPlayNetworking.send(new LustActionPayload(LustActionPayload.SELECT, entry.id()));
            button.active = false;
        }, DEFAULT_NARRATION);
        this.entry = entry;
        var state = LustComponent.KEY.get(Minecraft.getInstance().player);
        selected = entry.id().equals(state.selected());
        active = entry.alive() && !selected && state.selectionTicksLeft() == 0;
        setTooltip(Tooltip.create(Component.translatable("screen.habitrain_core.sin_lust.target",
                entry.name(), Component.translatable(!entry.online() ? "screen.habitrain_core.sin_lust.offline"
                        : !entry.alive() ? "screen.habitrain_core.sin_lust.dead"
                        : selected ? "screen.habitrain_core.sin_lust.chosen" : "screen.habitrain_core.sin_lust.choose"))));
    }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var client = Minecraft.getInstance();
        var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(entry.id());
        var skin = info == null ? DefaultPlayerSkin.get(entry.id()) : info.getSkin();
        graphics.fill(getX() - 3, getY() - 3, getX() + 19, getY() + 19,
                selected ? 0xFFEF72B5 : entry.alive() ? 0xFF5D314C : 0xFF424242);
        graphics.setColor(entry.alive() ? 1F : 0.3F, entry.alive() ? 1F : 0.3F, entry.alive() ? 1F : 0.3F, 1F);
        PlayerFaceRenderer.draw(graphics, skin.texture(), getX(), getY(), 16);
        graphics.setColor(1F, 1F, 1F, 1F);
        if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getX() + 16, getY() + 16, 0x70FFFFFF);
    }
    @Override public void renderString(GuiGraphics graphics, Font font, int color) {}
}
