package com.habitrain.core.client.gui.config;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.ShaderWhitelistScreen;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * "全局设置" Tab — DLC 概率滑块 + 警长除数 + 光影白名单入口 + 小游戏总开关。
 */
public class GlobalTabScreen {

    private static final int PAD = 16;
    private static final int ROW_H = 32;
    private static final int SLIDER_H = 12;
    private static final float MIN_TARGET = 0.10f;
    private static final float MAX_TARGET = 0.80f;
    private static final float STEP = 0.05f;

    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;

    private float dlcTarget;
    private boolean mgGlobal;
    private int sheriffDivisor;

    private EditBox sheriffField;
    private boolean draggingSlider = false;
    private int sliderX, sliderY, sliderW;

    private Button shaderBtn, mgToggleBtn, sheriffApplyBtn;
    /** 标记 widgets 是否已初始化 (S10-014) */
    private boolean widgetsInitialized = false;

    public GlobalTabScreen(ConfigRootScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        this.dlcTarget = ConfigManager.getInstance().getDlcProbabilityTarget();
        this.mgGlobal = ConfigManager.getInstance().isMinigameGlobalEnabled();
        this.sheriffDivisor = ConfigManager.getInstance().getSheriffCountDivisor();
    }

    /**
     * 一次性初始化 widgets (S10-014)。
     * 替代在 render() 中每帧检查 null 创建的模式。
     */
    private void ensureWidgetsInitialized() {
        if (widgetsInitialized) return;
        widgetsInitialized = true;

        sheriffField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        sheriffField.setMaxLength(3);
        sheriffField.setValue(String.valueOf(sheriffDivisor));
        sheriffField.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        sheriffField.setEditable(editable);

        shaderBtn = Button.builder(Component.literal("光影白名单设置"), b -> {
            Minecraft.getInstance().setScreen(new ShaderWhitelistScreen(root));
        }).bounds(-10000, -10000, 140, 20).build();
        shaderBtn.active = editable;

        mgToggleBtn = Button.builder(mgToggleLabel(), b -> {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return; }
            mgGlobal = !mgGlobal;
            ConfigManager.getInstance().setMinigameGlobalEnabled(mgGlobal);
            ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer());
            mgToggleBtn.setMessage(mgToggleLabel());
        }).bounds(-10000, -10000, 120, 20).build();
        mgToggleBtn.active = editable;

        sheriffApplyBtn = Button.builder(Component.literal("应用"), b -> {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return; }
            try {
                int v = Integer.parseInt(sheriffField.getValue().trim());
                sheriffDivisor = Math.max(1, v);
                ConfigManager.getInstance().setSheriffCountDivisor(sheriffDivisor);
            } catch (NumberFormatException ignored) {}
        }).bounds(-10000, -10000, 50, 18).build();
        sheriffApplyBtn.active = editable;
    }

    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        // 一次性初始化 widgets (S10-014)，从 render() 移至 init() 语义
        ensureWidgetsInitialized();

        int cy = y + 8;
        int labelX = x + PAD;
        int sliderMaxW = w - PAD * 2;

        // ===== DLC 概率滑块 =====
        g.drawString(font, Component.literal("§e§lDLC 任务目标占比"), labelX, cy, SharedGuiKit.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal("§7系统自动平衡 DLC 与原版任务的出现概率（10%~80%）"),
                labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += 18;

        sliderW = Math.min(360, sliderMaxW);
        sliderX = labelX;
        sliderY = cy;
        int trackTop = sliderY + (SLIDER_H - 6) / 2;
        int trackBot = trackTop + 6;

        // 滑条轨道
        g.fill(sliderX, trackTop, sliderX + sliderW, trackBot, 0x44FFFFFF);
        int tx = thumbX();
        float fillPct = (dlcTarget - MIN_TARGET) / (MAX_TARGET - MIN_TARGET);
        if (fillPct > 0.001f) {
            int fillColor;
            if (fillPct < 0.25f) fillColor = 0xAAFF5555;
            else if (fillPct < 0.5f) fillColor = 0xAAFFAA00;
            else if (fillPct < 0.75f) fillColor = 0xAA55FF55;
            else fillColor = 0xAA55AAFF;
            g.fill(sliderX, trackTop, tx, trackBot, fillColor);
        }
        int tc = draggingSlider ? 0xFFFFFFFF : 0xCCFFFFFF;
        g.fill(tx - 5, sliderY, tx + 5, sliderY + SLIDER_H, tc);
        g.fill(tx - 2, sliderY + 4, tx + 2, sliderY + SLIDER_H - 4, 0xFF333333);

        // 刻度
        for (int p = 10; p <= 80; p += 10) {
            float pf = (p / 100f - MIN_TARGET) / (MAX_TARGET - MIN_TARGET);
            int px = sliderX + (int) (pf * sliderW);
            g.fill(px, trackBot + 2, px + 1, trackBot + 2 + (p == 50 ? 8 : 4),
                    p == 50 ? 0x88FFFF00 : 0x44FFFFFF);
        }
        // 当前值
        String valStr = String.format("§6§l%d%%", Math.round(dlcTarget * 100));
        g.drawString(font, valStr, sliderX + sliderW + 8, sliderY + 1, 0xFFFFFFFF, false);
        cy = sliderY + SLIDER_H + 12;

        // ===== 警长除数 =====
        g.fill(labelX - 2, cy - 2, labelX + sliderW + 2, cy - 1, 0x20FFFFFF);
        cy += 6;
        g.drawString(font, Component.literal("§e§l警长数量除数"), labelX, cy, SharedGuiKit.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, "§7警长数量 = floor(玩家数 / 除数)，默认 6", labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += 18;
        g.drawString(font, "除数:", labelX, cy + 2, 0xFFCCCCCC, false);
        sheriffField.setX(labelX + 50); sheriffField.setY(cy); sheriffField.setWidth(60);
        sheriffField.render(g, mx, my, delta);
        sheriffApplyBtn.setX(labelX + 120); sheriffApplyBtn.setY(cy - 1); sheriffApplyBtn.setWidth(50);
        sheriffApplyBtn.render(g, mx, my, delta);
        cy += ROW_H;

        // ===== 小游戏总开关 =====
        g.fill(labelX - 2, cy - 2, labelX + sliderW + 2, cy - 1, 0x20FFFFFF);
        cy += 6;
        g.drawString(font, Component.literal("§e§l小游戏任务总开关"), labelX, cy, SharedGuiKit.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal("§7关闭后 SRE 将不再分配任何小游戏任务"), labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += 18;
        mgToggleBtn.setX(labelX); mgToggleBtn.setY(cy); mgToggleBtn.setWidth(120);
        mgToggleBtn.render(g, mx, my, delta);
        cy += ROW_H;

        // ===== 光影白名单 =====
        g.fill(labelX - 2, cy - 2, labelX + sliderW + 2, cy - 1, 0x20FFFFFF);
        cy += 6;
        g.drawString(font, Component.literal("§e§l光影白名单"), labelX, cy, SharedGuiKit.ACCENT_CYAN, false);
        cy += 18;
        boolean swEnabled = ConfigManager.getInstance().isShaderWhitelistEnabled();
        g.drawString(font, "§7当前: " + (swEnabled ? "§a已启用" : "§c已禁用") +
                " §7(" + ConfigManager.getInstance().getShaderWhitelist().size() + " 个)", labelX, cy, 0xFFAAAAAA, false);
        cy += 18;
        shaderBtn.setX(labelX); shaderBtn.setY(cy); shaderBtn.setWidth(140);
        shaderBtn.render(g, mx, my, delta);

        if (!editable) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改"),
                    labelX, y + h - 14, 0xFF5555, false);
        }
    }

    private Component mgToggleLabel() {
        return Component.literal(mgGlobal ? "§a小游戏：已启用" : "§c小游戏：已停用");
    }

    private int thumbX() {
        float pct = (dlcTarget - MIN_TARGET) / (MAX_TARGET - MIN_TARGET);
        return sliderX + (int) (pct * sliderW);
    }

    private float valFromMouse(double mx) {
        float rel = Mth.clamp((float) ((mx - sliderX) / sliderW), 0f, 1f);
        float raw = MIN_TARGET + rel * (MAX_TARGET - MIN_TARGET);
        return Math.round(raw / STEP) * STEP;
    }

    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (sheriffField != null && sheriffField.mouseClicked(mx, my, btn)) return true;
        if (shaderBtn != null && shaderBtn.mouseClicked(mx, my, btn)) return true;
        if (mgToggleBtn != null && mgToggleBtn.mouseClicked(mx, my, btn)) return true;
        if (sheriffApplyBtn != null && sheriffApplyBtn.mouseClicked(mx, my, btn)) return true;
        // 滑条
        int tx = thumbX();
        boolean onSlider = mx >= sliderX - 4 && mx <= sliderX + sliderW + 4 && my >= sliderY - 4 && my <= sliderY + SLIDER_H + 4;
        if (onSlider && editable) {
            draggingSlider = true;
            float nv = valFromMouse(mx);
            if (nv != dlcTarget) {
                dlcTarget = nv;
                ConfigManager.getInstance().setDlcProbabilityTarget(dlcTarget);
            }
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (draggingSlider) {
            float nv = valFromMouse(mx);
            if (nv != dlcTarget) {
                dlcTarget = nv;
                ConfigManager.getInstance().setDlcProbabilityTarget(dlcTarget);
            }
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        return false;
    }

    public boolean keyPressed(int key, int scan, int mod) {
        if (sheriffField != null && sheriffField.isFocused() && sheriffField.keyPressed(key, scan, mod)) return true;
        return false;
    }

    public boolean charTyped(char ch, int mod) {
        if (sheriffField != null && sheriffField.isFocused() && sheriffField.charTyped(ch, mod)) return true;
        return false;
    }
}