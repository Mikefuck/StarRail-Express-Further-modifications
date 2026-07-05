package com.habitrain.core.client.gui;

import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.BlackoutSheriffVotePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.UUID;

public class BlackoutSheriffVoteScreen extends Screen {
    private static final int PAD = 10;
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 220;
    private static final int ROW_H = 28;
    private static final int ROW_GAP = 4;
    private static final int SCROLLBAR_W = 4;

    private final Screen parent;
    private double scrollOffset = 0;

    public BlackoutSheriffVoteScreen(Screen parent) {
        super(Component.literal("警长投票"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("关闭"),
                btn -> onClose())
                .bounds(width / 2 - 40, height - 32, 80, 20)
                .build());
    }

    @Override
    public void tick() {
        if (!BlackoutSheriffVoteState.isActive()) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (width - PANEL_W) / 2;
        int panelY = 28;
        int listX = panelX + PAD;
        int listY = panelY + 52;
        int listW = PANEL_W - PAD * 2;
        int listH = PANEL_H - 84;

        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xE01A1520, 0xE0281F2E);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, 0xFF6B8CB3);
        g.fill(panelX + 1, panelY + 1, panelX + PANEL_W - 1, panelY + 2, 0x44D8E7FF);

        Font font = this.font;
        int sheriffCount = BlackoutSheriffVoteState.getSheriffCount();
        g.drawCenteredString(font, this.title, width / 2, panelY + 10, 0xF5F7FB);
        g.drawCenteredString(font, Component.literal("本局将选出 " + sheriffCount + " 名警长，你共有 " + sheriffCount + " 票"),
                width / 2, panelY + 24, 0xB9C7D9);

        String timer = BlackoutSheriffVoteState.isActive()
                ? "剩余时间: " + BlackoutSheriffVoteState.getRemainingSeconds() + "s"
                : "投票已结束";
        g.drawCenteredString(font, Component.literal(timer), width / 2, panelY + 36, 0xFFE6B566);

        List<BlackoutSheriffVotePayload.Entry> candidates = BlackoutSheriffVoteState.getCandidates();
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < candidates.size(); i++) {
            var entry = candidates.get(i);
            int rowY = listY + i * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (rowY + ROW_H < listY || rowY > listY + listH) continue;

            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H;
            int selectedSlot = BlackoutSheriffVoteState.isSelected(entry.playerId())
                    ? (BlackoutSheriffVoteState.getSelectedTargetIds().indexOf(entry.playerId()) + 1)
                    : 0;
            renderRow(g, entry, listX, rowY, listW, ROW_H, hovered, selectedSlot);
        }
        g.disableScissor();

        int totalHeight = candidates.isEmpty() ? 0 : candidates.size() * (ROW_H + ROW_GAP) - ROW_GAP;
        int maxScroll = Math.max(0, totalHeight - listH);
        if (maxScroll > 0) {
            int barH = Math.max(12, (int) ((float) listH * listH / totalHeight));
            int barY = listY + (int) ((listH - barH) * (scrollOffset / maxScroll));
            g.fill(listX + listW + 2, listY, listX + listW + 2 + SCROLLBAR_W, listY + listH, 0x332B3D55);
            g.fill(listX + listW + 2, barY, listX + listW + 2 + SCROLLBAR_W, barY + barH, 0xFF7E98B8);
        }
    }

    private void renderRow(GuiGraphics g, BlackoutSheriffVotePayload.Entry entry, int x, int y, int w, int h,
                           boolean hovered, int selectedSlot) {
        int border = selectedSlot > 0 ? 0xFFE6B566 : (hovered ? 0xFF8CA7C7 : 0xFF4B5F78);
        int bg = selectedSlot > 0 ? 0xFF2A2220 : (hovered ? 0xFF212A36 : 0xFF17202A);
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);

        g.drawString(font, Component.literal(entry.playerName()),
                x + 8, y + 9, 0xFFFFFF, false);
        g.drawString(font, Component.literal("票数: " + entry.votes()),
                x + w - 72, y + 9, 0xB9C7D9, false);

        if (selectedSlot > 0) {
            g.fill(x + w - 6, y + 4, x + w - 2, y + h - 4, 0xFFE6B566);
            g.drawCenteredString(font, Component.literal(String.valueOf(selectedSlot)),
                    x + w - 14, y + 9, 0xFFE6B566);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !BlackoutSheriffVoteState.isActive()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int panelX = (width - PANEL_W) / 2;
        int panelY = 28;
        int listX = panelX + PAD;
        int listY = panelY + 52;
        int listW = PANEL_W - PAD * 2;

        List<BlackoutSheriffVotePayload.Entry> candidates = BlackoutSheriffVoteState.getCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            int rowY = listY + i * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                var entry = candidates.get(i);
                UUID targetId = entry.playerId();
                boolean wasSelected = BlackoutSheriffVoteState.isSelected(targetId);
                BlackoutSheriffVoteState.toggleSelection(targetId);
                int slotIndex = BlackoutSheriffVoteState.getSelectedTargetIds().indexOf(targetId);
                if (slotIndex >= 0) {
                    PayloadSenders.sendSheriffVoteCast(targetId, slotIndex);
                } else if (wasSelected) {
                    // 取消投票：发送 slotIndex = -1 表示撤回（服务端按 slotIndex 移除该目标）
                    PayloadSenders.sendSheriffVoteCast(targetId, -1);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int panelX = (width - PANEL_W) / 2;
        int panelY = 28;
        int listX = panelX + PAD;
        int listY = panelY + 52;
        int listW = PANEL_W - PAD * 2;
        int listH = PANEL_H - 84;

        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            List<BlackoutSheriffVotePayload.Entry> candidates = BlackoutSheriffVoteState.getCandidates();
            int totalHeight = candidates.isEmpty() ? 0 : candidates.size() * (ROW_H + ROW_GAP) - ROW_GAP;
            int maxScroll = Math.max(0, totalHeight - listH);
            scrollOffset = Mth.clamp(scrollOffset - scrollY * (ROW_H + ROW_GAP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(parent);
    }
}