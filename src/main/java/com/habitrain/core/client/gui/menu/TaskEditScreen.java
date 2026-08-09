package com.habitrain.core.client.gui.menu;

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
    private static final int FOOTER_H = 38;
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
    private EditBox goldField, emotionField, weightField, shopPriceField;
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
        this.remoteEditable = MenuPermissions.canEditRemoteConfigs();
        this.saveController = new TaskSaveController(def, cfg, remoteEditable);
    }

    @Override
    protected void init() {
        super.init();
        Font f = font;

        enableBtn = Button.builder(makeEnableText(), b -> {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
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

        shopPriceField = new EditBox(f, -10000, -10000, 60, 14, Component.literal(""));
        shopPriceField.setMaxLength(8);
        if (cfg.hasShopPrice) shopPriceField.setValue(String.valueOf(cfg.shopPrice));
        shopPriceField.setHint(Component.literal("默认"));
        shopPriceField.setEditable(remoteEditable);

        int centerX = width / 2;
        int btnY = height - FOOTER_H + 6;

        saveBtn = Button.builder(Component.literal("§a✔ 保存修改"), b -> {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return;
            }
            saveController.syncFields(goldField, emotionField, weightField, shopPriceField, mapEditor.mapField);
            saveController.saveCurrent();
            TaskSaveController.showMessage("§a✔ 任务「" + def.getDisplayName() + "」已保存！");
        }).bounds(centerX - 155, btnY, 90, 20).build();
        addRenderableWidget(saveBtn);

        saveReturnBtn = Button.builder(Component.literal("§b✔ 保存并返回"), b -> {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return;
            }
            saveController.syncFields(goldField, emotionField, weightField, shopPriceField, mapEditor.mapField);
            saveController.saveCurrent();
            goBack();
        }).bounds(centerX - 58, btnY, 100, 20).build();
        addRenderableWidget(saveReturnBtn);

        resetBtn = Button.builder(Component.literal("§7↺ 重置默认"), b -> {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return;
            }
            saveController.resetDefault();
            clearWidgets();
            init();
        }).bounds(centerX + 50, btnY, 80, 20).build();
        addRenderableWidget(resetBtn);

        topBackBtn = Button.builder(Component.literal("§7← 返回列表"), b -> goBack())
                .bounds(8, 4, 74, 16).build();
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

    private void recalcContentHeight() {
        contentHeight = 420 + (isBlackoutExclusiveTask() ? ROW_H : 0);
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
        MenuTheme.drawBackdrop(g, width, height, modeAccentColor);
        MenuTheme.editorHeader(g, font, width, def.getDisplayName(),
                modeDisplayName + "  /  " + def.getFullId(), modeAccentColor);
        MenuTheme.editorFooter(g, width, height, FOOTER_H);
        super.render(g, mx, my, delta);

        int bodyTop = getBodyTop();
        int bodyBot = getBodyBottom();
        if (!remoteEditable) {
            String readOnly = "只读 · 仅 OP 可修改";
            g.drawString(font, readOnly, width - PAD - font.width(readOnly), 10, MenuTheme.DANGER, false);
        }

        int leftX = PAD;
        int leftW = leftPanelWidth();
        int rightX = rightPanelX();
        int rightW = rightPanelWidth();
        renderControlPanel(g, mx, my, delta, leftX, bodyTop, leftW, bodyBot - bodyTop);

        g.enableScissor(rightX, bodyTop, rightX + rightW, bodyBot);
        int contentStartY = bodyTop - (int) scrollOffset;
        int curY = contentStartY;
        curY = renderRewardCard(g, mx, my, delta, rightX, curY, rightW);
        curY += SECTION_GAP;
        curY = renderMapCard(g, mx, my, delta, rightX, curY, rightW);
        curY += SECTION_GAP;
        curY = renderInfoCard(g, rightX, curY, rightW);
        contentHeight = curY - contentStartY;

        g.disableScissor();

        int bodyH = getBodyHeight();
        if (contentHeight > bodyH) {
            int thumbH = Math.max(20, bodyH * bodyH / contentHeight);
            int thumbY = bodyTop + (int) ((float) scrollOffset / (contentHeight - bodyH) * (bodyH - thumbH));
            int sx = rightX + rightW - SCROLLBAR_W;
            g.fill(sx, bodyTop, sx + SCROLLBAR_W, bodyBot, MenuTheme.BG_PANEL);
            g.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, modeAccentColor);
        }

        String tip = "修改后请保存；返回时不会丢失已即时提交的开关";
        g.drawString(font, tip, PAD, bodyBot + 9, MenuTheme.TEXT_SECONDARY, false);
    }

    private void renderControlPanel(GuiGraphics g, int mx, int my, float delta,
                                    int x, int y, int w, int h) {
        MenuTheme.panel(g, x, y, w, h);
        g.fill(x, y, x + 3, y + h, cfg.getColor());
        g.drawString(font, "任务控制", x + 14, y + 13, MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, "状态、视觉与来源", x + 14, y + 28, MenuTheme.TEXT_SECONDARY, false);

        enableBtn.setX(x + 14);
        enableBtn.setY(y + 48);
        enableBtn.setWidth(w - 28);
        enableBtn.render(g, mx, my, delta);

        int afterColor = colorPicker.render(g, font, x + 14, x + 91, y + 78, mx, my, delta);
        int metaY = afterColor + 12;
        g.fill(x + 14, metaY, x + w - 14, metaY + 1, MenuTheme.BORDER);
        metaY += 12;
        metaLine(g, x + 14, metaY, w - 28, "来源", sourceLabel());
        metaLine(g, x + 14, metaY + 19, w - 28, "分类", getCategoryName(category));
        metaLine(g, x + 14, metaY + 38, w - 28, "模式", def.getGameModeId());
        metaLine(g, x + 14, metaY + 57, w - 28, "任务 ID", def.getTaskId());
    }

    private int renderRewardCard(GuiGraphics g, int mx, int my, float delta, int x, int y, int w) {
        int rows = isBlackoutExclusiveTask() ? 4 : 3;
        int cardH = 38 + rows * 24 + 8;
        card(g, x, y, w, cardH, "奖励与权重", "留空时继承任务定义默认值", MenuTheme.ACCENT_AMBER);
        int rowY = y + 38;
        renderFieldRow(g, mx, my, delta, x, rowY, "金币奖励", goldField, "系统默认");
        rowY += 24;
        renderFieldRow(g, mx, my, delta, x, rowY, "情绪奖励", emotionField, "系统默认");
        rowY += 24;
        float effectiveWeight = cfg.hasRefreshWeight ? cfg.refreshWeight : def.getWeight();
        renderFieldRow(g, mx, my, delta, x, rowY, "刷新权重", weightField,
                String.format("当前 %.1f", effectiveWeight));
        rowY += 24;
        if (isBlackoutExclusiveTask()) {
            renderFieldRow(g, mx, my, delta, x, rowY, "商店价格", shopPriceField, "系统默认");
        }
        return y + cardH;
    }

    private int renderMapCard(GuiGraphics g, int mx, int my, float delta, int x, int y, int w) {
        int cardH = 102;
        card(g, x, y, w, cardH, "地图范围", "全部地图、白名单或黑名单", MenuTheme.ACCENT_BLUE);
        mapEditor.render(g, font, x + 12, x + 90, w - 24, y + 38, mx, my, delta);
        return y + cardH;
    }

    private int renderInfoCard(GuiGraphics g, int x, int y, int w) {
        int cardH = 208;
        card(g, x, y, w, cardH, "任务定义", "只读技术信息", MenuTheme.TEXT_DIM);
        String[][] infos = {
                {"任务名称", def.getDisplayName()}, {"完整 ID", def.getFullId()},
                {"模组来源", def.getModId()}, {"任务分类", getCategoryName(def.getCategory())},
                {"默认权重", String.format("%.1f", def.getWeight())},
                {"方块类型 ID", def.getBlockTypeId() >= 0 ? String.valueOf(def.getBlockTypeId()) : "无"},
                {"可直接获胜", def.canDirectlyWin() ? "是" : "否"},
                {"扫描方块数", String.valueOf(def.getScanBlocks().size())}
        };
        int rowY = y + 40;
        for (String[] info : infos) {
            g.drawString(font, info[0], x + 12, rowY, MenuTheme.TEXT_SECONDARY, false);
            String value = fit(info[1], w - 126);
            g.drawString(font, value, x + 112, rowY, MenuTheme.TEXT_PRIMARY, false);
            rowY += 20;
        }
        return y + cardH;
    }

    private void renderFieldRow(GuiGraphics g, int mx, int my, float delta,
                                int x, int y, String label, EditBox field, String hint) {
        g.drawString(font, label, x + 12, y + 7, MenuTheme.TEXT_SECONDARY, false);
        field.setX(x + 102);
        field.setY(y + 3);
        field.setWidth(72);
        field.render(g, mx, my, delta);
        g.drawString(font, hint, x + 184, y + 7, MenuTheme.TEXT_DIM, false);
    }

    private void card(GuiGraphics g, int x, int y, int w, int h,
                      String title, String subtitle, int accent) {
        MenuTheme.panel(g, x, y, w, h);
        g.fill(x, y, x + 3, y + h, accent);
        g.drawString(font, title, x + 12, y + 10, MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, subtitle, x + 12, y + 24, MenuTheme.TEXT_SECONDARY, false);
    }

    private void metaLine(GuiGraphics g, int x, int y, int w, String label, String value) {
        g.drawString(font, label, x, y, MenuTheme.TEXT_SECONDARY, false);
        String fitted = fit(value, w - 54);
        g.drawString(font, fitted, x + 54, y, MenuTheme.TEXT_PRIMARY, false);
    }

    private String fit(String value, int maxWidth) {
        if (value == null) return "—";
        if (font.width(value) <= maxWidth) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end) + suffix) > maxWidth) end--;
        return value.substring(0, end) + suffix;
    }

    private String sourceLabel() {
        return HabiTrainCore.MOD_ID.equals(def.getModId()) ? "HabiTrain Core" : def.getModId();
    }

    private int leftPanelWidth() {
        return Math.min(240, Math.max(210, width / 3));
    }

    private int rightPanelX() {
        return PAD + leftPanelWidth() + SECTION_GAP;
    }

    private int rightPanelWidth() {
        return Math.max(1, width - rightPanelX() - PAD);
    }

    /** 是否为停电专属任务（电话商店购买类，需要商店价格配置）。 */
    private boolean isBlackoutExclusiveTask() {
        TaskCategory cat = def.getCategory();
        return com.habitrain.core.game.blackout.BlackoutMode.BLACKOUT_GOOD.equals(cat)
                || com.habitrain.core.game.blackout.BlackoutMode.BLACKOUT_BAD.equals(cat);
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
        if (mx >= rightPanelX() && my >= bodyTop && my < bodyBot) {
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

        int sx = rightPanelX() + rightPanelWidth() - SCROLLBAR_W;
        if (mx >= sx && mx < sx + SCROLLBAR_W && my >= bodyTop && my < bodyBot) {
            draggingScroll = true;
            dragStartY = my;
            dragStartOff = scrollOffset;
            return true;
        }

        if (my < bodyTop || my >= bodyBot) return false;

        goldField.setFocused(false);
        emotionField.setFocused(false);
        weightField.setFocused(false);
        shopPriceField.setFocused(false);
        mapEditor.mapField.setFocused(false);

        if (mx >= enableBtn.getX() && mx < enableBtn.getX() + enableBtn.getWidth()
                && my >= enableBtn.getY() && my < enableBtn.getY() + 20) {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            enableBtn.mouseClicked(mx, my, button);
            return true;
        }

        if (colorPicker.handleMouseClick(mx, my, button)) return true;
        if (mapEditor.handleMouseClick(mx, my, button)) return true;

        if (mx >= goldField.getX() && mx < goldField.getX() + goldField.getWidth()
                && my >= goldField.getY() && my < goldField.getY() + 14) {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            goldField.setFocused(true);
            return true;
        }
        if (mx >= emotionField.getX() && mx < emotionField.getX() + emotionField.getWidth()
                && my >= emotionField.getY() && my < emotionField.getY() + 14) {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            emotionField.setFocused(true);
            return true;
        }
        if (mx >= weightField.getX() && mx < weightField.getX() + weightField.getWidth()
                && my >= weightField.getY() && my < weightField.getY() + 14) {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            weightField.setFocused(true);
            return true;
        }
        if (isBlackoutExclusiveTask()
                && mx >= shopPriceField.getX() && mx < shopPriceField.getX() + shopPriceField.getWidth()
                && my >= shopPriceField.getY() && my < shopPriceField.getY() + 14) {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            shopPriceField.setFocused(true);
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
        if (shopPriceField.isFocused() && shopPriceField.keyPressed(key, sc, mod)) return true;
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
        if (shopPriceField.isFocused() && shopPriceField.charTyped(ch, mod)) return true;
        if (mapEditor.mapField.isFocused() && mapEditor.mapField.charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    @Override
    public boolean keyReleased(int key, int sc, int mod) {
        if (goldField.isFocused() && goldField.keyReleased(key, sc, mod)) return true;
        if (emotionField.isFocused() && emotionField.keyReleased(key, sc, mod)) return true;
        if (weightField.isFocused() && weightField.keyReleased(key, sc, mod)) return true;
        if (shopPriceField.isFocused() && shopPriceField.keyReleased(key, sc, mod)) return true;
        if (mapEditor.mapField.isFocused() && mapEditor.mapField.keyReleased(key, sc, mod)) return true;
        return super.keyReleased(key, sc, mod);
    }
}
