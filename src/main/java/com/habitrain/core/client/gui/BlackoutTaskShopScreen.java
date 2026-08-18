package com.habitrain.core.client.gui;

import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.BlackoutTaskShopOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 停电任务商店 GUI（放逐页风格布局：左任务名、右价格）。
 * 右键 decocraft:rotary_phone_red 打开。点击条目 → C2S 购买。
 */
public class BlackoutTaskShopScreen extends Screen {
    private static final int PAD = 10;
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 220;
    private static final int ROW_H = 28;
    private static final int ROW_GAP = 4;
    private static final int SCROLLBAR_W = 4;

    private static final Component CLOSE_BUTTON_TEXT = Component.literal("关闭");
    private final Screen parent;
    private double scrollOffset = 0;
    private Component statusText = Component.empty();

    public BlackoutTaskShopScreen(Screen parent) {
        super(Component.literal("任务商店"));
        this.parent = parent;
    }

    public void updateState(BlackoutTaskShopOpenPayload payload) {
        BlackoutTaskShopState.update(payload);
        statusText = Component.empty();
    }

    public void onPurchaseResult(boolean success, String reason) {
        statusText = success
                ? Component.literal("§a购买成功")
                : Component.literal("§c购买失败: " + reason);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(CLOSE_BUTTON_TEXT,
                btn -> onClose())
                .bounds(width / 2 - 40, height - 32, 80, 20)
                .build());
    }

    @Override
    public void tick() {
        super.tick();
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
        g.drawCenteredString(font, this.title, width / 2, panelY + 10, 0xF5F7FB);
        g.drawCenteredString(font, Component.literal("金币余额: " + BlackoutTaskShopState.getBalance()),
                width / 2, panelY + 24, 0xB9C7D9);

        List<BlackoutTaskShopOpenPayload.Entry> entries = BlackoutTaskShopState.getEntries();
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            int rowY = listY + i * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (rowY + ROW_H < listY || rowY > listY + listH) continue;
            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            renderRow(g, entry, listX, rowY, listW, ROW_H, hovered);
        }
        g.disableScissor();

        int totalHeight = entries.isEmpty() ? 0 : entries.size() * (ROW_H + ROW_GAP) - ROW_GAP;
        int maxScroll = Math.max(0, totalHeight - listH);
        // 刷新后 entries 可能减少，clamp 防止 scrollOffset 越界
        scrollOffset = Math.min(scrollOffset, maxScroll);
        if (maxScroll > 0) {
            int barH = Math.max(12, (int) ((float) listH * listH / totalHeight));
            int barY = listY + (int) ((listH - barH) * (scrollOffset / maxScroll));
            g.fill(listX + listW + 2, listY, listX + listW + 2 + SCROLLBAR_W, listY + listH, 0x332B3D55);
            g.fill(listX + listW + 2, barY, listX + listW + 2 + SCROLLBAR_W, barY + barH, 0xFF7E98B8);
        }

        if (!statusText.getString().isEmpty()) {
            g.drawCenteredString(font, statusText, width / 2, panelY + PANEL_H - 18, 0xFFFFFF);
        }
    }

    private void renderRow(GuiGraphics g, BlackoutTaskShopOpenPayload.Entry entry, int x, int y, int w, int h,
                           boolean hovered) {
        boolean affordable = entry.affordable();
        int border = affordable ? (hovered ? 0xFF8CA7C7 : 0xFF4B5F78) : 0xFF555555;
        int bg = affordable ? (hovered ? 0xFF212A36 : 0xFF17202A) : 0xFF1A1F26;
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);

        int nameColor = affordable ? 0xFFFFFF : 0xFF888888;
        g.drawString(font, Component.literal(entry.displayName()), x + 8, y + 9, nameColor, false);
        int priceColor = affordable ? 0xFFE6B566 : 0xFF888888;
        g.drawString(font, Component.literal("价格: " + entry.price()), x + w - 80, y + 9, priceColor, false);

        if (!affordable && !entry.lockedReason().isEmpty()) {
            g.drawString(font, Component.literal("§7" + entry.lockedReason()), x + 8, y + 19, 0xFF999999, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int panelX = (width - PANEL_W) / 2;
        int panelY = 28;
        int listX = panelX + PAD;
        int listY = panelY + 52;
        int listW = PANEL_W - PAD * 2;

        List<BlackoutTaskShopOpenPayload.Entry> entries = BlackoutTaskShopState.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            int rowY = listY + i * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                var entry = entries.get(i);
                if (entry.affordable()) {
                    PayloadSenders.sendTaskShopBuy(entry.key());
                    statusText = Component.literal("§e正在购买...");
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
            List<BlackoutTaskShopOpenPayload.Entry> entries = BlackoutTaskShopState.getEntries();
            int totalHeight = entries.isEmpty() ? 0 : entries.size() * (ROW_H + ROW_GAP) - ROW_GAP;
            int maxScroll = Math.max(0, totalHeight - listH);
            scrollOffset = Mth.clamp(scrollOffset - scrollY * (ROW_H + ROW_GAP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
