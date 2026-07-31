package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.api.role.*;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.RoleOverrideConfigSection;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * 配置中心「游戏模式 · 角色覆盖」页 — 管理角色替换/调整的启用/停用（移植自旧 RoleOverrideTabScreen）。
 * 显示所有已注册的 REPLACE 和 MODIFY 条目，支持全局开关和逐条目切换；切换后立即写入配置。
 */
public class ModeRolesPage implements ConfigPage {
    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private List<RowModel> rows = new ArrayList<>();
    private double scrollOffset = 0;
    private static final int ROW_H = 62;
    private static final int ROW_GAP = 2;

    public ModeRolesPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        rebuildRows();
    }

    @Override
    public boolean canSave() { return true; }
    @Override
    public void save() {}
    @Override
    public void flushPending() {}

    /** 配置同步后由 ConfigMenuScreen.refreshRoleOverrideTab() 调用。 */
    public void rebuildRows() {
        rows.clear();
        RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
        Set<String> seenIds = new HashSet<>();
        Map<String, RoleOverrideEntry> engineEntries = new HashMap<>();
        for (RoleOverrideEntry entry : RoleOverrideApi.getEffectiveEntries()) {
            engineEntries.put(entry.entryId(), entry);
        }

        // Collect all entries from registry
        for (ReplaceRoleDefinition def : RoleOverrideRegistry.INSTANCE.getReplaces()) {
            String id = entryId(def);
            if (!seenIds.add(id)) continue;
            boolean enabled = cfg.isEnabled(id);
            RoleOverrideEntry engineEntry = engineEntries.get(id);
            OverrideStatus status = engineEntry != null
                    ? engineEntry.status()
                    : resolveReplaceStatus(def, cfg);
            rows.add(new RowModel(id, def.sourceModId(), def.displayName(),
                    def.description().orElse(Component.empty()), def.targetRoleId(),
                    def.replacementRole().identifier(), RoleOverrideKind.REPLACE,
                    enabled, status, def.customTypeLabel().orElse("替换"),
                    engineEntry == null ? "" : engineEntry.statusMessage().orElse("")));
        }
        for (ModifyRoleDefinition def : RoleOverrideRegistry.INSTANCE.getModifies()) {
            String id = entryId(def);
            if (!seenIds.add(id)) continue;
            boolean enabled = cfg.isEnabled(id);
            RoleOverrideEntry engineEntry = engineEntries.get(id);
            OverrideStatus status = engineEntry != null
                    ? engineEntry.status()
                    : resolveModifyStatus(def, cfg);
            rows.add(new RowModel(id, def.sourceModId(), def.displayName(),
                    def.description().orElse(Component.empty()), def.targetRoleId(),
                    def.targetRoleId(), RoleOverrideKind.MODIFY,
                    enabled, status, def.customTypeLabel().orElse("调整"),
                    engineEntry == null ? "" : engineEntry.statusMessage().orElse("")));
        }
    }

    private OverrideStatus resolveReplaceStatus(ReplaceRoleDefinition def, RoleOverrideConfigSection cfg) {
        if (!cfg.isGlobalEnabled()) return OverrideStatus.DISABLED;
        if (TMMRoles.getRole(def.targetRoleId()) == null) return OverrideStatus.INVALID;
        // Check for conflicts: multiple replaces or a modify on same target
        long replaceCount = RoleOverrideRegistry.INSTANCE.getReplaces().stream()
                .filter(r -> r.targetRoleId().equals(def.targetRoleId()))
                .filter(r -> cfg.isEnabled(entryId(r)))
                .count();
        long modifyCount = RoleOverrideRegistry.INSTANCE.getModifies().stream()
                .filter(m -> m.targetRoleId().equals(def.targetRoleId()))
                .filter(m -> cfg.isEnabled(entryId(m)))
                .count();
        if (replaceCount > 1 || modifyCount > 0) return OverrideStatus.CONFLICT;
        return cfg.isEnabled(entryId(def)) ? OverrideStatus.ACTIVE : OverrideStatus.DISABLED;
    }

    private OverrideStatus resolveModifyStatus(ModifyRoleDefinition def, RoleOverrideConfigSection cfg) {
        if (!cfg.isGlobalEnabled()) return OverrideStatus.DISABLED;
        if (TMMRoles.getRole(def.targetRoleId()) == null) return OverrideStatus.INVALID;
        long replaceCount = RoleOverrideRegistry.INSTANCE.getReplaces().stream()
                .filter(r -> r.targetRoleId().equals(def.targetRoleId()))
                .filter(r -> cfg.isEnabled(entryId(r)))
                .count();
        long modifyCount = RoleOverrideRegistry.INSTANCE.getModifies().stream()
                .filter(m -> m.targetRoleId().equals(def.targetRoleId()))
                .filter(m -> cfg.isEnabled(entryId(m)))
                .count();
        if (modifyCount > 1 || replaceCount > 0) return OverrideStatus.CONFLICT;
        return cfg.isEnabled(entryId(def)) ? OverrideStatus.ACTIVE : OverrideStatus.DISABLED;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta,
                       int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF1E1E1E);

        // Global toggle header
        RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
        boolean globalEnabled = cfg.isGlobalEnabled();
        int headerY = y + 4;
        g.drawString(font, Component.literal("§l角色覆盖总开关"), x + 6, headerY + 2, 0xFFFFFF);
        PillToggle.render(g, font, x + w - 72, headerY, 64, 14, globalEnabled, "已启用", "已停用");

        // Conflict banner
        long conflictCount = rows.stream().filter(r -> r.status == OverrideStatus.CONFLICT).count();
        if (conflictCount > 0) {
            g.fill(x + 4, headerY + 14, x + w - 4, headerY + 26, 0x40FFAA00);
            g.drawString(font, Component.literal("§e⚠ " + conflictCount + " 组冲突待解决"),
                    x + 8, headerY + 16, 0xFFFFFF);
        }

        // Separator
        int listTop = headerY + 30;
        g.fill(x + 4, listTop, x + w - 4, listTop + 1, 0x30FFFFFF);

        // Scrollable list
        int listY = listTop + 4;
        int listH = h - (listY - y) - 4;
        int totalH = rows.size() * (ROW_H + ROW_GAP);
        int maxScroll = Math.max(0, totalH - listH);

        // Clamp scroll
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        g.enableScissor(x, listY, x + w, listY + listH);
        int rowY = listY - (int) scrollOffset;
        for (RowModel row : rows) {
            if (rowY + ROW_H > listY && rowY < listY + listH) {
                renderRow(g, x + 4, rowY, w - 8, ROW_H, row, mx, my, globalEnabled);
            }
            rowY += ROW_H + ROW_GAP;
        }
        g.disableScissor();

        // Scrollbar
        if (maxScroll > 0) {
            MenuTheme.drawScrollbar(g, x + w - 6, listY, listH, scrollOffset, maxScroll, 4);
        }

        // Read-only hint
        if (!editable) {
            g.drawString(font, Component.literal("§c仅 OP 可修改服务端配置"),
                    x + 4, y + h - 12, 0xFF5555, false);
        }
    }

    private void renderRow(GuiGraphics g, int x, int y, int w, int h, RowModel row,
                           int mx, int my, boolean globalEnabled) {
        boolean hover = MenuTheme.inBounds(mx, my, x, y, w, h);
        int bg = hover ? 0xFF2A3440 : 0xFF1B222B;
        if (row.status == OverrideStatus.CONFLICT) bg = hover ? 0xFF3A2A20 : 0xFF2A2018;
        if (row.status == OverrideStatus.INVALID) bg = hover ? 0xFF3A2020 : 0xFF2A1818;
        g.fill(x, y, x + w, y + h, bg);

        // Status indicator
        String statusText;
        int statusColor;
        switch (row.status) {
            case ACTIVE -> { statusText = "启用"; statusColor = 0xFF55C28A; }
            case DISABLED -> { statusText = "停用"; statusColor = 0xFFC25555; }
            case CONFLICT -> { statusText = "冲突"; statusColor = 0xFFFFAA00; }
            case INVALID -> { statusText = "无效"; statusColor = 0xFF666666; }
            default -> { statusText = "待定"; statusColor = 0xFF666666; }
        }
        statusText = (row.enabled ? "条目开 · " : "条目关 · ") + statusText;
        int statusX = x + w - font.width(statusText) - 6;
        g.drawString(font, statusText, statusX, y + 4, statusColor, false);

        // The actual behavior is always core-owned; providers may add a more specific label.
        String kindLabel = row.kind == RoleOverrideKind.REPLACE ? "[替换]" : "[调整]";
        int kindColor = row.kind == RoleOverrideKind.REPLACE ? 0xFF57C6D6 : 0xFFD4A55A;
        g.drawString(font, kindLabel, x + 4, y + 4, kindColor, false);
        int nameX = x + 4 + font.width(kindLabel) + 5;
        int nameWidth = Math.max(20, statusX - nameX - 6);
        g.drawString(font, fit(row.displayName.getString(), nameWidth),
                nameX, y + 4, 0xFFFFFFFF, false);

        String providerLine = "来源: " + row.sourceModId
                + "  ·  接入标注: " + row.customTypeLabel;
        g.drawString(font, fit(providerLine, w - 12), x + 6, y + 16, 0xFF9AA4B2, false);

        String roleLine = row.kind == RoleOverrideKind.REPLACE
                ? "角色 ID: " + row.targetId + "  →  " + row.shownId
                : "角色 ID: " + row.targetId;
        g.drawString(font, fit(roleLine, w - 12), x + 6, y + 28, 0xFF7F8A99, false);

        if (!row.description.getString().isBlank()) {
            g.drawString(font, fit("说明: " + row.description.getString(), w - 12),
                    x + 6, y + 39, 0xFF687382, false);
        }
        if (!row.statusMessage.isBlank()) {
            g.drawString(font, fit("状态详情: " + row.statusMessage, w - 12),
                    x + 6, y + 50, statusColor, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (!editable) return false;

        int headerY = y + 4;
        int listTop = headerY + 30;
        int listY = listTop + 4;
        int listH = h - (listY - y) - 4;

        // Global toggle click
        if (PillToggle.hit(mx, my, x + w - 72, headerY, 64, 14)) {
            RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
            cfg.setGlobalEnabled(!cfg.isGlobalEnabled());
            ConfigManager.getInstance().setRoleOverrides(cfg);
            rebuildRows();
            root.saveConfigNow();
            return true;
        }

        // Row click
        int rowIndex = (int) ((my - listY + scrollOffset) / (ROW_H + ROW_GAP));
        if (rowIndex >= 0 && rowIndex < rows.size()) {
            RowModel row = rows.get(rowIndex);
            int rowVisualY = listY + rowIndex * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (MenuTheme.inBounds(mx, my, x + 4, rowVisualY, w - 8, ROW_H)) {
                toggleRow(row);
                return true;
            }
        }

        return false;
    }

    private void toggleRow(RowModel row) {
        RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
        boolean newEnabled = !cfg.isEnabled(row.entryId);

        // If enabling, disable all other rows with same target (conflict resolution)
        if (newEnabled) {
            for (RowModel other : rows) {
                if (other != row && other.targetId.equals(row.targetId)) {
                    cfg.setEnabled(other.entryId, false);
                }
            }
        }

        cfg.setEnabled(row.entryId, newEnabled);
        ConfigManager.getInstance().setRoleOverrides(cfg);
        rebuildRows();
        root.saveConfigNow();
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy,
                                int x, int y, int w, int h) { return false; }
    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy,
                                  int x, int y, int w, int h) {
        int listTop = y + 4 + 30;
        int listY = listTop + 4;
        int listH = h - (listY - y) - 4;
        int totalH = rows.size() * (ROW_H + ROW_GAP);
        int maxScroll = Math.max(0, totalH - listH);

        scrollOffset -= sy * 12;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) { return false; }
    @Override
    public boolean charTyped(char ch, int mod) { return false; }

    private static String entryId(ReplaceRoleDefinition def) {
        return RoleOverrideApi.getEntryId(def);
    }

    private static String entryId(ModifyRoleDefinition def) {
        return RoleOverrideApi.getEntryId(def);
    }

    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int contentWidth = Math.max(0, maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, contentWidth) + ellipsis;
    }

    private record RowModel(String entryId, String sourceModId, Component displayName,
                            Component description,
                            ResourceLocation targetId, ResourceLocation shownId,
                            RoleOverrideKind kind, boolean enabled,
                            OverrideStatus status, String customTypeLabel,
                            String statusMessage) {}
}
