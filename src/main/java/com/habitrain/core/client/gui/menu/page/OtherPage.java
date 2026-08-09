package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.client.gui.menu.ui.ScrollArea;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 不属于常用平衡流程的兼容和服务端行为设置。 */
public class OtherPage implements ConfigPage {
    private static final int PAD = 14;
    private static final int CARD_H = 72;
    private static final int GAP = 8;

    private final Font font;
    private final boolean editable;
    private final EditBox tempPowerField;
    private final ScrollArea area = new ScrollArea(0, 0, 1, 1);
    private final List<Hit> hits = new ArrayList<>();

    private int tempPowerPrice;
    private boolean knifeDurabilityEnabled;
    private boolean lobbyVoiceGroupEnabled;
    private boolean blackoutEffectEnhancementEnabled;

    private record Hit(int action, int x, int y, int w, int h) {}

    public OtherPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.font = font;
        this.editable = editable;
        ConfigManager config = ConfigManager.getInstance();
        tempPowerPrice = config.getTempPowerPrice();
        knifeDurabilityEnabled = config.isKnifeDurabilityEnabled();
        lobbyVoiceGroupEnabled = config.isLobbyVoiceGroupEnabled();
        blackoutEffectEnhancementEnabled = config.isBlackoutEffectEnhancementEnabled();

        tempPowerField = new EditBox(font, -10000, -10000, 72, 16, Component.literal(""));
        tempPowerField.setMaxLength(6);
        tempPowerField.setFilter(value -> value.isEmpty() || value.matches("\\d*"));
        tempPowerField.setValue(String.valueOf(tempPowerPrice));
        tempPowerField.setEditable(editable);
    }

    @Override public boolean canSave() { return true; }
    @Override public void save() {}

    @Override
    public void flushPending() {
        if (!editable) return;
        try {
            tempPowerPrice = Math.max(0, Integer.parseInt(tempPowerField.getValue().trim()));
            ConfigManager.getInstance().setTempPowerPrice(tempPowerPrice);
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        area.setBounds(x, y, w, h);
        hits.clear();
        g.enableScissor(x, y, x + w, y + h);

        int contentStartY = area.getContentY();
        int cy = contentStartY + PAD;
        int cardX = x + PAD;
        int cardW = Math.max(180, w - PAD * 2 - 5);

        cy = renderPriceCard(g, mx, my, delta, cardX, cy, cardW);
        cy += GAP;
        cy = renderToggleCard(g, cardX, cy, cardW, 1,
                "杀手刀耐久规则",
                "开启后使用上游耐久；关闭后杀手刀保持无限耐久",
                knifeDurabilityEnabled, "耐久 · 已启用", "耐久 · 已关闭");
        cy += GAP;
        cy = renderToggleCard(g, cardX, cy, cardW, 2,
                "对局外自动拉入大厅语音群组",
                "控制进服、大厅空闲和对局结束后是否自动加入 LobbyChat",
                lobbyVoiceGroupEnabled, "自动拉群 · 已启用", "自动拉群 · 已关闭");
        cy += GAP;
        cy = renderToggleCard(g, cardX, cy, cardW, 3,
                "停电黑暗时长增强",
                "开启后普通停电强制黑暗+失明 20 秒，忍者关灯 10 秒；关闭保持原版 10 秒",
                blackoutEffectEnhancementEnabled, "增强 · 已启用", "增强 · 已关闭");
        cy += PAD;

        area.setContentHeight(cy - contentStartY);
        area.render(g);
        g.disableScissor();
    }

    private int renderPriceCard(GuiGraphics g, int mx, int my, float delta,
                                int x, int y, int w) {
        MenuTheme.panel(g, x, y, w, CARD_H);
        g.fill(x, y, x + 2, y + CARD_H, MenuTheme.ACCENT_AMBER);
        g.drawString(font, "停电模式 · 临时电源价格", x + 12, y + 12,
                MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, "红色电话商店中的提灯价格；默认 100", x + 12, y + 28,
                MenuTheme.TEXT_SECONDARY, false);
        g.drawString(font, "价格", x + 12, y + 50, MenuTheme.TEXT_SECONDARY, false);
        tempPowerField.setX(x + 52);
        tempPowerField.setY(y + 46);
        tempPowerField.setWidth(72);
        tempPowerField.render(g, mx, my, delta);
        g.drawString(font, "点击底部保存后提交输入值", x + 134, y + 50,
                MenuTheme.TEXT_DIM, false);
        return y + CARD_H;
    }

    private int renderToggleCard(GuiGraphics g, int x, int y, int w, int action,
                                 String title, String description, boolean value,
                                 String onText, String offText) {
        MenuTheme.panel(g, x, y, w, CARD_H);
        int accent = value ? MenuTheme.ACCENT_MINT : MenuTheme.DANGER;
        g.fill(x, y, x + 2, y + CARD_H, accent);
        g.drawString(font, title, x + 12, y + 12, MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, description, x + 12, y + 28, MenuTheme.TEXT_SECONDARY, false);
        int toggleW = Math.min(176, w - 24);
        int toggleY = y + 45;
        PillToggle.render(g, font, x + 12, toggleY, toggleW, 20, value, onText, offText);
        hits.add(new Hit(action, x + 12, toggleY, toggleW, 20));
        return y + CARD_H;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (tempPowerField.mouseClicked(mx, my, btn)) return true;
        for (Hit hit : hits) {
            if (!MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            if (!editable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            if (hit.action() == 1) {
                knifeDurabilityEnabled = !knifeDurabilityEnabled;
                ConfigManager.getInstance().setKnifeDurabilityEnabled(knifeDurabilityEnabled);
            } else if (hit.action() == 2) {
                lobbyVoiceGroupEnabled = !lobbyVoiceGroupEnabled;
                ConfigManager.getInstance().setLobbyVoiceGroupEnabled(lobbyVoiceGroupEnabled);
            } else {
                blackoutEffectEnhancementEnabled = !blackoutEffectEnhancementEnabled;
                ConfigManager.getInstance().setBlackoutEffectEnhancementEnabled(blackoutEffectEnhancementEnabled);
            }
            MenuSounds.playClick();
            return true;
        }
        return area.mouseClicked(mx, my, btn);
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy,
                                          int x, int y, int w, int h) { return area.mouseDragged(my); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return area.mouseReleased(); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy,
                                           int x, int y, int w, int h) { return area.mouseScrolled(sy); }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        return tempPowerField.isFocused() && tempPowerField.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        return tempPowerField.isFocused() && tempPowerField.charTyped(ch, mod);
    }
}
