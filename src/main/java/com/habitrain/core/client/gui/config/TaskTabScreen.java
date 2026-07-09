package com.habitrain.core.client.gui.config;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.TaskEditScreen;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.ConfigQueryService;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.game.blackout.BlackoutMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "任务配置" Tab — 左侧模式列表 + 右侧任务网格。
 */
public class TaskTabScreen {

    private static final int SIDEBAR_W = 180;
    private static final int ROW_H = 28;
    private static final int HEADER_H = 28;
    private static final int GROUP_HEADER_H = 20;

    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;

    private Map<String, ModeSection> sections = new LinkedHashMap<>();
    private String selectedMode = "";
    private String searchText = "";

    private EditBox searchBox;
    private double sidebarScroll = 0;
    private double contentScroll = 0;
    private boolean draggingSidebar = false;
    private boolean draggingContent = false;
    private double dragStartY = 0;
    private double dragStartScroll = 0;

    /** 每帧快照的 ConfigQueryService 代理 (S10-007)，避免 render 内反复 getInstance */
    private ConfigQueryService configSnapshot;

    private final List<RowHit> sidebarHits = new ArrayList<>();
    private final List<TaskRowHit> taskHits = new ArrayList<>();

    private record ModeSection(String gameModeId, String title, int accent, List<TaskDefinition> tasks) {}
    private record RowHit(String id, int x, int y, int w, int h) {}
    private record TaskRowHit(TaskDefinition def, int toggleX, int toggleW, int editX, int editW, int y, int h) {}

    public TaskTabScreen(ConfigRootScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        rebuildSections();
        if (!sections.isEmpty()) selectedMode = sections.keySet().iterator().next();
    }

    // ==================== 构建/分组 ====================

    /**
     * 构建侧边栏模式分组。
     * <p>停电模式({@code habitrains:blackout})按阵营拆成两个独立侧栏条目：
     * "§a停电模式 · 好人任务"和"§c停电模式 · 坏人任务"，各自只显示对应阵营池的任务。
     * 其他模式仍按 gameModeId 合成一条。
     * <p>新增好人/坏人任务时，只需在任务注册时指定 {@code .category(BlackoutMode.BLACKOUT_GOOD/BAD)}，
     * 此处会自动归入对应侧栏条目，无需改 GUI 代码。
     */
    private void rebuildSections() {
        Map<String, List<TaskDefinition>> grouped = new LinkedHashMap<>();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            String sectionKey = sectionKeyFor(def);
            grouped.computeIfAbsent(sectionKey, k -> new ArrayList<>()).add(def);
        }
        sections.clear();
        for (var entry : grouped.entrySet()) {
            String sectionKey = entry.getKey();
            List<TaskDefinition> tasks = entry.getValue();
            tasks.sort(Comparator
                    .comparingInt((TaskDefinition d) -> taskCategoryPriority(d.getCategory()))
                    .thenComparing(TaskDefinition::getDisplayName, String.CASE_INSENSITIVE_ORDER));
            String title = resolveSectionTitle(sectionKey, tasks);
            int accent = accentForSection(sectionKey, tasks);
            sections.put(sectionKey, new ModeSection(sectionKey, title, accent, tasks));
        }
    }

    /**
     * 为任务计算所属侧栏 key。停电模式按阵营拆成两个 key，其他模式用 gameModeId 原样。
     */
    private String sectionKeyFor(TaskDefinition def) {
        String modeId = def.getGameModeId();
        if (BlackoutMode.MODE_ID.equals(modeId)) {
            TaskCategory cat = def.getCategory();
            if (BlackoutMode.BLACKOUT_GOOD.equals(cat)) return BlackoutMode.MODE_ID + "__good";
            if (BlackoutMode.BLACKOUT_BAD.equals(cat)) return BlackoutMode.MODE_ID + "__bad";
        }
        return modeId;
    }

    private String resolveSectionTitle(String sectionKey, List<TaskDefinition> tasks) {
        if (sectionKey.endsWith("__good")) return "\u00a7a停电模式 \u00b7 好人任务";
        if (sectionKey.endsWith("__bad")) return "\u00a7c停电模式 \u00b7 坏人任务";
        String modeId = sectionKey;
        if ("sre:base".equals(modeId)) return "基础任务";
        GameMode mode = GameModeRegistry.get(fullModeId(modeId));
        if (mode != null && mode.getDisplayName() != null && !mode.getDisplayName().isBlank()) {
            return mode.getDisplayName();
        }
        String raw = simpleModeName(modeId);
        return raw.isEmpty() ? modeId : raw;
    }

    private int accentForSection(String sectionKey, List<TaskDefinition> tasks) {
        if (sectionKey.endsWith("__good")) return 0xFF3FBF6F;
        if (sectionKey.endsWith("__bad")) return 0xFFD84848;
        return SharedGuiKit.accentFor(sectionKey);
    }

    private String fullModeId(String modeId) {
        return "habitrain_core:" + modeId;
    }

    private String simpleModeName(String modeId) {
        int idx = modeId.lastIndexOf(':');
        String tail = idx >= 0 ? modeId.substring(idx + 1) : modeId;
        tail = tail.replace('_', ' ').replace('-', ' ').trim();
        if (tail.isEmpty()) return modeId;
        StringBuilder sb = new StringBuilder();
        for (String p : tail.split("\\s+")) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private int taskCategoryPriority(TaskCategory cat) {
        if (BlackoutMode.BLACKOUT_GOOD.equals(cat)) return 0;
        if (BlackoutMode.BLACKOUT_BAD.equals(cat)) return 1;
        if (TaskCategory.MURDER.equals(cat)) return 2;
        if (TaskCategory.REPAIR.equals(cat)) return 3;
        if (TaskCategory.ALL.equals(cat)) return 4;
        if (TaskCategory.CUSTOM.equals(cat)) return 5;
        return 6;
    }

    /**
     * 内容区组标题：停电模式已按阵营拆分到不同侧栏条目，此处仅做细分标注。
     */
    private String categoryGroupLabel(TaskCategory cat) {
        if (cat == null) return "未分类";
        String id = cat.getId();
        if (BlackoutMode.BLACKOUT_GOOD.equals(cat)) return "\u00a7a好人任务池";
        if (BlackoutMode.BLACKOUT_BAD.equals(cat)) return "\u00a7c坏人任务池";
        if (TaskCategory.MURDER.equals(cat)) return "谋杀模式";
        if (TaskCategory.REPAIR.equals(cat)) return "修机模式";
        if (TaskCategory.ALL.equals(cat)) return "通用任务";
        if (TaskCategory.CUSTOM.equals(cat)) return "自定义任务";
        return cat.getDisplayName();
    }

    // ==================== 渲染 ====================

    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        // 每帧快照 ConfigQueryService (S10-007/S10-008)，替代逐任务 getInstance() 调用
        configSnapshot = ConfigManager.getInstance();

        int sidebarX = x;
        int contentX = x + SIDEBAR_W + 4;
        int contentW = w - SIDEBAR_W - 4;

        // 搜索框
        if (searchBox == null) {
            searchBox = new EditBox(font, sidebarX + 6, y + 6, SIDEBAR_W - 12, 14, Component.literal(""));
            searchBox.setMaxLength(64);
            searchBox.setHint(Component.literal("搜索任务..."));
            searchBox.setResponder(t -> { searchText = t == null ? "" : t.trim().toLowerCase(Locale.ROOT); contentScroll = 0; });
        }
        searchBox.setX(sidebarX + 6);
        searchBox.setY(y + 6);
        searchBox.setWidth(SIDEBAR_W - 12);
        searchBox.render(g, mx, my, delta);

        // 侧边栏：模式列表
        int sidebarListY = y + HEADER_H;
        int sidebarListH = h - HEADER_H;
        sidebarHits.clear();
        g.enableScissor(sidebarX, sidebarListY, sidebarX + SIDEBAR_W, sidebarListY + sidebarListH);
        int rowY = sidebarListY - (int) sidebarScroll;
        for (var section : sections.values()) {
            boolean selected = section.gameModeId().equals(selectedMode);
            boolean hover = SharedGuiKit.inBounds(mx, my, sidebarX, rowY, SIDEBAR_W, ROW_H);
            int bg = selected ? SharedGuiKit.BG_ROW_SELECTED : (hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_PANEL);
            g.fill(sidebarX, rowY, sidebarX + SIDEBAR_W, rowY + ROW_H, bg);
            if (selected) g.fill(sidebarX, rowY, sidebarX + 3, rowY + ROW_H, section.accent());
            int enabled = countEnabled(section.tasks());
            String label = section.title() + " §7" + enabled + "/" + section.tasks().size();
            g.drawString(font, label, sidebarX + 8, rowY + (ROW_H - font.lineHeight) / 2,
                    selected ? 0xFFFFFFFF : SharedGuiKit.TEXT_PRIMARY, false);
            sidebarHits.add(new RowHit(section.gameModeId(), sidebarX, rowY, SIDEBAR_W, ROW_H));
            rowY += ROW_H;
        }
        // 滚动条
        int sidebarContentH = rowY + (int) sidebarScroll - sidebarListY;
        int maxSidebarScroll = Math.max(0, sidebarContentH - sidebarListH);
        SharedGuiKit.drawScrollbar(g, sidebarX + SIDEBAR_W - 4, sidebarListY, sidebarListH, sidebarScroll, maxSidebarScroll, 3);
        g.disableScissor();

        // 内容区：任务列表
        ModeSection section = sections.get(selectedMode);
        if (section == null) return;
        taskHits.clear();
        g.enableScissor(contentX, y, contentX + contentW, y + h);
        int cy = y - (int) contentScroll;

        // 内容标题
        g.drawString(font, Component.literal("§l" + section.title()), contentX + 6, cy + 4, 0xFFFFFFFF, false);
        cy += HEADER_H;

        // 按 category 分组
        Map<String, List<TaskDefinition>> groups = new LinkedHashMap<>();
        for (TaskDefinition def : section.tasks()) {
            if (!matchesSearch(def)) continue;
            String label = categoryGroupLabel(def.getCategory());
            groups.computeIfAbsent(label, k -> new ArrayList<>()).add(def);
        }
        if (groups.isEmpty()) {
            g.drawString(font, Component.literal("§7无匹配任务"), contentX + 6, cy + 8, 0xFF888888, false);
        }
        for (var group : groups.entrySet()) {
            // 组标题
            g.drawString(font, Component.literal("§e§l" + group.getKey()), contentX + 6, cy + 2, SharedGuiKit.ACCENT_CYAN, false);
            cy += GROUP_HEADER_H;
            for (TaskDefinition def : group.getValue()) {
                drawTaskRow(g, def, contentX, cy, contentW);
                cy += ROW_H;
            }
        }
        // 内容滚动条
        int contentContentH = cy + (int) contentScroll - y;
        int maxContentScroll = Math.max(0, contentContentH - h);
        SharedGuiKit.drawScrollbar(g, contentX + contentW - 4, y, h, contentScroll, maxContentScroll, 3);
        g.disableScissor();
    }

    private void drawTaskRow(GuiGraphics g, TaskDefinition def, int x, int y, int w) {
        TaskConfigEntry cfg = configSnapshot.getTaskConfig(def.getFullId());
        boolean enabled = cfg == null || cfg.enabled;
        int color = cfg != null ? cfg.instinctColor : SharedGuiKit.accentFor(def.getFullId());
        // 色条
        SharedGuiKit.drawAccentStripe(g, x, y, ROW_H, color);
        // 名称
        g.drawString(font, def.getDisplayName(), x + 8, y + 4, SharedGuiKit.TEXT_PRIMARY, false);
        // 元信息
        String meta = "§7" + def.getFullId();
        g.drawString(font, meta, x + 8, y + 15, SharedGuiKit.TEXT_SECONDARY, false);
        // 阵营药丸（仅停电模式任务显示）：绿色"好人"/红色"坏人"
        int factionPillW = 0;
        String factionLabel = factionLabelFor(def);
        if (factionLabel != null) {
            boolean isGood = BlackoutMode.BLACKOUT_GOOD.equals(def.getCategory());
            factionPillW = 36;
            int fpX = x + w - 168;
            g.fill(fpX, y + 4, fpX + factionPillW, y + 18, isGood ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
            g.drawString(font, factionLabel, fpX + (factionPillW - font.width(factionLabel)) / 2, y + 6,
                    0xFFFFFFFF, false);
        }
        // 状态药丸
        int pillX = x + w - 120;
        int pillW = 48;
        g.fill(pillX, y + 4, pillX + pillW, y + 18, enabled ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
        g.drawString(font, enabled ? "§a已启用" : "§c已停用", pillX + 6, y + 6, 0xFFFFFFFF, false);
        // 编辑按钮
        int editX = x + w - 64;
        int editW = 54;
        g.fill(editX, y + 4, editX + editW, y + 18, SharedGuiKit.BG_EDIT);
        g.drawString(font, "§e编辑", editX + (editW - font.width("编辑")) / 2, y + 6, 0xFFFFFFFF, false);
        taskHits.add(new TaskRowHit(def, pillX, pillW, editX, editW, y, ROW_H));
    }

    /**
     * 返回阵营药丸文本（§a好人 / §c坏人），非停电任务返回 null 不显示药丸。
     */
    private String factionLabelFor(TaskDefinition def) {
        TaskCategory cat = def.getCategory();
        if (BlackoutMode.BLACKOUT_GOOD.equals(cat)) return "\u00a7a好人";
        if (BlackoutMode.BLACKOUT_BAD.equals(cat)) return "\u00a7c坏人";
        return null;
    }

    private boolean matchesSearch(TaskDefinition def) {
        if (searchText.isEmpty()) return true;
        return (def.getDisplayName() + " " + def.getFullId()).toLowerCase(Locale.ROOT).contains(searchText);
    }

    private int countEnabled(List<TaskDefinition> tasks) {
        int n = 0;
        for (TaskDefinition d : tasks) {
            TaskConfigEntry cfg = configSnapshot.getTaskConfig(d.getFullId());
            if (cfg == null || cfg.enabled) n++;
        }
        return n;
    }

    // ==================== 交互 ====================

    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (searchBox != null && searchBox.mouseClicked(mx, my, btn)) return true;

        // 侧边栏点击
        for (RowHit hit : sidebarHits) {
            if (SharedGuiKit.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                selectedMode = hit.id();
                contentScroll = 0;
                return true;
            }
        }
        // 任务行点击
        for (TaskRowHit hit : taskHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                toggleTask(hit.def());
                return true;
            }
            if (mx >= hit.editX() && mx < hit.editX() + hit.editW()) {
                openTaskEditor(hit.def());
                return true;
            }
        }
        // 侧边栏滚动
        int sidebarListY = y + HEADER_H;
        if (mx < x + SIDEBAR_W && my >= sidebarListY) {
            draggingSidebar = true;
            dragStartY = my;
            dragStartScroll = sidebarScroll;
            return true;
        }
        // 内容滚动
        if (mx > x + SIDEBAR_W + 4) {
            draggingContent = true;
            dragStartY = my;
            dragStartScroll = contentScroll;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (draggingSidebar) {
            sidebarScroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, 10000);
            return true;
        }
        if (draggingContent) {
            contentScroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, 10000);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        if (mx < x + SIDEBAR_W) {
            sidebarScroll = Mth.clamp(sidebarScroll - sy * 18, 0, 10000);
        } else {
            contentScroll = Mth.clamp(contentScroll - sy * 18, 0, 10000);
        }
        return true;
    }

    public boolean keyPressed(int key, int scan, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.keyPressed(key, scan, mod)) return true;
        return false;
    }

    public boolean charTyped(char ch, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(ch, mod)) return true;
        return false;
    }

    private void toggleTask(TaskDefinition def) {
        if (!editable) { LiveConfigAccess.showDeniedMessage(); return; }
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg == null) cfg = TaskConfigEntry.createDefault();
        cfg.enabled = !cfg.enabled;
        ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
    }

    private void openTaskEditor(TaskDefinition def) {
        if (!editable) { LiveConfigAccess.showDeniedMessage(); return; }
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg == null) cfg = TaskConfigEntry.createDefault();
        ConfigManager.getInstance().putTaskConfig(def.getFullId(), cfg);
        // 用 sectionKeyFor 查找（停电模式已拆成 __good/__bad 两条）
        ModeSection section = sections.get(sectionKeyFor(def));
        String modeName = section != null ? section.title() : def.getGameModeId();
        int accent = section != null ? section.accent() : SharedGuiKit.accentFor(def.getGameModeId());
        Minecraft.getInstance().setScreen(new TaskEditScreen(root, def, cfg, def.getCategory(), modeName, accent));
    }
}