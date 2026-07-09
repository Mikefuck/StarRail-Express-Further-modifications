package com.habitrain.core.client.gui;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TaskEditScreen extends Screen {

    private static final int PAD = 10;
    private static final int HEADER_H = 48;
    private static final int FOOTER_H = 32;
    private static final int SECTION_GAP = 8;
    private static final int ROW_H = 22;
    private static final int LABEL_W = 76;
    private static final int SCROLLBAR_W = 4;

    private final Screen parent;
    private final TaskDefinition def;
    private final TaskConfigEntry cfg;
    private final TaskCategory category;
    private final String modeDisplayName;
    private final int modeAccentColor;
    private final boolean remoteEditable;

    private double scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0, dragStartOff = 0;
    private int contentHeight = 0;

    private Button enableBtn;
    private EditBox goldField, emotionField, weightField;
    private Button saveBtn, saveReturnBtn, resetBtn;
    private Button topBackBtn;

    private TaskColorPicker colorPicker;
    private TaskMapFilterEditor mapEditor;
    private final TaskSaveController saveController;

    public TaskEditScreen(Screen parent, TaskDefinition def, TaskConfigEntry cfg,
                              TaskCategory category, String modeDisplayName, int modeAccentColor) {
        super(Component.literal("§l" + def.getDisplayName() + " - 详细配置"));
        this.parent = parent;
        this.def = def;
        this.cfg = cfg;
        this.category = category;
        this.modeDisplayName = modeDisplayName;
        this.modeAccentColor = modeAccentColor;
        this.remoteEditable = LiveConfigAccess.canEditRemoteConfigs();
        this.saveController = new TaskSaveController(def, cfg, remoteEditable);
    }

    @Override
    protected void init() {
        super.init();
        Font f = font;

        enableBtn = Button.builder(makeEnableText(), b -> {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return;
            }
            cfg.enabled = !cfg.enabled;
            enableBtn.setMessage(makeEnableText());
            saveController.saveCurrent();
        }).bounds(-10000, -10000, 88, 20).build();

        colorPicker = new TaskColorPicker(cfg, remoteEditable, () -> saveController.saveCurrent());

        mapEditor = new TaskMapFilterEditor(cfg, remoteEditable, () -> saveController.saveCurrent(), f);

        goldField = new EditBox(f, -10000, -10000, 60, 14, Component.literal(""));
        goldField.setMaxLength(8);
        if (cfg.hasGoldReward) goldField.setValue(String.valueOf(cfg.goldReward));
        goldField.setHint(Component.literal("默认"));
        goldField.setEditable(remoteEditable);

        emotionField = new EditBox(f, -10000, -10000, 60, 14, Component.literal(""));
        emotionField.setMaxLength(8);
        if (cfg.hasEmotionReward) emotionField.setValue(String.format("%.2f", cfg.emotionReward));
        emotionField.setHint(Component.literal("默认"));
        emotionField.setEditable(remoteEditable);

        weightField = new EditBox(f, -10000, -10000, 60, 14, Component.literal(""));
        weightField.setMaxLength(8);
        if (cfg.hasRefreshWeight) weightField.setValue(String.format("%.1f", cfg.refreshWeight));
        weightField.setHint(Component.literal("默认"));
        weightField.setEditable(remoteEditable);

        int centerX = width / 2;
        int btnY = height - FOOTER_H + 6;

        saveBtn = Button.builder(Component.literal("§a✔ 保存修改"), b -> {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return;
            }
            saveController.syncFields(goldField, emotionField, weightField, mapEditor.mapField);
            saveController.saveCurrent();
            TaskSaveController.showMessage("§a✔ 任务「" + def.getDisplayName() + "」已保存！");
        }).bounds(centerX - 155, btnY, 90, 20).build();
        addRenderableWidget(saveBtn);

        saveReturnBtn = Button.builder(Component.literal("§b✔ 保存并返回"), b -> {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return;
            }
            saveController.syncFields(goldField, emotionField, weightField, mapEditor.mapField);
            saveController.saveCurrent();
            goBack();
        }).bounds(centerX - 58, btnY, 100, 20).build();
        addRenderableWidget(saveReturnBtn);

        resetBtn = Button.builder(Component.literal("§7↺ 重置默认"), b -> {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return;
            }
            saveController.resetDefault();
            clearWidgets();
            init();
        }).bounds(centerX + 50, btnY, 80, 20).build();
        addRenderableWidget(resetBtn);

        topBackBtn = Button.builder(Component.literal("§7← 返回列表"), b -> goBack())
                .bounds(width - 90, 4, 80, 16).build();
        addRenderableWidget(topBackBtn);

        if (!remoteEditable) {
            enableBtn.active = false;
            colorPicker.setActive(false);
            mapEditor.setActive(false);
            saveBtn.active = false;
            saveReturnBtn.active = false;
            resetBtn.active = false;
        }

        recalcContentHeight();
    }

    private int calcSectionCount() {
        return 3 + 3 + 2 + 8;
    }

    private int calcTotalContentHeight() {
        int rows = calcSectionCount();
        return PAD + 4 * 24 + rows * ROW_H + 3 * SECTION_GAP + PAD;
    }

    private void recalcContentHeight() {
        contentHeight = calcTotalContentHeight();
        clampScroll();
    }

    private int getBodyTop() {
        return HEADER_H + 4;
    }

    private int getBodyBottom() {
        return height - FOOTER_H - 4;
    }

    private int getBodyHeight() {
        return getBodyBottom() - getBodyTop();
    }

    private void clampScroll() {
        int bodyH = getBodyHeight();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentHeight - bodyH)));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        Font f = font;
        int bodyTop = getBodyTop();
        int bodyBot = getBodyBottom();
        int scrollW = width - PAD * 2;

        String breadcrumb = "§7" + modeDisplayName + " §f> §r§l" + def.getDisplayName();
        g.drawString(f, Component.literal(breadcrumb), PAD, 4, 0xFFFFFF, false);
        g.drawString(f, Component.literal("§8" + def.getFullId()), PAD, 17, 0x555555, false);
        boolean builtin = HabiTrainCore.MOD_ID.equals(def.getModId());
        g.drawString(f, Component.literal(builtin ? "§8[内置任务]" : "§e[外部/DLC任务]"),
                PAD, 30, 0, false);
        if (!remoteEditable) {
            g.drawString(f, Component.literal("§c只读：联机服务器中仅 OP 可修改"),
                    PAD, 42, 0xFF7777, false);
        }
        g.fill(PAD, HEADER_H - 1, width - PAD, HEADER_H, modeAccentColor);

        g.enableScissor(PAD, bodyTop, width - PAD, bodyBot);
        int labelX = PAD + 8;
        int rowX = labelX + LABEL_W;
        int curY = bodyTop - (int) scrollOffset;

        curY = renderSectionBasic(g, f, scrollW, curY, labelX, rowX, mx, my, delta);
        curY += SECTION_GAP;
        curY = renderSectionReward(g, f, scrollW, curY, labelX, rowX, mx, my, delta);
        curY += SECTION_GAP;
        curY = renderSectionMap(g, f, scrollW, curY, labelX, rowX, mx, my, delta);
        curY += SECTION_GAP;
        curY = renderSectionInfo(g, f, scrollW, curY, labelX, rowX);

        g.disableScissor();

        int bodyH = getBodyHeight();
        if (contentHeight > bodyH) {
            int thumbH = Math.max(20, bodyH * bodyH / contentHeight);
            int thumbY = bodyTop + (int) ((float) scrollOffset / (contentHeight - bodyH) * (bodyH - thumbH));
            int sx = width - PAD - SCROLLBAR_W;
            g.fill(sx, bodyTop, sx + SCROLLBAR_W, bodyBot, 0x20FFFFFF);
            g.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, 0x90AAAAAA);
        }

        g.fill(PAD, bodyBot, width - PAD, bodyBot + 1, 0x30FFFFFF);
        String tip = "§7提示: 修改后记得点击「保存修改」或「保存并返回」";
        g.drawString(f, Component.literal(tip), PAD, bodyBot + 6, 0x777777, false);
    }

    private int renderSectionFrame(GuiGraphics g, Font f, int w, int y, String title, int titleColor) {
        int barH = 22;
        g.fill(PAD, y, PAD + w, y + barH, titleColor & 0x00FFFFFF | 0x44000000);
        g.fill(PAD, y, PAD + w, y + 1, titleColor | 0xFF000000);
        g.drawString(f, Component.literal("§l§f" + title), PAD + 6, y + 7, 0xFFFFFF, false);
        return y + barH;
    }

    private int renderSectionBasic(GuiGraphics g, Font f, int w, int y, int labelX, int rowX, int mx, int my, float delta) {
        int secY = renderSectionFrame(g, f, w, y, "⚙ 基础设置", 0x44FFFFFF);

        int r1 = secY;
        g.drawString(f, Component.literal("§7任务状态:"), labelX, r1 + 4, 0xCCCCCC, false);
        enableBtn.setX(rowX);
        enableBtn.setY(r1);
        enableBtn.render(g, mx, my, delta);

        return colorPicker.render(g, f, labelX, rowX, r1 + ROW_H, mx, my, delta);
    }

    private int renderSectionReward(GuiGraphics g, Font f, int w, int y, int labelX, int rowX, int mx, int my, float delta) {
        int secY = renderSectionFrame(g, f, w, y, "💰 奖励设置", 0x44FFD700);

        int r1 = secY;
        g.drawString(f, Component.literal("§6金币奖励:"), labelX, r1 + 4, 0xCCCCCC, false);
        goldField.setX(rowX);
        goldField.setY(r1 + 4);
        goldField.setWidth(60);
        goldField.render(g, mx, my, delta);
        g.drawString(f, Component.literal("§7(留空 = 系统默认值)"), rowX + 66, r1 + 4, 0x777777, false);

        int r2 = r1 + ROW_H;
        g.drawString(f, Component.literal("§d情绪奖励:"), labelX, r2 + 4, 0xCCCCCC, false);
        emotionField.setX(rowX);
        emotionField.setY(r2 + 4);
        emotionField.setWidth(60);
        emotionField.render(g, mx, my, delta);
        g.drawString(f, Component.literal("§7(留空 = 系统默认值)"), rowX + 66, r2 + 4, 0x777777, false);

        int r3 = r2 + ROW_H;
        g.drawString(f, Component.literal("§e刷新权重:"), labelX, r3 + 4, 0xCCCCCC, false);
        weightField.setX(rowX);
        weightField.setY(r3 + 4);
        weightField.setWidth(60);
        weightField.render(g, mx, my, delta);
        float effW = cfg.hasRefreshWeight ? cfg.refreshWeight : def.getWeight();
        String effWStr = String.format("§7生效: §e%.1f", effW);
        g.drawString(f, Component.literal(effWStr), rowX + 66, r3 + 4, 0x777777, false);

        return r3 + ROW_H;
    }

    private int renderSectionMap(GuiGraphics g, Font f, int w, int y, int labelX, int rowX, int mx, int my, float delta) {
        int secY = renderSectionFrame(g, f, w, y, "🗺 地图设置", 0x4455AAFF);
        return mapEditor.render(g, f, labelX, rowX, w, secY, mx, my, delta);
    }

    private int renderSectionInfo(GuiGraphics g, Font f, int w, int y, int labelX, int rowX) {
        int secY = renderSectionFrame(g, f, w, y, "ℹ 基本信息 (只读)", 0x44555555);

        String[][] infos = {
                {"任务名称", def.getDisplayName()},
                {"完整ID", def.getFullId()},
                {"模组来源", def.getModId()},
                {"任务分类", getCategoryName(def.getCategory())},
                {"默认权重", String.format("%.1f", def.getWeight())},
                {"方块类型ID", def.getBlockTypeId() >= 0 ? String.valueOf(def.getBlockTypeId()) : "无"},
                {"可直接获胜", def.canDirectlyWin() ? "§a是" : "§c否"},
                {"扫描方块数", String.valueOf(def.getScanBlocks().size())},
        };

        int curY = secY;
        for (String[] info : infos) {
            g.drawString(f, Component.literal("§7" + info[0] + ":"), PAD + 12, curY + 2, 0x888888, false);
            g.drawString(f, Component.literal("§f" + info[1]), rowX, curY + 2, 0xFFFFFF, false);
            curY += ROW_H - 2;
        }
        return curY;
    }

    private Component makeEnableText() {
        return Component.literal(cfg.enabled ? "§a✔ 已启用" : "§c✘ 已禁用");
    }

    private void goBack() {
        Minecraft.getInstance().setScreen(parent);
    }

    private String getCategoryName(TaskCategory cat) {
        if (cat == TaskCategory.MURDER) return "谋杀模式";
        if (cat == TaskCategory.REPAIR) return "修机模式";
        if (cat == TaskCategory.ALL) return "通用任务";
        if (cat == TaskCategory.CUSTOM) return "自定义任务";
        return cat.getDisplayName();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int bodyTop = getBodyTop();
        int bodyBot = getBodyBottom();
        if (my >= bodyTop && my < bodyBot) {
            scrollOffset -= dy * 16;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;

        int bodyTop = getBodyTop();
        int bodyBot = getBodyBottom();

        int sx = width - PAD - SCROLLBAR_W;
        if (mx >= sx && mx < width - PAD && my >= bodyTop && my < bodyBot) {
            draggingScroll = true;
            dragStartY = my;
            dragStartOff = scrollOffset;
            return true;
        }

        if (my < bodyTop || my >= bodyBot) return false;

        goldField.setFocused(false);
        emotionField.setFocused(false);
        weightField.setFocused(false);
        mapEditor.mapField.setFocused(false);

        if (mx >= enableBtn.getX() && mx < enableBtn.getX() + 88
                && my >= enableBtn.getY() && my < enableBtn.getY() + 20) {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            enableBtn.mouseClicked(mx, my, button);
            return true;
        }

        if (colorPicker.handleMouseClick(mx, my, button)) return true;
        if (mapEditor.handleMouseClick(mx, my, button)) return true;

        if (mx >= goldField.getX() && mx < goldField.getX() + 60
                && my >= goldField.getY() && my < goldField.getY() + 14) {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            goldField.setFocused(true);
            return true;
        }
        if (mx >= emotionField.getX() && mx < emotionField.getX() + 60
                && my >= emotionField.getY() && my < emotionField.getY() + 14) {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            emotionField.setFocused(true);
            return true;
        }
        if (mx >= weightField.getX() && mx < weightField.getX() + 60
                && my >= weightField.getY() && my < weightField.getY() + 14) {
            if (!remoteEditable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            weightField.setFocused(true);
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingScroll = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingScroll) {
            int bodyH = getBodyHeight();
            double scale = (double) (contentHeight - bodyH) / Math.max(1, bodyH - 20);
            scrollOffset = dragStartOff + (my - dragStartY) * scale;
            clampScroll();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (goldField.isFocused() && goldField.keyPressed(key, sc, mod)) return true;
        if (emotionField.isFocused() && emotionField.keyPressed(key, sc, mod)) return true;
        if (weightField.isFocused() && weightField.keyPressed(key, sc, mod)) return true;
        if (mapEditor.mapField.isFocused() && mapEditor.mapField.keyPressed(key, sc, mod)) return true;

        if (key == 256) {
            goBack();
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (goldField.isFocused() && goldField.charTyped(ch, mod)) return true;
        if (emotionField.isFocused() && emotionField.charTyped(ch, mod)) return true;
        if (weightField.isFocused() && weightField.charTyped(ch, mod)) return true;
        if (mapEditor.mapField.isFocused() && mapEditor.mapField.charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    @Override
    public boolean keyReleased(int key, int sc, int mod) {
        if (goldField.isFocused() && goldField.keyReleased(key, sc, mod)) return true;
        if (emotionField.isFocused() && emotionField.keyReleased(key, sc, mod)) return true;
        if (weightField.isFocused() && weightField.keyReleased(key, sc, mod)) return true;
        if (mapEditor.mapField.isFocused() && mapEditor.mapField.keyReleased(key, sc, mod)) return true;
        return super.keyReleased(key, sc, mod);
    }
}
