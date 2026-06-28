package com.habitrain.taskapi.client.gui;

import com.habitrain.taskapi.api.HabiTaskCategory;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * =========================================================
 *  哈比列车任务系统 - 模式选择主界面 (原版风格)
 * =========================================================
 *
 * 导航层级:
 *   HabiConfigScreen (模式选择) ← 当前
 *       → HabiTaskListScreen (任务列表)
 *           → HabiTaskEditScreen (任务详情)
 *
 * 功能:
 *   - 2x2 卡片网格展示四个游戏模式
 *   - 每个卡片显示: 图标、名称、任务数量、启用比例
 *   - 搜索过滤
 *   - 全局保存功能
 */
public class HabiConfigScreen extends Screen {

    // ====== 布局常量 ======
    private static final int PAD = 10;
    private static final int TOP_H = 48;         // 顶部区域高度
    private static final int BOT_H = 32;         // 底部按钮区域高度
    private static final int CARD_GAP = 10;      // 卡片间距
    private static final int CARD_H = 110;       // 卡片高度
    private static final int CORNER_R = 3;       // 圆角矩形边条宽度

    // ====== 模式卡片定义 ======
    private record ModeCard(
            HabiTaskCategory category,
            String icon,
            String name,
            int accentColor,       // 强调色 ARGB
            int bgColor            // 背景色 ARGB
    ) {}

    private static final List<ModeCard> MODE_CARDS = List.of(
            new ModeCard(HabiTaskCategory.MURDER, "🔪", "谋杀模式",
                    0xFFFF5555, 0x22FF0000),
            new ModeCard(HabiTaskCategory.REPAIR, "🔧", "修机模式",
                    0xFF55BBFF, 0x2200AAFF),
            new ModeCard(HabiTaskCategory.ALL, "⭐", "通用任务",
                    0xFF55FF55, 0x2200AA00),
            new ModeCard(HabiTaskCategory.CUSTOM, "📦", "自定义任务",
                    0xFFFFAA00, 0x22FF8800)
    );

    // ====== 控件 ======
    private final Screen parent;
    private EditBox searchBox;
    private String searchText = "";

    // ====== 构造 ======
    public HabiConfigScreen(Screen parent) {
        super(Component.literal("§l⚙ 哈比列车任务系统配置"));
        this.parent = parent;
    }

    // =========================================================
    //  初始化
    // =========================================================

    @Override
    protected void init() {
        super.init();
        int centerX = width / 2;

        // ---- 搜索框 ----
        searchBox = new EditBox(font, PAD, TOP_H - 16, Math.min(200, width / 3), 14, Component.literal(""));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("🔍 搜索任务名称或ID..."));
        searchBox.setResponder(t -> searchText = t.trim().toLowerCase());
        addRenderableWidget(searchBox);

        // ---- 底部按钮 ----
        int btnY = height - BOT_H + 6;

        // 保存配置
        addRenderableWidget(Button.builder(
                Component.literal("§a✔ 保存"), b -> {
                    HabiConfigManager.getInstance().save();
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                                Component.literal("§a✔ 任务配置已保存！"), true);
                    }
                }
        ).bounds(centerX - 180, btnY, 70, 20).build());

        // ★ 全局设置入口
        addRenderableWidget(Button.builder(
                Component.literal("§e⚙ 全局设置"), b ->
                    Minecraft.getInstance().setScreen(new HabiGlobalSettingsScreen(this))
        ).bounds(centerX - 100, btnY, 70, 20).build());

        // ★ Iris 光影白名单
        addRenderableWidget(Button.builder(
                Component.literal("§b✧ 光影白名单"), b ->
                    Minecraft.getInstance().setScreen(new ShaderWhitelistScreen(this))
        ).bounds(centerX - 20, btnY, 80, 20).build());

        // 关闭
        addRenderableWidget(Button.builder(
                Component.literal("§c✖ 关闭"), b -> onClose()
        ).bounds(centerX + 70, btnY, 60, 20).build());
    }

    // =========================================================
    //  渲染
    // =========================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        Font f = font;

        // ---- 顶部区域 ----
        // 标题
        g.drawString(f, Component.literal("§l⚙ 哈比列车任务系统配置"), PAD, 6, 0xFFFFFF, false);

        // 副标题
        String subtitle = "§7选择游戏模式以管理对应的任务";
        g.drawString(f, Component.literal(subtitle), PAD, 21, 0x888888, false);

        // DLC目标占比指示（在副标题右侧）
        int targetPct = Math.round(HabiConfigManager.getInstance().getDlcProbabilityTarget() * 100);
        g.drawString(f, Component.literal("§aDLC目标 §e" + targetPct + "%"),
                PAD + font.width(subtitle) + 14, 21, 0, false);

        // 统计信息 (右侧)
        long total = HabiTaskRegistry.size();
        long enabled = HabiConfigManager.getInstance().getAllConfigs().values().stream()
                .filter(e -> e.enabled).count();
        long dlcCount = HabiTaskRegistry.getAll().stream()
                .filter(t -> !"habitrain_taskapi".equals(t.getModId())).count();

        String stats = String.format("§7共 §e%d §7个任务  |  已启用 §a%d§7/§e%d", total, enabled, total);
        if (dlcCount > 0) {
            stats += String.format("  |  §e%d §7个外部任务", dlcCount);
        }
        int statsW = f.width(stats);
        g.drawString(f, Component.literal(stats), width - PAD - statsW, 6, 0, false);

        // 搜索框下方分割线
        g.fill(PAD, TOP_H, width - PAD, TOP_H + 1, 0x30FFFFFF);

        // ---- 模式卡片区域 ----
        int availW = width - PAD * 2;
        int cardW = (availW - CARD_GAP) / 2;   // 两列等宽
        int cardAreaY = TOP_H + 8;
        int col1X = PAD;
        int col2X = PAD + cardW + CARD_GAP;

        int displayIdx = 0;
        for (var modeCard : MODE_CARDS) {
            // 获取该模式的可用任务
            List<HabiTaskDefinition> tasks = getTasksForMode(modeCard.category);

            // 搜索过滤
            if (!searchText.isEmpty()) {
                tasks = filterBySearch(tasks);
                if (tasks.isEmpty()) continue;
            }

            int col = displayIdx % 2;
            int row = displayIdx / 2;
            int cx = col == 0 ? col1X : col2X;
            int cy = cardAreaY + row * (CARD_H + CARD_GAP);

            // 确保卡片不超出底部按钮区域
            if (cy + CARD_H > height - BOT_H - 4) break;

            renderModeCard(g, f, modeCard, tasks, cx, cy, cardW, CARD_H, mx, my);
            displayIdx++;
        }

        // 搜索无结果
        if (displayIdx == 0) {
            String noResult = "§7没有找到匹配 \"§f" + searchText + "§7\" 的任务";
            g.drawString(f, Component.literal(noResult),
                    width / 2 - f.width(noResult) / 2, cardAreaY + 30, 0, false);
        }

        // ---- 底部提示 ----
        String tip = "§7提示: 点击模式卡片进入任务列表 → 点击任务编辑详细属性";
        g.drawString(f, Component.literal(tip),
                width / 2 - f.width(tip) / 2, height - BOT_H - 4, 0x555555, false);

        // ---- 底部和顶部装饰线 ----
        g.fill(PAD, height - BOT_H, width - PAD, height - BOT_H + 1, 0x30FFFFFF);
    }

    /** 渲染单个模式卡片 */
    private void renderModeCard(GuiGraphics g, Font f, ModeCard card,
                                List<HabiTaskDefinition> tasks, int x, int y,
                                int w, int h, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        int cardColor = card.bgColor;
        if (hover) {
            // 悬停变亮
            cardColor = (cardColor & 0x00FFFFFF) | 0x33FFFFFF;
        }

        // ---- 卡片背景 ----
        g.fill(x, y, x + w, y + h, cardColor);

        // 悬停边框
        if (hover) {
            int border = 0x55FFFFFF;
            g.fill(x, y, x + w, y + 1, border);                 // 上
            g.fill(x, y + h - 1, x + w, y + h, border);         // 下
            g.fill(x, y, x + 1, y + h, border);                 // 左
            g.fill(x + w - 1, y, x + w, y + h, border);         // 右
        }

        // ---- 左侧彩色竖条 ----
        g.fill(x, y + 8, x + CORNER_R, y + h - 8, card.accentColor);

        // ---- 内容 ----
        int tx = x + 12;

        // 图标 + 标题
        String title = card.icon + " §l" + card.name;
        int titleColor = hover ? 0xFFFFFF : 0xDDDDDD;
        g.drawString(f, Component.literal(title), tx, y + 12, titleColor, false);

        // 任务数量统计
        long builtinCount = tasks.stream()
                .filter(t -> "habitrain_taskapi".equals(t.getModId())).count();
        long dlcCount = tasks.size() - builtinCount;

        String countStr = "§7共 §e" + tasks.size() + " §7个任务";
        if (dlcCount > 0) {
            countStr += "  §e[含" + dlcCount + "个外部]";
        }
        g.drawString(f, Component.literal(countStr), tx, y + 32, 0, false);

        // 启用统计
        long enabledCount = tasks.stream()
                .filter(t -> {
                    var cfg = HabiConfigManager.getInstance().getTaskConfig(t.getFullId());
                    return cfg != null && cfg.enabled;
                }).count();
        String enabledStr = String.format("§7已启用: §a%d§7/§e%d", enabledCount, tasks.size());
        g.drawString(f, Component.literal(enabledStr), tx, y + 47, 0, false);

        // ---- 进度条 ----
        if (!tasks.isEmpty()) {
            int barW = w - 30;
            int barH = 5;
            int barX = tx;
            int barY = y + 65;

            // 背景
            g.fill(barX, barY, barX + barW, barY + barH, 0x44FFFFFF);
            // 填充
            float pct = (float) enabledCount / tasks.size();
            int fillW = (int) (barW * pct);
            if (fillW > 0) {
                g.fill(barX, barY, barX + fillW, barY + barH,
                        (card.accentColor & 0xFFFFFF) | 0xAA000000);
            }
        }

        // ---- 外部任务标记 ----
        if (dlcCount > 0) {
            g.drawString(f, Component.literal("§e✦ 含外部模组任务"),
                    tx, y + 78, 0, false);
        }

        // ---- 右下角操作提示 ----
        String action = hover ? "§a§l点击进入 ▶" : "§7点击管理";
        g.drawString(f, Component.literal(action),
                x + w - f.width("点击管理") - 12, y + h - 12, 0, false);
    }

    // =========================================================
    //  鼠标事件
    // =========================================================

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;

        int availW = width - PAD * 2;
        int cardW = (availW - CARD_GAP) / 2;
        int cardAreaY = TOP_H + 8;
        int col1X = PAD;
        int col2X = PAD + cardW + CARD_GAP;

        int displayIdx = 0;
        for (var modeCard : MODE_CARDS) {
            List<HabiTaskDefinition> tasks = getTasksForMode(modeCard.category);
            if (!searchText.isEmpty()) {
                tasks = filterBySearch(tasks);
                if (tasks.isEmpty()) { displayIdx++; continue; }
            }

            int col = displayIdx % 2;
            int row = displayIdx / 2;
            int cx = col == 0 ? col1X : col2X;
            int cy = cardAreaY + row * (CARD_H + CARD_GAP);

            if (cy + CARD_H > height - BOT_H - 4) break;

            if (mx >= cx && mx < cx + cardW && my >= cy && my < cy + CARD_H) {
                if (!tasks.isEmpty()) {
                    Minecraft.getInstance().setScreen(
                            new HabiTaskListScreen(this, modeCard.category,
                                    modeCard.icon + " " + modeCard.name,
                                    modeCard.accentColor));
                    return true;
                }
            }
            displayIdx++;
        }

        return false;
    }

    // =========================================================
    //  键盘事件
    // =========================================================

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.keyPressed(key, sc, mod)) {
            return true;
        }
        if (key == 256) { // ESC
            onClose();
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(ch, mod)) {
            return true;
        }
        return super.charTyped(ch, mod);
    }

    @Override
    public void onClose() {
        HabiConfigManager.getInstance().save();
        Minecraft.getInstance().setScreen(parent);
    }

    // =========================================================
    //  工具方法
    // =========================================================

    private List<HabiTaskDefinition> getTasksForMode(HabiTaskCategory category) {
        List<HabiTaskDefinition> result = new ArrayList<>();
        for (var def : HabiTaskRegistry.getAll()) {
            if (def.getCategory() == category || def.getCategory() == HabiTaskCategory.ALL) {
                // ALL 卡片只显示纯 ALL 任务
                if (category == HabiTaskCategory.ALL && def.getCategory() != HabiTaskCategory.ALL) continue;
                // CUSTOM 卡片只显示纯 CUSTOM 任务
                if (category == HabiTaskCategory.CUSTOM && def.getCategory() != HabiTaskCategory.CUSTOM) continue;
                result.add(def);
            }
        }
        result.sort(Comparator.comparingInt((HabiTaskDefinition d) ->
                "habitrain_taskapi".equals(d.getModId()) ? 0 : 1)
                .thenComparing(HabiTaskDefinition::getFullId));
        return result;
    }

    private List<HabiTaskDefinition> filterBySearch(List<HabiTaskDefinition> tasks) {
        return tasks.stream()
                .filter(t -> t.getDisplayName().toLowerCase().contains(searchText)
                        || t.getFullId().toLowerCase().contains(searchText)
                        || t.getModId().toLowerCase().contains(searchText))
                .collect(Collectors.toList());
    }
}
