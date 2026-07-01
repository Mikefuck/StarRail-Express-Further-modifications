package com.habitrain.core.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 停电模式专用角色介绍界面。
 * 只显示本模式的 3 个角色：黑化平民、黑化杀手、警长。
 * 基于 SRE 的 RoleIntroduceScreen 样式简化
 */
public class BlackoutRoleIntroduceScreen extends Screen {

    private static class RoleCard {
        final String name;
        final String description;
        final int color;  // ARGB

        RoleCard(String name, String description, int color) {
            this.name = name;
            this.description = description;
            this.color = color;
        }
    }

    private static final List<RoleCard> ROLES = List.of(
        new RoleCard("黑化平民", "§7好人阵营\n你是一名普通的列车乘客。\n\n§f→ 完成好人任务\n→ 存活到最后\n→ 帮助警长找出杀手", 0xFF44BB66),
        new RoleCard("黑化杀手", "§c坏人阵营\n混入人群的破坏者。\n\n§f→ 消灭所有好人\n→ 破坏列车供电\n→ 不要暴露身份", 0xFFCC2233),
        new RoleCard("警长", "§b好人阵营\n维护正义的执法者。\n\n§f→ 暗中调查可疑玩家\n→ 使用配枪制裁杀手\n→ 保护好自己", 0xFF22BBCC)
    );

    private static final int CARD_H = 42;
    private static final int CARD_SPACING = 4;
    private static final int PANEL_PAD = 6;
    private static final int ICON_SIZE = 26;

    private int selectedIndex = 0;
    private int listScrollOffset = 0;

    public BlackoutRoleIntroduceScreen() {
        super(Component.literal("停电模式 — 角色介绍"));
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("关闭"),
                btn -> onClose())
                .bounds(width / 2 - 50, height - 30, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderBackground(g, mouseX, mouseY, partialTick);

        int usableW = Math.min(600, (int)(width * 0.9f));
        int leftW = (int)(usableW * 0.35f);
        int rightW = usableW - leftW;
        int panelX = (width - usableW) / 2;
        int panelY = 40;
        int panelH = height - 80;

        // Left panel — role list
        drawPanelBg(g, panelX, panelY, leftW, panelH);
        int areaX = panelX + PANEL_PAD;
        int areaY = panelY + PANEL_PAD;
        int areaW = leftW - PANEL_PAD * 2;
        int areaH = panelH - PANEL_PAD * 2;

        g.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);
        for (int i = 0; i < ROLES.size(); i++) {
            RoleCard role = ROLES.get(i);
            int cardY = areaY + i * (CARD_H + CARD_SPACING) - listScrollOffset;
            if (cardY + CARD_H < areaY || cardY > areaY + areaH) continue;

            boolean hovered = mouseX >= areaX && mouseX < areaX + areaW
                    && mouseY >= cardY && mouseY < cardY + CARD_H;
            boolean selected = i == selectedIndex;
            renderListCard(g, role, areaX, cardY, areaW, CARD_H, hovered, selected);
        }
        g.disableScissor();

        // Right panel — detail
        int rightX = panelX + leftW;
        drawPanelBg(g, rightX, panelY, rightW, panelH);
        if (selectedIndex >= 0 && selectedIndex < ROLES.size()) {
            RoleCard role = ROLES.get(selectedIndex);
            int textX = rightX + PANEL_PAD + 2;
            int textY = panelY + 12;
            int maxW = rightW - PANEL_PAD * 2 - 4;

            // Name
            g.drawString(font, Component.literal(role.name).copy().withStyle(s -> s.withBold(true)),
                    textX, textY, role.color, true);

            // Separator
            g.fill(textX, textY + 14, textX + 40, textY + 16, role.color & 0x88FFFFFF);

            // Description (word-wrapped)
            textY += 22;
            var lines = font.split(Component.literal(role.description), maxW);
            for (var line : lines) {
                g.drawString(font, line, textX, textY, 0xFFFFFF, false);
                textY += font.lineHeight + 2;
            }
        }

        // Title
        g.drawCenteredString(font, this.title, width / 2, 12, 0xF5E8C8);
    }

    private void renderListCard(GuiGraphics g, RoleCard role, int x, int y, int w, int h, boolean hovered, boolean selected) {
        int borderColor = selected ? 0xFFD4AF37 : (hovered ? 0xFF8B6914 : 0xFF5A4530);
        g.fill(x, y, x + w, y + h, borderColor);
        int bg = selected ? 0xFF3A2A10 : (hovered ? 0xFF2A1E0C : 0xFF1A1008);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        // Color bar
        g.fill(x + 1, y + 1, x + 4, y + h - 1, role.color);
        // Name
        g.drawString(font, Component.literal(role.name), x + 10, y + (h - font.lineHeight) / 2, role.color, false);
        // Selection indicator
        if (selected) {
            g.fill(x + w - 4, y + 3, x + w - 1, y + h - 3, 0xFFFFD700);
        }
    }

    private void drawPanelBg(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0xD81A1008, 0xD820140A);
        g.renderOutline(x, y, w, h, 0xFF8B6914);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x22FFE8C0);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int usableW = Math.min(600, (int)(width * 0.9f));
        int leftW = (int)(usableW * 0.35f);
        int panelX = (width - usableW) / 2;
        int panelY = 40;
        int panelH = height - 80;
        int areaX = panelX + PANEL_PAD;
        int areaY = panelY + PANEL_PAD;
        int areaW = leftW - PANEL_PAD * 2;

        if (button == 0 && mx >= areaX && mx < areaX + areaW && my >= areaY && my < areaY + panelH - PANEL_PAD * 2) {
            for (int i = 0; i < ROLES.size(); i++) {
                int cardY = areaY + i * (CARD_H + CARD_SPACING) - listScrollOffset;
                if (my >= cardY && my < cardY + CARD_H) {
                    selectedIndex = i;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int usableW = Math.min(600, (int)(width * 0.9f));
        int leftW = (int)(usableW * 0.35f);
        int panelX = (width - usableW) / 2;
        int panelY = 40;
        int panelH = height - 80;

        if (mx >= panelX && mx < panelX + leftW && my >= panelY && my < panelY + panelH) {
            int totalH = ROLES.size() * (CARD_H + CARD_SPACING);
            int areaH = panelH - PANEL_PAD * 2;
            int maxScroll = Math.max(0, totalH - areaH);
            listScrollOffset = Mth.clamp((int)(listScrollOffset - scrollY * (CARD_H + CARD_SPACING)), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
