package com.habitrain.core.client.gui.config;

import com.habitrain.core.api.role.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.RoleOverrideConfigSection;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * ModMenu "角色覆盖" Tab — 管理角色替换/调整的启用/停用。
 * 显示所有已注册的 REPLACE 和 MODIFY 条目，支持全局开关和逐条目切换。
 */
public class RoleOverrideTabScreen {
    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;

    private List<RowModel> rows = new ArrayList<>();
    private double scrollOffset = 0;
    private static final int ROW_H = 28;
    private static final int ROW_GAP = 2;

    public RoleOverrideTabScreen(ConfigRootScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        rebuildRows();
    }

    public void rebuildRows() {
        rows.clear();
        RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
        Set<String> seenIds = new HashSet<>();

        // Collect all entries from registry
        for (ReplaceRoleDefinition def : RoleOverrideRegistry.INSTANCE.getReplaces()) {
            String id = entryId(def);
            if (!seenIds.add(id)) continue;
            boolean enabled = cfg.isEnabled(id);
            OverrideStatus status = resolveReplaceStatus(def, cfg);
            rows.add(new RowModel(id, def.displayName(), def.targetRoleId(),
                    def.replacementRole().identifier(), RoleOverrideKind.REPLACE,
                    enabled, status, def.customTypeLabel().orElse("替换")));
        }
        for (ModifyRoleDefinition def : RoleOverrideRegistry.INSTANCE.getModifies()) {
            String id = entryId(def);
            if (!seenIds.add(id)) continue;
            boolean enabled = cfg.isEnabled(id);
            OverrideStatus status = resolveModifyStatus(def, cfg);
            rows.add(new RowModel(id, def.displayName(), def.targetRoleId(),
                    def.targetRoleId(), RoleOverrideKind.MODIFY,
                    enabled, status, def.customTypeLabel().orElse("调整")));
        }
    }

    private OverrideStatus resolveReplaceStatus(ReplaceRoleDefinition def, RoleOverrideConfigSection cfg) {
        if (!cfg.isGlobalEnabled()) return OverrideStatus.DISABLED;
        if (TMMRoles.getRole(def.targetRoleId()) == null) return OverrideStatus.INVALID;
        // Check for conflicts: multiple replaces or a modify on same target
        long replaceCount = RoleOverrideRegistry.INSTANCE.getReplaces().stream()
                .filter(r -> r.targetRoleId().equals(def.targetRoleId())).count();
        long modifyCount = RoleOverrideRegistry.INSTANCE.getModifies().stream()
                .filter(m -> m.targetRoleId().equals(def.targetRoleId())).count();
        if (replaceCount > 1 || modifyCount > 0) return OverrideStatus.CONFLICT;
        return cfg.isEnabled(entryId(def)) ? OverrideStatus.ACTIVE : OverrideStatus.DISABLED;
    }

    private OverrideStatus resolveModifyStatus(ModifyRoleDefinition def, RoleOverrideConfigSection cfg) {
        if (!cfg.isGlobalEnabled()) return OverrideStatus.DISABLED;
        if (TMMRoles.getRole(def.targetRoleId()) == null) return OverrideStatus.INVALID;
        long replaceCount = RoleOverrideRegistry.INSTANCE.getReplaces().stream()
                .filter(r -> r.targetRoleId().equals(def.targetRoleId())).count();
        long modifyCount = RoleOverrideRegistry.INSTANCE.getModifies().stream()
                .filter(m -> m.targetRoleId().equals(def.targetRoleId())).count();
        if (modifyCount > 1 || replaceCount > 0) return OverrideStatus.CONFLICT;
        return cfg.isEnabled(entryId(def)) ? OverrideStatus.ACTIVE : OverrideStatus.DISABLED;
    }

    public void render(GuiGraphics g, int mx, int my, float delta,
                       int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF1E1E1E);

        // Global toggle header
        RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
        boolean globalEnabled = cfg.isGlobalEnabled();
        int headerY = y + 4;
        g.drawString(font, Component.literal("§l角色覆盖总开关"), x + 6, headerY + 2, 0xFFFFFF);
        String globalStatus = globalEnabled ? "§a已启用" : "§c已停用";
        g.drawString(font, Component.literal(globalStatus), x + w - 50, headerY + 2, 0xFFFFFF);

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
            SharedGuiKit.drawScrollbar(g, x + w - 6, listY, listH, scrollOffset, maxScroll, 4);
        }

        // Read-only hint
        if (!editable) {
            g.drawString(font, Component.literal("§c仅 OP 可修改服务端配置"),
                    x + 4, y + h - 12, 0xFF5555, false);
        }
    }

    private void renderRow(GuiGraphics g, int x, int y, int w, int h, RowModel row,
                           int mx, int my, boolean globalEnabled) {
        boolean hover = SharedGuiKit.inBounds(mx, my, x, y, w, h);
        int bg = hover ? 0xFF2A3440 : 0xFF1B222B;
        if (row.status == OverrideStatus.CONFLICT) bg = hover ? 0xFF3A2A20 : 0xFF2A2018;
        if (row.status == OverrideStatus.INVALID) bg = hover ? 0xFF3A2020 : 0xFF2A1818;
        g.fill(x, y, x + w, y + h, bg);

        // Kind badge
        String kindLabel = row.kind == RoleOverrideKind.REPLACE ? "§b[替换]" : "§e[调整]";
        g.drawString(font, Component.literal(kindLabel), x + 4, y + 6, 0xFFFFFF);

        // Display name
        g.drawString(font, row.displayName, x + 40, y + 6, 0xFFFFFF);

        // Target role id
        String targetStr = "→ " + row.targetId.toString();
        g.drawString(font, Component.literal("§7" + targetStr), x + 40, y + 16, 0x8A92A0);

        // Custom type label
        if (row.customTypeLabel != null && !row.customTypeLabel.isEmpty()) {
            g.drawString(font, Component.literal("§8[" + row.customTypeLabel + "]"),
                    x + 40 + font.width(targetStr) + 8, y + 16, 0x8A92A0);
        }

        // Status indicator
        String statusText;
        int statusColor;
        switch (row.status) {
            case ACTIVE -> { statusText = "§a启用"; statusColor = 0xFF55C28A; }
            case DISABLED -> { statusText = "§c停用"; statusColor = 0xFFC25555; }
            case CONFLICT -> { statusText = "§e冲突"; statusColor = 0xFFFFAA00; }
            case INVALID -> { statusText = "§7无效"; statusColor = 0xFF666666; }
            default -> { statusText = "§7待定"; statusColor = 0xFF666666; }
        }
        g.drawString(font, Component.literal(statusText), x + w - 40, y + 6, statusColor);
    }

    public boolean mouseClicked(double mx, double my, int button, int x, int y, int w, int h) {
        if (!editable) return false;

        int headerY = y + 4;
        int listTop = headerY + 30;
        int listY = listTop + 4;
        int listH = h - (listY - y) - 4;

        // Global toggle click
        if (SharedGuiKit.inBounds(mx, my, x + w - 50, headerY, 46, 12)) {
            RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
            cfg.setGlobalEnabled(!cfg.isGlobalEnabled());
            ConfigManager.getInstance().setRoleOverrides(cfg);
            rebuildRows();
            return true;
        }

        // Row click
        int rowIndex = (int) ((my - listY + scrollOffset) / (ROW_H + ROW_GAP));
        if (rowIndex >= 0 && rowIndex < rows.size()) {
            RowModel row = rows.get(rowIndex);
            int rowVisualY = listY + rowIndex * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (SharedGuiKit.inBounds(mx, my, x + 4, rowVisualY, w - 8, ROW_H)) {
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
    }

    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY,
                                  int x, int y, int w, int h) {
        int listTop = y + 4 + 30;
        int listY = listTop + 4;
        int listH = h - (listY - y) - 4;
        int totalH = rows.size() * (ROW_H + ROW_GAP);
        int maxScroll = Math.max(0, totalH - listH);

        scrollOffset -= scrollY * 12;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    private static String entryId(ReplaceRoleDefinition def) {
        ResourceLocation replId = def.replacementId().orElse(def.replacementRole().identifier());
        return def.sourceModId() + "$" + replId.getPath() + "@" + def.targetRoleId();
    }

    private static String entryId(ModifyRoleDefinition def) {
        return def.sourceModId() + "$" + def.targetRoleId().getPath() + "@" + def.targetRoleId();
    }

    private record RowModel(String entryId, Component displayName,
                            ResourceLocation targetId, ResourceLocation shownId,
                            RoleOverrideKind kind, boolean enabled,
                            OverrideStatus status, String customTypeLabel) {}
}
