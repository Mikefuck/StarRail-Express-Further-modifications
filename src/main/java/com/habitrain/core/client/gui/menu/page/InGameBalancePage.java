package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.*;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** 游戏内·数值平衡：DLC 占比滑块 / 警长除数 / 临时电源价格 / 刀耐久 / 小游戏总开关。 */
public class InGameBalancePage implements ConfigPage {

    private static final int PAD = 16;
    private static final int ROW_H = 32;
    private static final float MIN_TARGET = 0.10f, MAX_TARGET = 0.80f, STEP = 0.05f;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private float dlcTarget;
    private boolean mgGlobal, knifeDurabilityEnabled;
    private int sheriffDivisor, tempPowerPrice;

    private final EditBox sheriffField, tempPowerField;
    private boolean widgetsInitialized = false;

    private final SliderRow slider = new SliderRow(MIN_TARGET, MAX_TARGET, STEP);
    private ScrollArea area;

    // 本页命中矩形：开关药丸 + 数值行
    private record Hit(int x, int y, int w, int h, int action) {}
    private final java.util.List<Hit> hits = new java.util.ArrayList<>();

    public InGameBalancePage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        ConfigManager c = ConfigManager.getInstance();
        this.dlcTarget = c.getDlcProbabilityTarget();
        this.mgGlobal = c.isMinigameGlobalEnabled();
        this.knifeDurabilityEnabled = c.isKnifeDurabilityEnabled();
        this.sheriffDivisor = c.getSheriffCountDivisor();
        this.tempPowerPrice = c.getTempPowerPrice();
        this.sheriffField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        this.sheriffField.setMaxLength(3);
        this.sheriffField.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        this.sheriffField.setValue(String.valueOf(sheriffDivisor));
        this.tempPowerField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        this.tempPowerField.setMaxLength(6);
        this.tempPowerField.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        this.tempPowerField.setValue(String.valueOf(tempPowerPrice));
        this.area = new ScrollArea(0, 0, 0, 0); // 坐标在 render 里设定
    }

    @Override public boolean canSave() { return true; }
    @Override public void save() { /* 所有改动即时生效，无额外提交 */ }

    @Override
    public void flushPending() {
        if (!editable) return;
        try {
            int v = Integer.parseInt(sheriffField.getValue().trim());
            sheriffDivisor = Math.max(1, v);
            ConfigManager.getInstance().setSheriffCountDivisor(sheriffDivisor);
        } catch (NumberFormatException ignored) {}
        try {
            int v = Integer.parseInt(tempPowerField.getValue().trim());
            tempPowerPrice = Math.max(0, v);
            ConfigManager.getInstance().setTempPowerPrice(tempPowerPrice);
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        area = new ScrollArea(x, y, w, h);
        g.enableScissor(x, y, x + w, y + h);
        hits.clear();
        int cy = area.getContentY();
        int labelX = x + PAD;
        int sliderW = Math.min(360, w - PAD * 2);

        // ===== DLC 任务目标占比 =====
        g.drawString(font, Component.literal("§e§lDLC 任务目标占比"), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal("§7系统自动平衡 DLC 与原版任务的出现概率（10%~80%）"),
                labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        if (editable) {
            if (slider.mouseDragged()) {
                float nv = slider.valueFromMouse(mx);
                if (nv != dlcTarget) { dlcTarget = nv; ConfigManager.getInstance().setDlcProbabilityTarget(dlcTarget); }
            }
            slider.render(g, font, labelX, cy, sliderW, dlcTarget);
        } else {
            g.drawString(font, String.format("§6§l%d%%", Math.round(dlcTarget * 100)), labelX, cy + 1, 0xFFFFFFFF, false);
        }
        cy += 24;

        // ===== 警长数量除数 =====
        cy = sectionLine(g, cy, labelX, sliderW);
        g.drawString(font, Component.literal("§e§l警长数量除数"), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, "§7警长数量 = floor(玩家数 / 除数)，默认 6", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        g.drawString(font, "除数:", labelX, cy + 2, 0xFFCCCCCC, false);
        EditRow.render(g, mx, my, delta, sheriffField, labelX + 50, cy, 60);
        g.drawString(font, "§7（点底部保存生效）", labelX + 118, cy + 2, 0xFF777777, false);
        cy += ROW_H;

        // ===== 临时电源价格 =====
        cy = sectionLine(g, cy, labelX, sliderW);
        g.drawString(font, Component.literal("§e§l临时电源价格"), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal("§7停电模式红色电话商店「临时电源」提灯价格，默认 100"),
                labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        g.drawString(font, "价格:", labelX, cy + 2, 0xFFCCCCCC, false);
        EditRow.render(g, mx, my, delta, tempPowerField, labelX + 50, cy, 60);
        g.drawString(font, "§7（点底部保存生效）", labelX + 118, cy + 2, 0xFF777777, false);
        cy += ROW_H;

        // ===== 杀手刀耐久 =====
        cy = toggleRow(g, cy, labelX, "杀手刀耐久",
                "§7关闭时恢复旧版无限耐久；开启时恢复上游耐久规则",
                knifeDurabilityEnabled, "§a刀耐久：已启用", "§c刀耐久：已禁用", "knife");

        // ===== 小游戏任务总开关 =====
        cy = toggleRow(g, cy, labelX, "小游戏任务总开关",
                "§7关闭后 SRE 将不再分配任何小游戏任务",
                mgGlobal, "§a小游戏：已启用", "§c小游戏：已停用", "mg");

        cy += 8;
        area.setContentHeight(cy - y);
        area.render(g);
        g.disableScissor();
    }

    private int sectionLine(GuiGraphics g, int cy, int labelX, int w) {
        g.fill(labelX - 2, cy - 2, labelX + w + 2, cy - 1, 0x20FFFFFF);
        return cy + 6;
    }

    private int toggleRow(GuiGraphics g, int cy, int labelX, String title, String desc,
                          boolean on, String onText, String offText, String action) {
        g.fill(labelX - 2, cy - 2, labelX + 346, cy - 1, 0x20FFFFFF);
        cy += 6;
        g.drawString(font, Component.literal("§e§l" + title), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal(desc), labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        int tw = 160;
        PillToggle.render(g, font, labelX, cy, tw, 20, on, onText, offText);
        hits.add(new Hit(labelX, cy, tw, 20, action.equals("knife") ? 2 : 1));
        return cy + ROW_H;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (sheriffField.mouseClicked(mx, my, btn)) return true;
        if (tempPowerField.mouseClicked(mx, my, btn)) return true;
        for (Hit hit : hits) {
            if (PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                if (hit.action() == 1) {
                    mgGlobal = !mgGlobal;
                    ConfigManager.getInstance().setMinigameGlobalEnabled(mgGlobal);
                    ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer());
                } else {
                    knifeDurabilityEnabled = !knifeDurabilityEnabled;
                    ConfigManager.getInstance().setKnifeDurabilityEnabled(knifeDurabilityEnabled);
                }
                return true;
            }
        }
        if (slider.mouseClicked(mx, my)) return true;
        return area.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (slider.mouseDragged()) return true;
        return area.mouseDragged(my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (slider.mouseReleased()) return true;
        return area.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        return area.mouseScrolled(sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (sheriffField.isFocused() && sheriffField.keyPressed(key, scan, mod)) return true;
        if (tempPowerField.isFocused() && tempPowerField.keyPressed(key, scan, mod)) return true;
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (sheriffField.isFocused() && sheriffField.charTyped(ch, mod)) return true;
        if (tempPowerField.isFocused() && tempPowerField.charTyped(ch, mod)) return true;
        return false;
    }
}
