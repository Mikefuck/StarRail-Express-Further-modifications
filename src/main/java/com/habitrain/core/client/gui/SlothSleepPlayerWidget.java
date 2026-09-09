package com.habitrain.core.client.gui;

import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.SlothSleepRosterPayload;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;

/** Voodoo-sized head and shop slot embedded directly in the existing backpack. */
public final class SlothSleepPlayerWidget extends Button {
    private final SlothSleepRosterPayload.Entry entry;

    public SlothSleepPlayerWidget(int x, int y, SlothSleepRosterPayload.Entry entry) {
        super(x, y, 16, 16, Component.literal(entry.playerName()), button -> {
            button.active = false;
            PayloadSenders.sendSlothSleepTarget(entry.playerId());
        }, DEFAULT_NARRATION);
        this.entry = entry;
        setTooltip(Tooltip.create(Component.translatable(
                "screen.habitrain_core.sin_sloth.click_target", entry.playerName())));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var client = Minecraft.getInstance();
        var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(entry.playerId());
        var skin = info == null ? DefaultPlayerSkin.get(entry.playerId()) : info.getSkin();
        graphics.setColor(active ? 1F : 0.25F, active ? 1F : 0.25F, active ? 1F : 0.25F, 1F);
        graphics.blitSprite(ShopEntry.Type.TOOL.getTexture(), getX() - 7, getY() - 7, 30, 30);
        PlayerFaceRenderer.draw(graphics, skin.texture(), getX(), getY(), 16);
        graphics.setColor(1F, 1F, 1F, 1F);
        if (isHoveredOrFocused()) {
            graphics.fill(getX(), getY(), getX() + 16, getY() + 16, 0x90FFFFFF);
        }
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {}
}