package com.habitrain.core.client.gui;

import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.awt.Color;

public class TaskColorPicker {
    private static final int ALPHA = 0xB4;

    private static int color(int index) {
        return SharedGuiConstants.getColor(index, ALPHA);
    }

    public final Button colorBtn;
    public final Button outlineMinusBtn;
    public final Button outlinePlusBtn;

    private final TaskConfigEntry cfg;
    private final boolean remoteEditable;
    private final Runnable onSave;

    public TaskColorPicker(TaskConfigEntry cfg, boolean remoteEditable, Runnable onSave) {
        this.cfg = cfg;
        this.remoteEditable = remoteEditable;
        this.onSave = onSave;

        this.colorBtn = Button.builder(Component.literal(colorBtnLabel()), b -> cycleColor())
                .bounds(-10000, -10000, 88, 20).build();

        this.outlineMinusBtn = Button.builder(Component.literal("§c−"), b -> {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return;
            }
            cfg.outlineWidth = Math.max(1.0f, cfg.outlineWidth - 0.5f);
            onSave.run();
        }).bounds(-10000, -10000, 20, 20).build();

        this.outlinePlusBtn = Button.builder(Component.literal("§a+"), b -> {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return;
            }
            cfg.outlineWidth = Math.min(10.0f, cfg.outlineWidth + 0.5f);
            onSave.run();
        }).bounds(-10000, -10000, 20, 20).build();
    }

    public int render(GuiGraphics g, Font f, int labelX, int rowX, int y, int mx, int my, float delta) {
        int r1 = y;
        g.drawString(f, Component.literal("§7透视颜色:"), labelX, r1 + 4, 0xCCCCCC, false);
        colorBtn.setX(rowX);
        colorBtn.setY(r1);
        colorBtn.render(g, mx, my, delta);
        int swatchX = colorBtn.getX() + colorBtn.getWidth() + 4;
        Color col = new Color(cfg.getColor(), true);
        int idx = getColorIndex();
        String cName = idx >= 0 ? SharedGuiConstants.COLOR_NAMES[idx] : "自定义";
        renderColorSwatch(g, f, swatchX, r1 + 3, col, cName);

        int r2 = r1 + 22;
        g.drawString(f, Component.literal("§7描边粗细:"), labelX, r2 + 4, 0xCCCCCC, false);
        outlineMinusBtn.setX(rowX);
        outlineMinusBtn.setY(r2);
        outlinePlusBtn.setX(rowX + 24);
        outlinePlusBtn.setY(r2);
        outlineMinusBtn.render(g, mx, my, delta);
        outlinePlusBtn.render(g, mx, my, delta);
        String valStr = String.format("§b%.1f", cfg.outlineWidth);
        g.drawString(f, Component.literal(valStr), rowX + 50, r2 + 4, 0xFFFFFF, false);
        g.drawString(f, Component.literal("§7(1.0 ~ 10.0)"), rowX + 50 + f.width(valStr) + 4, r2 + 4, 0x555555, false);

        return r2 + 22;
    }

    public static void renderColorSwatch(GuiGraphics g, Font f, int x, int y, Color color, String colorName) {
        int fill = (color.getAlpha() << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        g.fill(x, y, x + 12, y + 12, fill);
        g.fill(x, y, x + 1, y + 12, 0x88FFFFFF);
        g.fill(x + 11, y, x + 12, y + 12, 0x88FFFFFF);
        g.fill(x, y, x + 12, y + 1, 0x88FFFFFF);
        g.fill(x, y + 11, x + 12, y + 12, 0x88FFFFFF);
        g.drawString(f, Component.literal("§7" + colorName), x + 16, y + 2, 0x888888, false);
    }

    public int getColorIndex() {
        int cur = cfg.instinctColor & 0x00FFFFFF;
        for (int i = 0; i < SharedGuiConstants.getColorCount(); i++) {
            if ((color(i) & 0x00FFFFFF) == cur) return i;
        }
        return -1;
    }

    public void cycleColor() {
        int cur = cfg.instinctColor & 0x00FFFFFF;
        int n = SharedGuiConstants.getColorCount();
        for (int i = 0; i < n; i++) {
            if ((color(i) & 0x00FFFFFF) == cur) {
                cfg.instinctColor = color((i + 1) % n);
                onSave.run();
                colorBtn.setMessage(Component.literal(colorBtnLabel())); // S10-019
                return;
            }
        }
        cfg.instinctColor = color(0);
        onSave.run();
        colorBtn.setMessage(Component.literal(colorBtnLabel())); // S10-019
    }

    /** 当前颜色对应的按钮标签文字 (S10-019) */
    private String colorBtnLabel() {
        int idx = getColorIndex();
        if (idx >= 0) return "§l● " + SharedGuiConstants.COLOR_NAMES[idx];
        return "点击切换";
    }

    public boolean handleMouseClick(double mx, double my, int button) {
        if (mx >= colorBtn.getX() && mx < colorBtn.getX() + 88
                && my >= colorBtn.getY() && my < colorBtn.getY() + 20) {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            colorBtn.mouseClicked(mx, my, button);
            return true;
        }
        if (mx >= outlineMinusBtn.getX() && mx < outlineMinusBtn.getX() + 20
                && my >= outlineMinusBtn.getY() && my < outlineMinusBtn.getY() + 20) {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            outlineMinusBtn.mouseClicked(mx, my, button);
            return true;
        }
        if (mx >= outlinePlusBtn.getX() && mx < outlinePlusBtn.getX() + 20
                && my >= outlinePlusBtn.getY() && my < outlinePlusBtn.getY() + 20) {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            outlinePlusBtn.mouseClicked(mx, my, button);
            return true;
        }
        return false;
    }

    public void setActive(boolean active) {
        colorBtn.active = active;
        outlineMinusBtn.active = active;
        outlinePlusBtn.active = active;
    }
}
