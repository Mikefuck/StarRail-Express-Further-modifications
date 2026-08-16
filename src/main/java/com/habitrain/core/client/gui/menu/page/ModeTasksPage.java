package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.TaskEditScreen;
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
import java.util.Set;

/**
 * "任务配置" 子页 — 左侧模式列表 + 右侧任务网格。
 * <p>由 ConfigMenuScreen 以 {@code new ModeTasksPage(this, font, remoteEditable)} 构造。
 */
public class ModeTasksPage implements com.habitrain.core.client.gui.menu.ConfigPage {

    private static final int SIDEBAR_W = 180;
    private static final int ROW_H = 28;
    private static final int HEADER_H = 28;
    private static final int GROUP_HEADER_H = 20;
    private static final String GROUP_ORIGINAL = "original";
    private static final String GROUP_BLACKOUT = "blackout";
    private static final String GROUP_MORE = "more";
    private static final String GROUP_OTHER = "other";
    private static final Set<String> ORIGINAL_SRE_TASK_IDS = Set.of(
            "sleep", "eat", "drink", "exercise", "raed_book", "bathe", "toilet",
            "chair", "note_block", "meditate", "outside", "breathe", "be_alone",
            "repair_wire", "repair_panel", "vending_machine"
    );

    private final ConfigMenuScreen root;
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
    private double maxSidebarScroll = 0;
    private double maxContentScroll = 0;

    /** 每帧快照的 ConfigQueryService 代理 (S10-007)，避免 render 内反复 getInstance */
    private ConfigQueryService configSnapshot;

    private final List<RowHit> sidebarHits = new ArrayList<>();
    private final List<TaskRowHit> taskHits = new ArrayList<>();

    private record ModeSection(String gameModeId, String title, int accent, List<TaskDefinition> tasks) {}
    private record RowHit(String id, int x, int y, int w, int h) {}
    private record TaskRowHit(TaskDefinition def, int toggleX, int toggleW, int editX, int editW, int y, int h) {}

    public ModeTasksPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        rebuildSections();
        if (!sections.isEmpty()) selectedMode = sections.keySet().iterator().next();
    }

    // ==================== 构建/分组 ====================

    /** 按玩家理解成本划分固定四组，避免所有 {@code sre:base} 任务挤在“基础任务”中。 */
    private void rebuildSections() {
        Map<String, List<TaskDefinition>> grouped = new LinkedHashMap<>();
        grouped.put(GROUP_ORIGINAL, new ArrayList<>());
        grouped.put(GROUP_BLACKOUT, new ArrayList<>());
        grouped.put(GROUP_MORE, new ArrayList<>());
        grouped.put(GROUP_OTHER, new ArrayList<>());
        for (TaskDefinition def : TaskRegistry.getAll()) {
            grouped.get(groupKeyFor(def)).add(def);
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

    private String groupKeyFor(TaskDefinition def) {
        TaskCategory category = def.getCategory();
        if (BlackoutMode.MODE_ID.equals(def.getGameModeId())
                || BlackoutMode.BLACKOUT_GOOD.equals(category)
                || BlackoutMode.BLACKOUT_BAD.equals(category)) {
            return GROUP_BLACKOUT;
        }
        if (HabiTrainCore.MOD_ID.equals(def.getModId())
                && "sre:base".equals(def.getGameModeId())
                && ORIGINAL_SRE_TASK_IDS.contains(def.getTaskId())) {
            return GROUP_ORIGINAL;
        }
        if (!HabiTrainCore.MOD_ID.equals(def.getModId()) || "sre:base".equals(def.getGameModeId())) {
            return GROUP_MORE;
        }
        return GROUP_OTHER;
    }

    private String resolveSectionTitle(String sectionKey, List<TaskDefinition> tasks) {
        return switch (sectionKey) {
            case GROUP_ORIGINAL -> "原版哈比任务";
            case GROUP_BLACKOUT -> "停电专属任务";
            case GROUP_MORE -> "更多任务";
            default -> "其他";
        };
    }

    private int accentForSection(String sectionKey, List<TaskDefinition> tasks) {
        return switch (sectionKey) {
            case GROUP_ORIGINAL -> MenuTheme.ACCENT_MINT;
            case GROUP_BLACKOUT -> MenuTheme.DANGER;
            case GROUP_MORE -> MenuTheme.ACCENT_BLUE;
            default -> MenuTheme.TEXT_SECONDARY;
        };
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
        if (BlackoutMode.BLACKOUT_GOOD.equals(cat)) return "§a好人任务池";
        if (BlackoutMode.BLACKOUT_BAD.equals(cat)) return "§c坏人任务池";
        if (TaskCategory.MURDER.equals(cat)) return "谋杀模式";
        if (TaskCategory.REPAIR.equals(cat)) return "修机模式";
        if (TaskCategory.ALL.equals(cat)) return "通用任务";
        if (TaskCategory.CUSTOM.equals(cat)) return "自定义任务";
        return cat.getDisplayName();
    }

    // ==================== 渲染 ====================

    @Override
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
            boolean hover = MenuTheme.inBounds(mx, my, sidebarX, rowY, SIDEBAR_W, ROW_H);
            int bg = selected ? MenuTheme.BG_ROW_SELECTED : (hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_PANEL);
            g.fill(sidebarX, rowY, sidebarX + SIDEBAR_W, rowY + ROW_H, bg);
            if (selected) g.fill(sidebarX, rowY, sidebarX + 3, rowY + ROW_H, section.accent());
            int enabled = countEnabled(section.tasks());
            String label = section.title() + " §7" + enabled + "/" + section.tasks().size();
            g.drawString(font, label, sidebarX + 8, rowY + (ROW_H - font.lineHeight) / 2,
                    selected ? 0xFFFFFFFF : MenuTheme.TEXT_PRIMARY, false);
            sidebarHits.add(new RowHit(section.gameModeId(), sidebarX, rowY, SIDEBAR_W, ROW_H));
            rowY += ROW_H;
        }
        // 滚动条
        int sidebarContentH = rowY + (int) sidebarScroll - sidebarListY;
        maxSidebarScroll = Math.max(0, sidebarContentH - sidebarListH);
        sidebarScroll = Mth.clamp(sidebarScroll, 0, maxSidebarScroll);
        MenuTheme.drawScrollbar(g, sidebarX + SIDEBAR_W - 4, sidebarListY, sidebarListH, sidebarScroll, maxSidebarScroll, 3);
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
            g.drawString(font, Component.literal("§e§l" + group.getKey()), contentX + 6, cy + 2, MenuTheme.ACCENT_CYAN, false);
            cy += GROUP_HEADER_H;
            for (TaskDefinition def : group.getValue()) {
                drawTaskRow(g, def, contentX, cy, contentW, mx, my);
                cy += ROW_H;
            }
        }
        // 内容滚动条
        int contentContentH = cy + (int) contentScroll - y;
        maxContentScroll = Math.max(0, contentContentH - h);
        contentScroll = Mth.clamp(contentScroll, 0, maxContentScroll);
        MenuTheme.drawScrollbar(g, contentX + contentW - 4, y, h, contentScroll, maxContentScroll, 3);
        g.disableScissor();
    }

    private void drawTaskRow(GuiGraphics g, TaskDefinition def, int x, int y, int w, int mx, int my) {
        TaskConfigEntry cfg = configSnapshot.getTaskConfig(def.getFullId());
        boolean enabled = cfg == null || cfg.enabled;
        int color = cfg != null ? cfg.instinctColor : MenuTheme.accentFor(def.getFullId());
        MenuTheme.row(g, x, y, w - 5, ROW_H - 1,
                MenuTheme.inBounds(mx, my, x, y, w - 5, ROW_H), false);
        // 色条
        MenuTheme.drawAccentStripe(g, x, y, ROW_H, color);
        // 名称
        g.drawString(font, def.getDisplayName(), x + 8, y + 4, MenuTheme.TEXT_PRIMARY, false);
        // 元信息
        String meta = "§7" + def.getFullId();
        g.drawString(font, meta, x + 8, y + 15, MenuTheme.TEXT_SECONDARY, false);
        // 阵营药丸（仅停电模式任务显示）：绿色"好人"/红色"坏人"
        int factionPillW = 0;
        String factionLabel = factionLabelFor(def);
        if (factionLabel != null) {
            boolean isGood = BlackoutMode.BLACKOUT_GOOD.equals(def.getCategory());
            factionPillW = 36;
            int fpX = x + w - 168;
            g.fill(fpX, y + 4, fpX + factionPillW, y + 18, isGood ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
            g.drawString(font, factionLabel, fpX + (factionPillW - font.width(factionLabel)) / 2, y + 6,
                    0xFFFFFFFF, false);
        }
        // 状态药丸
        int pillX = x + w - 120;
        int pillW = 48;
        g.fill(pillX, y + 4, pillX + pillW, y + 18, enabled ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        g.drawString(font, enabled ? "§a已启用" : "§c已停用", pillX + 6, y + 6, 0xFFFFFFFF, false);
        // 编辑按钮
        int editX = x + w - 64;
        int editW = 54;
        boolean editHover = MenuTheme.inBounds(mx, my, editX, y + 4, editW, 14);
        MenuTheme.button(g, font, "编辑", editX, y + 4, editW, 14,
                MenuTheme.ACCENT_AMBER, true, editHover);
        taskHits.add(new TaskRowHit(def, pillX, pillW, editX, editW, y, ROW_H));
    }

    /**
     * 返回阵营药丸文本（§a好人 / §c坏人），非停电任务返回 null 不显示药丸。
     */
    private String factionLabelFor(TaskDefinition def) {
        TaskCategory cat = def.getCategory();
        if (BlackoutMode.BLACKOUT_GOOD.equals(cat)) return "§a好人";
        if (BlackoutMode.BLACKOUT_BAD.equals(cat)) return "§c坏人";
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

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (searchBox != null && searchBox.mouseClicked(mx, my, btn)) return true;

        // 侧边栏点击
        for (RowHit hit : sidebarHits) {
            if (MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                selectedMode = hit.id();
                contentScroll = 0;
                MenuSounds.playClick();
                return true;
            }
        }
        // 任务行点击
        for (TaskRowHit hit : taskHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                MenuSounds.playClick();
                toggleTask(hit.def());
                return true;
            }
            if (mx >= hit.editX() && mx < hit.editX() + hit.editW()) {
                MenuSounds.playClick();
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

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (draggingSidebar) {
            sidebarScroll = Mth.clamp(dragStartScroll + (my - dragStartY), 0, maxSidebarScroll);
            return true;
        }
        if (draggingContent) {
            contentScroll = Mth.clamp(dragStartScroll + (my - dragStartY), 0, maxContentScroll);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (draggingSidebar || draggingContent) {
            draggingSidebar = false;
            draggingContent = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        if (mx < x + SIDEBAR_W) {
            sidebarScroll = Mth.clamp(sidebarScroll - sy * 18, 0, maxSidebarScroll);
        } else {
            contentScroll = Mth.clamp(contentScroll - sy * 18, 0, maxContentScroll);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.keyPressed(key, scan, mod)) return true;
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(ch, mod)) return true;
        return false;
    }

    @Override
    public boolean canSave() { return true; }

    @Override
    public void save() { /* 任务配置即时生效，保存按钮写盘由 ConfigMenuScreen 统一处理 */ }

    @Override
    public void flushPending() { /* 任务页无可延迟提交的文本框（搜索框不写配置） */ }

    private void toggleTask(TaskDefinition def) {
        if (!editable) { MenuPermissions.showDeniedMessage(); return; }
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg == null) cfg = TaskConfigEntry.createDefault();
        cfg.enabled = !cfg.enabled;
        ConfigManager.getInstance().putTaskConfig(def.getFullId(), cfg);
    }

    private void openTaskEditor(TaskDefinition def) {
        if (!editable) { MenuPermissions.showDeniedMessage(); return; }
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg == null) cfg = TaskConfigEntry.createDefault();
        ConfigManager.getInstance().putTaskConfig(def.getFullId(), cfg);
        ModeSection section = sections.get(groupKeyFor(def));
        String modeName = section != null ? section.title() : def.getGameModeId();
        int accent = section != null ? section.accent() : MenuTheme.accentFor(def.getGameModeId());
        Minecraft.getInstance().setScreen(new TaskEditScreen(root, def, cfg, def.getCategory(), modeName, accent));
    }
}
