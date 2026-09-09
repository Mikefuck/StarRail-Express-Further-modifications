package com.habitrain.core.client.gui;

import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.SlothSleepRosterPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Backpack replacement showing every server-authorized Sloth sleep target as a clickable head. */
public final class SlothSleepSelectScreen extends Screen {
    private static final int CELL_W = 72;
    private static final int CELL_H = 54;
    private static final int HEAD_SIZE = 32;

    private final List<SlothSleepRosterPayload.Entry> entries;
    private int page;

    public SlothSleepSelectScreen(List<SlothSleepRosterPayload.Entry> entries) {
        super(Component.translatable("screen.habitrain_core.sin_sloth.targets"));
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    @Override
    protected void init() {
        int columns = columns();
        int perPage = columns * rows();
        int maxPage = Math.max(0, (entries.size() - 1) / perPage);
        page = Math.max(0, Math.min(page, maxPage));

        int from = page * perPage;
        int to = Math.min(entries.size(), from + perPage);
        int visible = to - from;
        int usedColumns = Math.min(columns, Math.max(1, visible));
        int startX = (width - usedColumns * CELL_W) / 2;
        int startY = 56;

        for (int i = from; i < to; i++) {
            int local = i - from;
            int row = local / columns;
            int col = local % columns;
            int x = startX + col * CELL_W + (CELL_W - HEAD_SIZE) / 2;
            int y = startY + row * CELL_H;
            addRenderableWidget(new PlayerHeadButton(x, y, entries.get(i)));
        }

        int navY = height - 36;
        if (page > 0) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                page--;
                rebuildWidgets();
            }).bounds(width / 2 - 84, navY, 54, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 27, navY, 54, 20).build());
        if (page < maxPage) {
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                page++;
                rebuildWidgets();
            }).bounds(width / 2 + 30, navY, 54, 20).build());
        }
    }

    private int columns() {
        return Math.max(1, Math.min(6, Math.max(1, (width - 24) / CELL_W)));
    }

    private int rows() {
        return Math.max(1, Math.min(3, (height - 100) / CELL_H));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int panelWidth = Math.min(width - 16, columns() * CELL_W + 28);
        int left = (width - panelWidth) / 2;
        graphics.fillGradient(left, 18, left + panelWidth, height - 14,
                0xF0121020, 0xF0282038);
        graphics.renderOutline(left, 18, panelWidth, height - 32, 0xFF9381C8);
        graphics.drawCenteredString(font, title, width / 2, 27, 0xFFD8CCFF);
        graphics.drawCenteredString(font,
                Component.translatable("screen.habitrain_core.sin_sloth.targets_hint"),
                width / 2, 39, 0xFFB8AECF);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.habitrain_core.sin_sloth.no_targets"),
                    width / 2, height / 2, 0xFFB8AECF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private static final class PlayerHeadButton extends Button {
        private final SlothSleepRosterPayload.Entry entry;
        private final ResourceLocation skin;

        private PlayerHeadButton(int x, int y, SlothSleepRosterPayload.Entry entry) {
            super(x, y, HEAD_SIZE, HEAD_SIZE, Component.literal(entry.playerName()), button -> {
                PayloadSenders.sendSlothSleepTarget(entry.playerId());
                Minecraft.getInstance().setScreen(null);
            }, DEFAULT_NARRATION);
            this.entry = entry;
            this.skin = resolveSkin(entry);
            setTooltip(Tooltip.create(Component.translatable(
                    "screen.habitrain_core.sin_sloth.click_target", entry.playerName())));
        }

        private static ResourceLocation resolveSkin(SlothSleepRosterPayload.Entry entry) {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                PlayerInfo info = client.getConnection().getPlayerInfo(entry.playerId());
                if (info != null && info.getSkin() != null) {
                    return info.getSkin().texture();
                }
            }
            return DefaultPlayerSkin.get(entry.playerId()).texture();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = isHoveredOrFocused() ? 0xFFE5D8FF : 0xFF766895;
            graphics.fill(getX() - 2, getY() - 2, getX() + width + 2, getY() + height + 2,
                    isHoveredOrFocused() ? 0xCC51456B : 0xAA282238);
            graphics.renderOutline(getX() - 2, getY() - 2, width + 4, height + 4, border);
            PlayerFaceRenderer.draw(graphics, skin, getX(), getY(), HEAD_SIZE);
            Font font = Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(entry.playerName(), CELL_W - 4);
            graphics.drawCenteredString(font, label, getX() + width / 2,
                    getY() + height + 4, 0xFFFFFFFF);
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
            // Name is rendered below the face.
        }
    }
}
