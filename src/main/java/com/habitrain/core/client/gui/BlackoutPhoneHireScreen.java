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
    private static final int PANEL_H = 180;

    private static final int HIRE_COST = 50;

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
        this.statusText = Component.empty(); // 清除之前的请求状态
        if (hireButton != null) {
            boolean canHire = canHire();
            hireButton.active = canHire;
            hireButton.setMessage(Component.literal(canHire ? "§e拨打110" : "§7拨打110"));
        }
    }

    /**
     * 服务端聘请结果回执。
     * 客户端据此更新 statusText 显示成功/失败信息，不再卡在"正在请求..."。
     */
    public void onHireResult(com.habitrain.core.network.BlackoutHireResultPayload result) {
        if (result.success()) {
            statusText = Component.literal("§a聘请成功");
        } else {
            statusText = Component.literal("§c聘请失败: " + result.reason());
        }
        // 恢复按钮可用状态
        if (hireButton != null) {
            boolean canHire = canHire();
            hireButton.active = canHire;
            hireButton.setMessage(Component.literal(canHire ? "§e拨打110" : "§7拨打110"));
        }
    }

    /** 综合判断是否可以聘请（无开局解锁 CD） */
    private boolean canHire() {
        if (state.hasHiredThisGame()) return false;
        if (state.balance() < HIRE_COST) return false;
        if (state.killerCount() <= 0) return false;
        if (state.sheriffCount() + 1 > state.killerCount()) return false;
        return true;
    }

    /** 获取不能聘请的原因文本 */
    private Component getHireDisabledReason() {
        if (state.hasHiredThisGame()) return Component.literal("§7你本局已经拨打过110");
        if (state.balance() < HIRE_COST) return Component.literal("§c话费不足，需要 " + HIRE_COST + " 金币");
        if (state.killerCount() <= 0) return Component.literal("§c当前没有杀手，无需聘请警察");
        if (state.sheriffCount() + 1 > state.killerCount()) return Component.literal("§c当前警力已足够，无法继续聘请");
        // 可聘请：费用固定显示在按钮下方，此处不再重复
        return Component.empty();
    }

    @Override
    protected void init() {
        super.init();
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        boolean hireEnabled = canHire();
        hireButton = addRenderableWidget(Button.builder(
                Component.literal(hireEnabled ? "§e拨打110" : "§7拨打110"),
                btn -> {
                    if (canHire()) {
                        com.habitrain.core.client.network.PayloadSenders.sendHirePolice();
                        statusText = Component.literal("§a正在请求...");
                        btn.active = false; // 防止重复点击
                    }
                })
                .bounds(panelX + 50, panelY + 60, PANEL_W - 100, 30)
                .build());
        hireButton.active = hireEnabled;

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

        // 失败原因（可聘请时为空，费用固定显示在按钮下方）
        Component reason = getHireDisabledReason();
        g.drawCenteredString(font, reason, width / 2, panelY + 40, 0xB9C7D9);

        // 按钮下方固定常驻费用提示（不论可否聘请都显示）
        g.drawCenteredString(font, Component.literal("§7花费" + HIRE_COST + "话费拨打110"),
                width / 2, panelY + 100, 0xB9C7D9);

        // 警察/杀手数
        g.drawCenteredString(font, Component.literal(
                "§7当前警察: " + state.sheriffCount() + "  §7杀手: " + state.killerCount()),
                width / 2, panelY + 122, 0xB9C7D9);

        // 状态反馈（如"正在请求..."）
        if (!statusText.getString().isEmpty()) {
            g.drawCenteredString(font, statusText, width / 2, panelY + 144, 0xFFFFFF);
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
