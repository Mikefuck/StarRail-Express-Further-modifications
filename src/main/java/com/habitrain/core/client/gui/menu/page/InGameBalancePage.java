package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.*;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** 游戏内·数值平衡：DLC 占比滑块 / 警长除数 / 小游戏总开关。 */
public class InGameBalancePage implements ConfigPage {

    private static final int PAD = 16;
    private static final int ROW_H = 32;
    private static final float MIN_TARGET = 0.10f, MAX_TARGET = 0.80f, STEP = 0.05f;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private float dlcTarget;
    private boolean mgGlobal;
    private int sheriffDivisor;

    private final EditBox sheriffField;

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
        this.sheriffDivisor = c.getSheriffCountDivisor();
        this.sheriffField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        this.sheriffField.setMaxLength(3);
        this.sheriffField.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        this.sheriffField.setValue(String.valueOf(sheriffDivisor));
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
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        area.setBounds(x, y, w, h);
        g.enableScissor(x, y, x + w, y + h);
        hits.clear();
        int contentStartY = area.getContentY();
        int cy = contentStartY;
        int labelX = x + PAD;
        int sliderW = Math.min(360, w - PAD * 2);

        // ===== DLC 任务目标占比 =====
        g.drawString(font, "DLC 任务目标占比", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
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
        g.drawString(font, "警长数量除数", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += 18;
        g.drawString(font, "§7警长数量 = floor(玩家数 / 除数)，默认 6", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        g.drawString(font, "除数:", labelX, cy + 2, 0xFFCCCCCC, false);
        EditRow.render(g, mx, my, delta, sheriffField, labelX + 50, cy, 60);
        g.drawString(font, "§7（点底部保存生效）", labelX + 118, cy + 2, 0xFF777777, false);
        cy += ROW_H;

        // ===== 小游戏任务总开关 =====
        cy = toggleRow(g, cy, labelX, "小游戏任务总开关",
                "关闭后 SRE 将不再分配任何小游戏任务",
                mgGlobal, "小游戏 · 已启用", "小游戏 · 已停用", "mg");

        cy += 8;
        area.setContentHeight(cy - contentStartY);
        area.render(g);
        g.disableScissor();
    }

    private int sectionLine(GuiGraphics g, int cy, int labelX, int w) {
        g.fill(labelX - 2, cy - 2, labelX + w + 2, cy - 1, MenuTheme.BORDER);
        return cy + 6;
    }

    private int toggleRow(GuiGraphics g, int cy, int labelX, String title, String desc,
                          boolean on, String onText, String offText, String action) {
        g.fill(labelX - 2, cy - 2, labelX + 346, cy - 1, MenuTheme.BORDER);
        cy += 6;
        g.drawString(font, title, labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += 18;
        g.drawString(font, Component.literal(desc), labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        int tw = 160;
        PillToggle.render(g, font, labelX, cy, tw, 20, on, onText, offText);
        hits.add(new Hit(labelX, cy, tw, 20, 1));
        return cy + ROW_H;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (sheriffField.mouseClicked(mx, my, btn)) return true;
        sheriffField.setFocused(false);
        for (Hit hit : hits) {
            if (PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                if (hit.action() == 1) {
                    mgGlobal = !mgGlobal;
                    ConfigManager.getInstance().setMinigameGlobalEnabled(mgGlobal);
                    ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer());
                }
                MenuSounds.playClick();
                return true;
            }
        }
        if (editable && slider.mouseClicked(mx, my)) {
            MenuSounds.playClick();
            return true;
        }
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
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (sheriffField.isFocused() && sheriffField.charTyped(ch, mod)) return true;
        return false;
    }
}
