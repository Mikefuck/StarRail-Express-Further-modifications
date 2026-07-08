package com.habitrain.core.client.gui;

import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class BlackoutPhoneHireScreen extends Screen {
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 160;

    private final Screen parent;
    private BlackoutPhoneOpenPayload state;
    private Button hireButton;
    private Component statusText = Component.empty();

    public BlackoutPhoneHireScreen(Screen parent, BlackoutPhoneOpenPayload state) {
        super(Component.literal("电话聘请警察"));
        this.parent = parent;
        this.state = state;
    }

    /** 服务端推送了最新状态时调用 */
    public void updateState(BlackoutPhoneOpenPayload newState) {
        this.state = newState;
        if (hireButton != null) {
            boolean canHire = state.unlocked() && !state.hasHiredThisGame();
            hireButton.active = canHire;
            hireButton.setMessage(Component.literal(canHire ? "§e拨打110" : "§7拨打110"));
        }
    }

    @Override
    protected void init() {
        super.init();
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        hireButton = addRenderableWidget(Button.builder(
                Component.literal(state.unlocked() && !state.hasHiredThisGame() ? "§e拨打110" : "§7拨打110"),
                btn -> {
                    if (state.unlocked() && !state.hasHiredThisGame()) {
                        com.habitrain.core.client.network.PayloadSenders.sendHirePolice();
                        statusText = Component.literal("§a正在请求...");
                    }
                })
                .bounds(panelX + 50, panelY + 60, PANEL_W - 100, 30)
                .build());
        hireButton.active = state.unlocked() && !state.hasHiredThisGame();

        addRenderableWidget(Button.builder(Component.literal("关闭"),
                btn -> onClose())
                .bounds(width / 2 - 40, height - 32, 80, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xE01A1520, 0xE0281F2E);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, 0xFF6B8CB3);
        g.fill(panelX + 1, panelY + 1, panelX + PANEL_W - 1, panelY + 2, 0x44D8E7FF);

        Font font = this.font;
        g.drawCenteredString(font, "§6电话聘请警察", width / 2, panelY + 12, 0xF5F7FB);

        // 状态信息
        String info;
        if (state.hasHiredThisGame()) {
            info = "§7你本局已经拨打过110";
        } else if (!state.unlocked()) {
            info = "§7报警线路尚未接通（剩余 " + state.remainingLockSeconds() + " 秒）";
        } else {
            info = "§7花费 §e" + (state.balance() >= 300 ? 300 : state.balance()) + " §7话费拨打110";
            if (state.balance() < 300) {
                info = "§c话费不足（需要300）";
            }
        }
        g.drawCenteredString(font, Component.literal(info), width / 2, panelY + 40, 0xB9C7D9);

        // 警察/杀手数
        g.drawCenteredString(font, Component.literal(
                "§7当前警察: " + state.sheriffCount() + "  §7杀手: " + state.killerCount()),
                width / 2, panelY + 100, 0xB9C7D9);

        // 状态反馈
        if (!statusText.getString().isEmpty()) {
            g.drawCenteredString(font, statusText, width / 2, panelY + 120, 0xFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
