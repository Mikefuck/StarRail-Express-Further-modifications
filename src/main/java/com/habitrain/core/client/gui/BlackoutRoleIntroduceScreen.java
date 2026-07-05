package com.habitrain.core.client.gui;

import com.habitrain.core.game.blackout.BlackoutRoleDefinition;
import com.habitrain.core.game.blackout.BlackoutRoleRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Blackout mode role introduction screen.
 */
public class BlackoutRoleIntroduceScreen extends Screen {

    private static final int CARD_H = 46;
    private static final int CARD_SPACING = 4;
    private static final int PANEL_PAD = 6;

    private int selectedIndex = 0;
    private int listScrollOffset = 0;

    public BlackoutRoleIntroduceScreen() {
        super(Component.literal("停电模式 - 角色介绍"));
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("关闭"), btn -> onClose())
                .bounds(width / 2 - 50, height - 30, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        List<BlackoutRoleDefinition> roles = BlackoutRoleRegistry.getAll();
        int usableW = Math.min(700, (int) (width * 0.92f));
        int leftW = (int) (usableW * 0.34f);
        int rightW = usableW - leftW;
        int panelX = (width - usableW) / 2;
        int panelY = 36;
        int panelH = height - 76;

        drawPanelBg(g, panelX, panelY, leftW, panelH);
        int areaX = panelX + PANEL_PAD;
        int areaY = panelY + PANEL_PAD;
        int areaW = leftW - PANEL_PAD * 2;
        int areaH = panelH - PANEL_PAD * 2;

        g.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);
        for (int i = 0; i < roles.size(); i++) {
            BlackoutRoleDefinition role = roles.get(i);
            int cardY = areaY + i * (CARD_H + CARD_SPACING) - listScrollOffset;
            if (cardY + CARD_H < areaY || cardY > areaY + areaH) {
                continue;
            }

            boolean hovered = mouseX >= areaX && mouseX < areaX + areaW
                    && mouseY >= cardY && mouseY < cardY + CARD_H;
            boolean selected = i == selectedIndex;
            renderListCard(g, role, areaX, cardY, areaW, CARD_H, hovered, selected);
        }
        g.disableScissor();

        int rightX = panelX + leftW;
        drawPanelBg(g, rightX, panelY, rightW, panelH);
        if (selectedIndex >= 0 && selectedIndex < roles.size()) {
            BlackoutRoleDefinition role = roles.get(selectedIndex);
            int textX = rightX + PANEL_PAD + 2;
            int textY = panelY + 12;
            int maxW = rightW - PANEL_PAD * 2 - 4;

            g.drawString(font, Component.literal(role.displayName()).copy().withStyle(s -> s.withBold(true)),
                    textX, textY, roleColor(role), true);
            g.fill(textX, textY + 14, textX + 40, textY + 16, roleColor(role) | 0x88000000);

            textY += 22;
            var lines = font.split(Component.literal(role.description()), maxW);
            for (var line : lines) {
                g.drawString(font, line, textX, textY, 0xFFFFFF, false);
                textY += font.lineHeight + 2;
            }
        }

        g.drawCenteredString(font, this.title, width / 2, 12, 0xF5E8C8);
    }

    private void renderListCard(GuiGraphics g, BlackoutRoleDefinition role, int x, int y, int w, int h,
                                boolean hovered, boolean selected) {
        int roleColor = roleColor(role);
        int borderColor = selected ? 0xFFD4AF37 : (hovered ? 0xFF8B6914 : 0xFF5A4530);
        g.fill(x, y, x + w, y + h, borderColor);
        int bg = selected ? 0xFF3A2A10 : (hovered ? 0xFF2A1E0C : 0xFF1A1008);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        g.fill(x + 1, y + 1, x + 4, y + h - 1, roleColor);
        g.drawString(font, Component.literal(role.displayName()),
                x + 10, y + 6, roleColor, false);
        g.drawString(font, Component.literal(role.announcementSubtitle()),
                x + 10, y + 18, 0xD8D0C0, false);
        if (selected) {
            g.fill(x + w - 4, y + 3, x + w - 1, y + h - 3, 0xFFFFD700);
        }
    }

    private static int roleColor(BlackoutRoleDefinition role) {
        return switch (role.faction()) {
            case GOOD -> 0xFF4FD56B;
            case BAD -> 0xFFDD5A5A;
        };
    }

    private void drawPanelBg(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0xD81A1008, 0xD820140A);
        g.renderOutline(x, y, w, h, 0xFF8B6914);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x22FFE8C0);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<BlackoutRoleDefinition> roles = BlackoutRoleRegistry.getAll();
        int usableW = Math.min(700, (int) (width * 0.92f));
        int leftW = (int) (usableW * 0.34f);
        int panelX = (width - usableW) / 2;
        int panelY = 36;
        int panelH = height - 76;
        int areaX = panelX + PANEL_PAD;
        int areaY = panelY + PANEL_PAD;
        int areaW = leftW - PANEL_PAD * 2;

        if (button == 0 && mx >= areaX && mx < areaX + areaW && my >= areaY && my < areaY + panelH - PANEL_PAD * 2) {
            for (int i = 0; i < roles.size(); i++) {
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
        List<BlackoutRoleDefinition> roles = BlackoutRoleRegistry.getAll();
        int usableW = Math.min(700, (int) (width * 0.92f));
        int leftW = (int) (usableW * 0.34f);
        int panelX = (width - usableW) / 2;
        int panelY = 36;
        int panelH = height - 76;

        if (mx >= panelX && mx < panelX + leftW && my >= panelY && my < panelY + panelH) {
            int totalH = roles.size() * (CARD_H + CARD_SPACING);
            int areaH = panelH - PANEL_PAD * 2;
            int maxScroll = Math.max(0, totalH - areaH);
            listScrollOffset = Mth.clamp((int) (listScrollOffset - scrollY * (CARD_H + CARD_SPACING)), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
