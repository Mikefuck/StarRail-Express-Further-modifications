package com.habitrain.taskapi.client.gui;

import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * =========================================================
 *  哈比列车任务系统 - 全局设置界面
 * =========================================================
 *
 * ★ 自动平衡系统
 *   系统在每次构建任务池时，统计实际可用的原版和DLC任务数量，
 *   自动计算权重乘数，使DLC集体概率稳定在目标比例。
 *   加新DLC模组后无需任何配置，自动适应。
 *
 * ★ 唯一可调参数：目标占比（默认50%）
 *   大多数情况保持默认即可，极端情况（如DLC任务太多/太少）可微调。
 */
public class HabiGlobalSettingsScreen extends Screen {

    private static final float MIN_TARGET = 0.10f;
    private static final float MAX_TARGET = 0.80f;
    private static final float STEP = 0.05f;
    private static final float DEFAULT_TARGET = 0.50f;

    private static final int PAD = 15;
    private static final int TITLE_Y = 10;

    private final Screen parent;
    private float currentTarget;
    private boolean dragging = false;

    private int sliderX, sliderY, sliderW, sliderH = 12;
    private int trackTop, trackBot;

    private Button decreaseBtn, increaseBtn, resetBtn, backBtn;
    private Button autoReplayBtn;

    public HabiGlobalSettingsScreen(Screen parent) {
        super(Component.literal("§l⚙ 全局设置 — 自动平衡系统"));
        this.parent = parent;
        this.currentTarget = HabiConfigManager.getInstance().getDlcProbabilityTarget();
    }

    @Override
    protected void init() {
        super.init();
        int centerX = width / 2;

        sliderW = Math.min(340, width - PAD * 4);
        sliderX = centerX - sliderW / 2;
        sliderY = 62;
        trackTop = sliderY + (sliderH - 6) / 2;
        trackBot = trackTop + 6;

        int ctrlY = sliderY + sliderH + 14;
        int btnH = 20;

        decreaseBtn = addRenderableWidget(Button.builder(
                Component.literal("§c◀ -5%"), b -> adjust(-STEP)
        ).bounds(centerX - 115, ctrlY, 54, btnH).build());

        increaseBtn = addRenderableWidget(Button.builder(
                Component.literal("+5% ▶"), b -> adjust(STEP)
        ).bounds(centerX + 61, ctrlY, 54, btnH).build());

        resetBtn = addRenderableWidget(Button.builder(
                Component.literal("§7⟲ 恢复50%"), b -> {
                    currentTarget = DEFAULT_TARGET;
                    saveAndUpdate();
                    updateBtn();
                }
        ).bounds(centerX - 56, ctrlY + 26, 112, btnH).build());

        // ---- 自动录制回放切换 ----
        boolean autoReplay = HabiConfigManager.getInstance().isAutoReplayRecording();
        autoReplayBtn = addRenderableWidget(Button.builder(
                Component.literal(autoReplay ? "§a✔ 自动录制回放" : "§c✖ 自动录制回放"), b -> {
                    HabiConfigManager cfg = HabiConfigManager.getInstance();
                    boolean newVal = !cfg.isAutoReplayRecording();
                    cfg.setAutoReplayRecording(newVal);
                    b.setMessage(Component.literal(newVal ? "§a✔ 自动录制回放" : "§c✖ 自动录制回放"));
                }
        ).bounds(centerX - 60, ctrlY + 50, 120, 20).build());

        backBtn = addRenderableWidget(Button.builder(
                Component.literal("§7← 返回"), b -> {
                    HabiConfigManager.getInstance().save();
                    Minecraft.getInstance().setScreen(parent);
                }
        ).bounds(PAD, height - 28, 80, 20).build());

        updateBtn();
    }

    private void adjust(float delta) {
        float nv = currentTarget + delta;
        nv = Math.round(nv / STEP) * STEP;
        nv = Mth.clamp(nv, MIN_TARGET, MAX_TARGET);
        if (nv != currentTarget) {
            currentTarget = nv;
            saveAndUpdate();
            updateBtn();
        }
    }

    private void saveAndUpdate() {
        HabiConfigManager.getInstance().setDlcProbabilityTarget(currentTarget);
    }

    private void updateBtn() {
        decreaseBtn.active = currentTarget > MIN_TARGET;
        increaseBtn.active = currentTarget < MAX_TARGET;
        resetBtn.active = Math.abs(currentTarget - DEFAULT_TARGET) > 0.01f;
    }

    // ==============================
    //  鼠标事件
    // ==============================

    private float valFromMouse(double mx) {
        float rel = Mth.clamp((float) ((mx - sliderX) / sliderW), 0f, 1f);
        float raw = MIN_TARGET + rel * (MAX_TARGET - MIN_TARGET);
        return Math.round(raw / STEP) * STEP;
    }

    private int thumbX() {
        float pct = (currentTarget - MIN_TARGET) / (MAX_TARGET - MIN_TARGET);
        return sliderX + (int) (pct * sliderW);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;
        int tx = thumbX();
        boolean onThumb = mx >= tx - 6 && mx <= tx + 6 && my >= sliderY - 4 && my <= sliderY + sliderH + 4;
        boolean onTrack = mx >= sliderX && mx <= sliderX + sliderW && my >= sliderY - 4 && my <= sliderY + sliderH + 4;
        if (onThumb || onTrack) {
            dragging = true;
            float nv = valFromMouse(mx);
            if (nv != currentTarget) { currentTarget = nv; saveAndUpdate(); updateBtn(); }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging) {
            float nv = valFromMouse(mx);
            if (nv != currentTarget) { currentTarget = nv; saveAndUpdate(); updateBtn(); }
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        dragging = false;
        return super.mouseReleased(mx, my, btn);
    }

    // ==============================
    //  渲染
    // ==============================

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);
        Font f = font;
        HabiConfigManager cfg = HabiConfigManager.getInstance();

        int registeredDlc = (int) cfg.countDlcTasks();
        int registeredOrig = Math.max(1, (int) cfg.countOriginalTasks());

        // ---- 标题 ----
        g.drawString(f, Component.literal("§l⚙ DLC任务自动平衡系统"), PAD, TITLE_Y, 0xFFFFFF, false);

        // 自动平衡说明
        g.drawString(f, Component.literal("§7✓ 系统自动根据实际进池的任务数平衡概率，加新DLC模组无需调配置"),
                PAD, TITLE_Y + 14, 0x888888, false);
        g.drawString(f, Component.literal("§7✓ 默认50%即可，以下滑条只在需要微调时使用"),
                PAD, TITLE_Y + 26, 0x888888, false);

        // 分割线
        g.fill(PAD, TITLE_Y + 42, width - PAD, TITLE_Y + 43, 0x30FFFFFF);

        // ---- 滑条 ----
        // 标签
        String valStr = String.format("§6§l%d%%", Math.round(currentTarget * 100));
        g.drawString(f, Component.literal("§eDLC目标占比"), sliderX, sliderY - 20, 0xFFFFFF, false);
        g.drawString(f, Component.literal(valStr), sliderX + sliderW - f.width(valStr) + 22, sliderY - 20, 0, false);

        // 轨道
        g.fill(sliderX, trackTop, sliderX + sliderW, trackBot, 0x44FFFFFF);
        int tx = thumbX();
        float fillPct = (currentTarget - MIN_TARGET) / (MAX_TARGET - MIN_TARGET);
        if (fillPct > 0.001f) {
            int fillColor;
            if (fillPct < 0.25f) fillColor = 0xAAFF5555;
            else if (fillPct < 0.5f) fillColor = 0xAAFFAA00;
            else if (fillPct < 0.75f) fillColor = 0xAA55FF55;
            else fillColor = 0xAA55AAFF;
            g.fill(sliderX, trackTop, tx, trackBot, fillColor);
        }
        int tc = dragging ? 0xFFFFFFFF : 0xCCFFFFFF;
        g.fill(tx - 5, sliderY, tx + 5, sliderY + sliderH, tc);
        g.fill(tx - 2, sliderY + 4, tx + 2, sliderY + sliderH - 4, 0xFF333333);

        // 刻度
        for (int p = 10; p <= 80; p += 10) {
            float pf = (p / 100f - MIN_TARGET) / (MAX_TARGET - MIN_TARGET);
            int px = sliderX + (int) (pf * sliderW);
            g.fill(px, trackBot + 2, px + 1, trackBot + 2 + (p == 50 ? 10 : 5),
                    p == 50 ? 0x88FFFF00 : 0x44FFFFFF);
        }

        // ---- 信息区 ----
        int ay = sliderY + sliderH + 58;
        g.fill(PAD, ay - 2, width - PAD, ay - 1, 0x30FFFFFF);
        ay += 6;

        // 任务注册统计
        g.drawString(f, Component.literal("§7📋 当前注册: §e" + registeredOrig + " §7个原版任务 + §e" + registeredDlc + " §7个DLC任务"),
                sliderX, ay, 0, false); ay += 14;

        // 自动平衡说明
        g.drawString(f, Component.literal("§7⚖ 平衡方式：每次生成任务时，统计实际可用数量自动计算权重"),
                sliderX, ay, 0, false); ay += 12;

        // 公式
        g.drawString(f, Component.literal("§7   autoBoost = target/(1-target) × 原版可用数 / DLC可用数"),
                sliderX, ay, 0, false); ay += 14;

        // 兼容性说明
        g.fill(PAD, ay - 2, width - PAD, ay - 1, 0x20FFFFFF); ay += 4;
        g.drawString(f, Component.literal("§a✔ 未来加新DLC模组 → 自动适应，无需修改任何配置"),
                sliderX, ay, 0, false); ay += 12;
        g.drawString(f, Component.literal("§a✔ 地图禁用某些任务 → 自动排除，不影响平衡"),
                sliderX, ay, 0, false); ay += 12;
        g.drawString(f, Component.literal("§a✔ 原版任务情绪/次数权重 → 不受影响，保留原汁原味"),
                sliderX, ay, 0, false); ay += 14;

        // 建议
        g.fill(PAD, ay - 2, width - PAD, ay - 1, 0x20FFFFFF); ay += 4;
        String tip;
        if (Math.abs(currentTarget - 0.5f) < 0.01f) tip = "§a✓ 默认50%平衡模式，DLC和原版任务各占一半";
        else if (currentTarget < 0.2f) tip = "§c⚠ DLC几乎不会出现";
        else if (currentTarget < 0.3f) tip = "§e⚠ DLC出现偏少";
        else if (currentTarget <= 0.6f) tip = "§a✓ 合理区间";
        else if (currentTarget <= 0.7f) tip = "§e↑ DLC偏多";
        else tip = "§c⚠ DLC占比过高，原版几乎不出现";
        g.drawString(f, Component.literal("§7当前: " + tip), sliderX, ay + 4, 0, false);

        // 配置文件路径
        g.drawString(f, Component.literal("§7config/habitrain_taskapi.json → dlcProbabilityTarget"),
                PAD, height - 10, 0x555555, false);

        // ---- 自动录制回放提示 ----
        String autoTip = "§7开启后，游戏开始将自动录制所有玩家回放，游戏结束自动停止（需安装 ServerReplay）";
        g.drawString(f, Component.literal(autoTip),
                width / 2 - f.width(autoTip) / 2, autoReplayBtn.getY() + 22, 0x555555, false);
    }

    @Override
    public void onClose() {
        HabiConfigManager.getInstance().save();
        Minecraft.getInstance().setScreen(parent);
    }
}
