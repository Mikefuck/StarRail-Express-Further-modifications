package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.client.role.RoleHandshakeState;
import com.habitrain.core.client.role.RoleSnapshotState;
import com.habitrain.core.network.RoleConfigUpdatePayload;
import com.habitrain.core.network.RoleSnapshotPayload;
import com.habitrain.core.role.config.RoleExtensionConfigSection;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置中心「游戏模式 · 角色扩展」页（fix-doc §13.2 / §14.2）。
 *
 * <p>渲染服务端同步的角色扩展 v2 条目（provider/entry 启用态 + 编译状态）与握手
 * 状态。普通客户端只读；OP 玩家可切换 provider/entry/全局开关并立即经
 * {@link RoleConfigUpdatePayload} 提交，由服务端权威校验、持久化并产生 pending
 * snapshot（下一局生效）。
 */
public class RoleExtensionsPage implements ConfigPage {

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private RoleExtensionConfigSection section = RoleExtensionConfigSection.createDefault();
    private List<ProviderRow> providers = new ArrayList<>();
    private List<EntryRow> entries = new ArrayList<>();
    private String handshakeStatus = "…";
    private String handshakeMessage = "";
    private int payloadGeneration = -1;

    private double scrollOffset = 0;
    private static final int ROW_H = 22;
    private static final int ROW_GAP = 2;

    public RoleExtensionsPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        refresh();
    }

    @Override
    public boolean canSave() { return false; }
    @Override
    public void save() {}
    @Override
    public void flushPending() {}

    /** 从服务端同步的 payload（manifest + 编译条目）重建行。 */
    public void refresh() {
        RoleSnapshotPayload payload = RoleSnapshotState.INSTANCE.get();
        if (payload == null) {
            return;
        }
        int gen = System.identityHashCode(payload);
        if (gen == payloadGeneration) {
            return;
        }
        payloadGeneration = gen;
        try {
            section = RoleExtensionConfigService.parseSection(payload.configJson());
        } catch (RuntimeException e) {
            section = RoleExtensionConfigSection.createDefault();
        }

        providers.clear();
        var manifest = RoleHandshakeState.INSTANCE.serverManifest();
        if (manifest != null) {
            for (var p : manifest.providers()) {
                providers.add(new ProviderRow(p.providerId(), p.version(),
                        p.requiredClient(), RoleExtensionConfigService.INSTANCE.isProviderEnabled(p.providerId())));
            }
        }
        for (var e : section.providers().entrySet()) {
            boolean known = providers.stream().anyMatch(r -> r.id.equals(e.getKey()));
            if (!known) {
                providers.add(new ProviderRow(e.getKey(), "", false, e.getValue()));
            }
        }

        entries.clear();
        for (var row : payload.entries()) {
            entries.add(new EntryRow(row.entryId(), row.operation(), row.status(),
                    row.enabledSource(), row.statusMessage()));
        }
        entries.sort((a, b) -> a.entryId.compareTo(b.entryId));

        handshakeStatus = RoleHandshakeState.INSTANCE.status().name();
        handshakeMessage = RoleHandshakeState.INSTANCE.message() == null
                ? "" : RoleHandshakeState.INSTANCE.message();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta,
                       int x, int y, int w, int h) {
        refresh();
        g.fill(x, y, x + w, y + h, MenuTheme.BG_PANEL);

        int headerY = y + 4;
        g.drawString(font, "§l角色扩展 v2（服务端权威）", x + 6, headerY + 2, 0xFFFFFF);
        PillToggle.render(g, font, x + w - 150, headerY, 66, 14, section.isEnabled(), "全局启用", "全局停用");
        PillToggle.render(g, font, x + w - 78, headerY, 68, 14, section.isAllowGlobalHooks(), "全局Hook", "关");

        int bannerY = headerY + 22;
        int bannerColor = "OK".equals(handshakeStatus) ? 0xFF55C28A
                : "DEGRADED_CLIENT_EXTENSION".equals(handshakeStatus) ? 0xFFD4A55A : 0xFFC25555;
        g.fill(x + 4, bannerY, x + w - 4, bannerY + 18, MenuTheme.withAlpha(bannerColor, 0x30));
        g.drawString(font, "握手: " + handshakeStatus + (handshakeMessage.isBlank() ? "" : "  " + handshakeMessage),
                x + 8, bannerY + 4, bannerColor, false);

        int listY = bannerY + 24;
        int listH = h - (listY - y) - 4;
        List<Row> rows = buildRows();
        int totalH = rows.size() * (ROW_H + ROW_GAP);
        int maxScroll = Math.max(0, totalH - listH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        g.enableScissor(x, listY, x + w, listY + listH);
        int rowY = listY - (int) scrollOffset;
        for (Row row : rows) {
            if (rowY + ROW_H > listY && rowY < listY + listH) {
                renderRow(g, x + 4, rowY, w - 8, row);
            }
            rowY += ROW_H + ROW_GAP;
        }
        g.disableScissor();
        if (maxScroll > 0) {
            MenuTheme.drawScrollbar(g, x + w - 6, listY, listH, scrollOffset, maxScroll, 4);
        }

        if (!editable) {
            g.drawString(font, "§c仅 OP 可修改（普通客户端只读）", x + 4, y + h - 12, 0xFFC25555, false);
        } else if (!"OK".equals(handshakeStatus)) {
            g.drawString(font, "§c握手未通过，仅只读", x + 4, y + h - 12, 0xFFC25555, false);
        }
    }

    private void renderRow(GuiGraphics g, int x, int y, int w, Row row) {
        if (row.header) {
            g.fill(x, y, x + w, y + ROW_H, MenuTheme.BG_SIDEBAR);
            g.drawString(font, "§7" + row.label, x + 4, y + (ROW_H - font.lineHeight) / 2, 0xFF9AA4B2, false);
            return;
        }
        g.fill(x, y, x + w, y + ROW_H, MenuTheme.BG_ROW);
        int toggleW = (editable && row.toggleKey != null) ? 54 : 0;
        String statusText = row.status;
        int statusX = x + w - toggleW - 6 - font.width(statusText);
        g.drawString(font, fit(row.label, statusX - x - 10), x + 6, y + (ROW_H - font.lineHeight) / 2, 0xFFFFFFFF, false);
        g.drawString(font, statusText, statusX, y + (ROW_H - font.lineHeight) / 2, statusColor(row.status), false);
        if (editable && row.toggleKey != null) {
            boolean on = row.status.startsWith("on") || row.status.startsWith("ACTIVE");
            PillToggle.render(g, font, x + w - 52, y, 48, ROW_H - 2, on, "开", "关");
        }
    }

    private int statusColor(String status) {
        if (status == null) return MenuTheme.TEXT_SECONDARY;
        if (status.startsWith("on") || status.startsWith("ACTIVE")) return 0xFF55C28A;
        if (status.startsWith("off") || status.startsWith("DISABLED")) return 0xFFC25555;
        if (status.startsWith("CONFLICT")) return 0xFFFFAA00;
        if (status.startsWith("INVALID")) return 0xFF666666;
        if (status.startsWith("LEGACY")) return 0xFF687382;
        if (status.startsWith("HOOK")) return 0xFFD4A55A;
        return MenuTheme.TEXT_SECONDARY;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (!editable || !"OK".equals(handshakeStatus)) {
            return false;
        }
        refresh();

        int headerY = y + 4;
        if (PillToggle.hit(mx, my, x + w - 150, headerY, 66, 14)) {
            MenuSounds.playClick();
            section.setEnabled(!section.isEnabled());
            sendConfig();
            return true;
        }
        if (PillToggle.hit(mx, my, x + w - 78, headerY, 68, 14)) {
            MenuSounds.playClick();
            section.setAllowGlobalHooks(!section.isAllowGlobalHooks());
            sendConfig();
            return true;
        }

        int bannerY = headerY + 22;
        int listY = bannerY + 24;
        List<Row> rows = buildRows();
        int rowIndex = (int) ((my - listY + scrollOffset) / (ROW_H + ROW_GAP));
        if (rowIndex >= 0 && rowIndex < rows.size()) {
            Row row = rows.get(rowIndex);
            if (!row.header && row.toggleKey != null) {
                int rowVisualY = listY + rowIndex * (ROW_H + ROW_GAP) - (int) scrollOffset;
                if (MenuTheme.inBounds(mx, my, x + w - 52, rowVisualY, 48, ROW_H - 2)) {
                    MenuSounds.playClick();
                    toggleRow(row.toggleKey);
                    return true;
                }
            }
        }
        return false;
    }

    private void toggleRow(String key) {
        String id = key.substring(key.indexOf(':') + 1);
        if (key.startsWith("provider:")) {
            boolean current = RoleExtensionConfigService.INSTANCE.isProviderEnabled(id);
            section.setProviderEnabled(id, !current);
        } else {
            boolean current = RoleExtensionConfigService.INSTANCE.isEntryEnabled(id);
            section.setEntryEnabled(id, !current);
        }
        sendConfig();
    }

    private void sendConfig() {
        String json = RoleExtensionConfigService.toJsonString(section);
        com.habitrain.core.client.network.PayloadSenders.sendRoleConfigUpdate(json);
        payloadGeneration = -1; // 等待服务端回推后 refresh
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        RoleSnapshotPayload snapshot = RoleSnapshotState.INSTANCE.get();
        if (snapshot != null) {
            rows.add(Row.header("snapshots  current=" + snapshot.lobbySnapshotId()
                    + (snapshot.roundSnapshotId() == null ? "" : "  round=" + snapshot.roundSnapshotId())
                    + (snapshot.pendingSnapshotId() == null ? "" : "  pending=" + snapshot.pendingSnapshotId())));
        }
        rows.add(Row.header("providers (" + providers.size() + ")"));
        for (ProviderRow p : providers) {
            rows.add(Row.item(p.id + (p.version.isBlank() ? "" : "@" + p.version)
                    + (p.requiredClient ? " [必需]" : ""),
                    p.enabled ? "on" : "off", "provider:" + p.id, null));
        }
        rows.add(Row.header("entries (" + entries.size() + ")"));
        for (EntryRow e : entries) {
            boolean on = "ACTIVE".equals(e.status);
            rows.add(Row.item(e.entryId + "  [" + e.operation + "]",
                    e.status + (e.enabledSource() == null ? "" : " via " + e.enabledSource()),
                    on ? null : "entry:" + e.entryId, e.message));
        }
        return rows;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy,
                                int x, int y, int w, int h) { return false; }
    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy,
                                  int x, int y, int w, int h) {
        int bannerY = y + 4 + 22;
        int listY = bannerY + 24;
        int listH = h - (listY - y) - 4;
        int totalH = buildRows().size() * (ROW_H + ROW_GAP);
        int maxScroll = Math.max(0, totalH - listH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - sy * 12, maxScroll));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) { return false; }
    @Override
    public boolean charTyped(char ch, int mod) { return false; }

    private String fit(String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    private record ProviderRow(String id, String version, boolean requiredClient, boolean enabled) {}
    private record EntryRow(String entryId, String operation, String status,
                            String enabledSource, String message) {}
    private record Row(String label, String status, String toggleKey, boolean header, String message) {
        static Row header(String label) {
            return new Row(label, "", null, true, null);
        }
        static Row item(String label, String status, String toggleKey, String message) {
            return new Row(label, status, toggleKey, false, message);
        }
    }
}
