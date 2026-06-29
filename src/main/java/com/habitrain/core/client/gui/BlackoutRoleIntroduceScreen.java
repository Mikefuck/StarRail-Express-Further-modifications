package com.habitrain.core.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 停电模式 — 角色介绍 GUI。
 * 纯客户端，展示 Blackout 模式的 3 种角色（平民/杀手/警长）。
 * 按 U 键打开（仅在 Blackout 模式激活时）。
 */
public class BlackoutRoleIntroduceScreen extends Screen {

    // ====== 角色数据 ======

    private record RoleInfo(
        String name,           // 角色名
        String faction,        // 阵营
        String goal,           // 目标
        String ability,        // 能力
        String description,    // 描述
        int color              // 主题色
    ) {}

    private static final List<RoleInfo> ROLES = List.of(
        new RoleInfo(
            "平民", "好人", "存活到最后，在停电中生存",
            "无特殊能力",
            "普通乘客。通过完成任务获得金币，\n注意观察周围玩家的异常行为。",
            0x55FF55  // 绿色
        ),
        new RoleInfo(
            "杀手", "坏人", "消灭所有好人",
            "可在商店购买 TACZ 沙漠之鹰击杀平民",
            "隐藏在人群中的杀手。利用停电\n掩护行动，但注意不要暴露身份。",
            0xFF5555  // 红色
        ),
        new RoleInfo(
            "警长", "好人", "找出并消灭杀手",
            "通过投票选出，可购买 TACZ 武器",
            "唯一可以击杀杀手的好人。利用\n投票环节争取支持，谨慎选择目标。",
            0xFFFF55  // 黄色
        )
    );

    // ====== GUI 布局常量 ======
    private static final int GUI_WIDTH = 320;
    private static final int GUI_HEIGHT = 200;
    private static final int LEFT_PANEL_W = 80;
    private static final int RIGHT_PANEL_W = GUI_WIDTH - LEFT_PANEL_W - 20;
    private static final int CARD_H = 24;
    private static final int CARD_GAP = 4;
    private static final int TOP_BAR_H = 20;

    private int guiLeft, guiTop;
    private int selectedIndex = 0;

    public BlackoutRoleIntroduceScreen() {
        super(Component.literal("停电模式角色介绍"));
    }

    @Override
    protected void init() {
        super.init();
        this.guiLeft = (this.width - GUI_WIDTH) / 2;
        this.guiTop = (this.height - GUI_HEIGHT) / 2;
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);

        int x = guiLeft;
        int y = guiTop;

        // 绘制背景
        gui.fill(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xC0101010);    // 半透明背景
        gui.fill(x, y, x + GUI_WIDTH, y + TOP_BAR_H, 0xD0333333);     // 顶栏

        // 标题
        gui.drawString(
            Minecraft.getInstance().font,
            "⚡ 停电模式 · 角色介绍",
            x + 8, y + 5, 0xFFFFFF
        );

        y += TOP_BAR_H;

        // 左侧角色列表
        int leftY = y + 8;
        for (int i = 0; i < ROLES.size(); i++) {
            RoleInfo role = ROLES.get(i);
            int cardX = x + 8;
            int cardY = leftY + i * (CARD_H + CARD_GAP);

            boolean hovered = mouseX >= cardX && mouseX <= cardX + LEFT_PANEL_W
                           && mouseY >= cardY && mouseY <= cardY + CARD_H;
            boolean selected = i == selectedIndex;

            // 卡片背景
            int bgColor = selected ? 0xD0 + (role.color() & 0x00FFFFFF) : 0xC0444444;
            if (hovered && !selected) bgColor = 0xC0555555;
            gui.fill(cardX, cardY, cardX + LEFT_PANEL_W, cardY + CARD_H, bgColor);

            // 角色名
            gui.drawString(
                Minecraft.getInstance().font,
                role.name(),
                cardX + 4, cardY + (CARD_H - 9) / 2,
                selected ? role.color() : 0xCCCCCC
            );
        }

        // 右侧详情面板
        int rightX = x + LEFT_PANEL_W + 16;
        int rightY = y + 8;
        int rightW = RIGHT_PANEL_W;

        if (selectedIndex >= 0 && selectedIndex < ROLES.size()) {
            RoleInfo selectedRole = ROLES.get(selectedIndex);

            // 分隔线
            gui.fill(rightX, rightY, rightX + rightW, rightY + 1, selectedRole.color());

            int textY = rightY + 8;
            int lineHeight = 10;

            // 角色名
            gui.drawString(
                Minecraft.getInstance().font,
                selectedRole.name(),
                rightX, textY, selectedRole.color()
            );
            textY += lineHeight + 4;

            // 阵营
            gui.drawString(
                Minecraft.getInstance().font,
                "§7阵营: " + selectedRole.faction(),
                rightX, textY, 0xCCCCCC
            );
            textY += lineHeight + 2;

            // 目标
            gui.drawString(
                Minecraft.getInstance().font,
                "§7目标: " + selectedRole.goal(),
                rightX, textY, 0xCCCCCC
            );
            textY += lineHeight + 2;

            // 能力
            gui.drawString(
                Minecraft.getInstance().font,
                "§7能力: " + selectedRole.ability(),
                rightX, textY, 0xCCCCCC
            );
            textY += lineHeight + 4;

            // 描述
            String[] descLines = selectedRole.description().split("\n");
            for (String line : descLines) {
                gui.drawString(
                    Minecraft.getInstance().font,
                    line,
                    rightX, textY, 0xAAAAAA
                );
                textY += lineHeight;
            }
        }

        // 底部提示
        gui.drawString(
            Minecraft.getInstance().font,
            "按 [U] 键关闭",
            x + 8, y + GUI_HEIGHT - TOP_BAR_H - 8, 0x666666
        );
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int x = guiLeft;
        int y = guiTop + TOP_BAR_H + 8;

        for (int i = 0; i < ROLES.size(); i++) {
            int cardX = x + 8;
            int cardY = y + i * (CARD_H + CARD_GAP);
            if (mouseX >= cardX && mouseX <= cardX + LEFT_PANEL_W
             && mouseY >= cardY && mouseY <= cardY + CARD_H) {
                selectedIndex = i;
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // U 键关闭（同打开键）
        if (keyCode == 85) { // GLFW_KEY_U
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
