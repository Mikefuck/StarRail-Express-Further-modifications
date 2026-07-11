package com.habitrain.core.client.gui.config;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.config.ModeVoteEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "投票设置" Tab — modeMapVote 总开关、时长、模式/地图可投票列表。
 */
public class VoteTabScreen {

    private static final int PAD = 12;
    private static final int ROW_H = 24;
    private static final int HEADER_H = 18;
    private static final int SECTION_GAP = 8;
    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 120;
    private static final int ACCENT = 0xFF7C9CFF;

    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;

    private boolean widgetsInitialized = false;
    private EditBox modeDurationField;
    private EditBox mapDurationField;

    private double contentScroll = 0;
    private boolean draggingContent = false;
    private double dragStartY = 0;
    private double dragStartScroll = 0;
    private int contentHeight = 0;

    private final List<RowHit> modeHits = new ArrayList<>();
    private final List<RowHit> mapHits = new ArrayList<>();
    private final List<ButtonHit> buttonHits = new ArrayList<>();

    private final List<String> modeIds = new ArrayList<>();
    private final List<String> mapIds = new ArrayList<>();
    private final java.util.Map<String, EditBox> modeNameFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, EditBox> mapNameFields = new java.util.LinkedHashMap<>();

    private record RowHit(String id, int toggleX, int toggleW, int mapsX, int mapsW, int y, int h, boolean mode) {}
    private record ButtonHit(String action, int x, int y, int w, int h) {}

    public VoteTabScreen(ConfigRootScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        rebuildIdLists();
    }

    private ModeMapVoteSettings settings() {
        return ConfigManager.getInstance().getModeMapVoteSettings();
    }

    private void rebuildIdLists() {
        ModeMapVoteSettings s = settings();
        Set<String> modes = new LinkedHashSet<>();
        try {
            modes.addAll(GameModeRegistry.getAllIds());
        } catch (Throwable ignored) {
            // client-safe: registry may be empty
        }
        modes.addAll(s.modes.keySet());
        modeIds.clear();
        modeIds.addAll(modes);

        Set<String> maps = new LinkedHashSet<>(s.maps.keySet());
        for (ModeVoteEntry me : s.modes.values()) {
            if (me.allowedMaps != null) maps.addAll(me.allowedMaps);
        }
        mapIds.clear();
        mapIds.addAll(maps);

        // ensure entries exist for UI mutation
        for (String id : modeIds) {
            s.modes.computeIfAbsent(id, k -> ModeVoteEntry.createDefault());
        }
        for (String id : mapIds) {
            s.maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
        }
    }

    private void ensureWidgetsInitialized() {
        if (widgetsInitialized) return;
        widgetsInitialized = true;
        ModeMapVoteSettings s = settings();

        modeDurationField = new EditBox(font, -10000, -10000, 48, 14, Component.literal(""));
        modeDurationField.setMaxLength(3);
        modeDurationField.setValue(String.valueOf(s.modeDurationSeconds));
        modeDurationField.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
        modeDurationField.setEditable(editable);

        mapDurationField = new EditBox(font, -10000, -10000, 48, 14, Component.literal(""));
        mapDurationField.setMaxLength(3);
        mapDurationField.setValue(String.valueOf(s.mapDurationSeconds));
        mapDurationField.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
        mapDurationField.setEditable(editable);

        rebuildNameFields();
    }

    private void rebuildNameFields() {
        ModeMapVoteSettings s = settings();
        for (String id : modeIds) {
            if (!modeNameFields.containsKey(id)) {
                ModeVoteEntry e = s.modes.computeIfAbsent(id, k -> ModeVoteEntry.createDefault());
                EditBox box = new EditBox(font, -10000, -10000, 120, 14, Component.literal(""));
                box.setMaxLength(64);
                String dn = e.displayName != null ? e.displayName : "";
                if (dn.isEmpty()) dn = resolveModeDisplay(id);
                box.setValue(dn);
                box.setEditable(editable);
                modeNameFields.put(id, box);
            }
        }
        for (String id : mapIds) {
            if (!mapNameFields.containsKey(id)) {
                MapVoteEntry e = s.maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
                EditBox box = new EditBox(font, -10000, -10000, 120, 14, Component.literal(""));
                box.setMaxLength(64);
                String dn = e.displayName != null && !e.displayName.isEmpty() ? e.displayName : id;
                box.setValue(dn);
                box.setEditable(editable);
                mapNameFields.put(id, box);
            }
        }
    }

    private String resolveModeDisplay(String id) {
        try {
            GameMode mode = GameModeRegistry.get(id);
            if (mode != null && mode.getDisplayName() != null && !mode.getDisplayName().isBlank()) {
                return mode.getDisplayName();
            }
        } catch (Throwable ignored) {}
        int idx = id.lastIndexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        ensureWidgetsInitialized();
        rebuildIdLists();
        rebuildNameFields();

        modeHits.clear();
        mapHits.clear();
        buttonHits.clear();

        int listTop = y;
        int listH = h;
        g.enableScissor(x, listTop, x + w, listTop + listH);

        int cy = listTop + 6 - (int) contentScroll;
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        // ===== 总开关 =====
        g.drawString(font, Component.literal("§e§l模式/地图投票"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        ModeMapVoteSettings s = settings();
        int toggleW = 70;
        int toggleX = labelX;
        g.fill(toggleX, cy, toggleX + toggleW, cy + 16, s.enabled ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
        g.drawString(font, s.enabled ? "§a已启用" : "§c已停用", toggleX + 6, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("toggle_enabled", toggleX, cy, toggleW, 16));
        cy += ROW_H + 4;

        // ===== 时长 =====
        g.drawString(font, Component.literal("§e§l投票时长（秒，5–120）"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        g.drawString(font, "模式投票:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
        modeDurationField.setX(labelX + 60);
        modeDurationField.setY(cy);
        modeDurationField.setWidth(48);
        modeDurationField.render(g, mx, my, delta);
        g.drawString(font, "地图投票:", labelX + 130, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
        mapDurationField.setX(labelX + 190);
        mapDurationField.setY(cy);
        mapDurationField.setWidth(48);
        mapDurationField.render(g, mx, my, delta);
        cy += ROW_H + SECTION_GAP;

        // ===== 模式列表 =====
        g.fill(labelX - 2, cy - 2, labelX + innerW + 2, cy - 1, SharedGuiKit.SEPARATOR);
        cy += 4;
        g.drawString(font, Component.literal("§e§l可投票模式"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        if (modeIds.isEmpty()) {
            g.drawString(font, "§7暂无模式条目（启动投票或注册模式后出现）", labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            for (String id : modeIds) {
                ModeVoteEntry e = s.modes.computeIfAbsent(id, k -> ModeVoteEntry.createDefault());
                boolean hover = SharedGuiKit.inBounds(mx, my, labelX, cy, innerW, ROW_H);
                g.fill(labelX, cy, labelX + innerW, cy + ROW_H - 2,
                        hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW);
                SharedGuiKit.drawAccentStripe(g, labelX, cy, ROW_H - 2, SharedGuiKit.accentFor(id));

                int tX = labelX + 6;
                int tW = 36;
                g.fill(tX, cy + 4, tX + tW, cy + 18, e.enabled ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
                g.drawString(font, e.enabled ? "§a开" : "§c关", tX + 8, cy + 6, 0xFFFFFFFF, false);

                String shortId = id.length() > 18 ? id.substring(0, 16) + "…" : id;
                g.drawString(font, "§7" + shortId, tX + tW + 6, cy + 6, SharedGuiKit.TEXT_SECONDARY, false);

                EditBox nameBox = modeNameFields.get(id);
                if (nameBox != null) {
                    nameBox.setX(labelX + 160);
                    nameBox.setY(cy + 4);
                    nameBox.setWidth(Math.max(60, innerW - 280));
                    nameBox.render(g, mx, my, delta);
                }

                int mapsW = 60;
                int mapsX = labelX + innerW - mapsW - 6;
                g.fill(mapsX, cy + 4, mapsX + mapsW, cy + 18, SharedGuiKit.BG_EDIT);
                g.drawString(font, "§e可选地图", mapsX + 4, cy + 6, 0xFFFFFFFF, false);

                modeHits.add(new RowHit(id, tX, tW, mapsX, mapsW, cy, ROW_H - 2, true));
                cy += ROW_H;
            }
        }

        cy += SECTION_GAP;
        g.fill(labelX - 2, cy - 2, labelX + innerW + 2, cy - 1, SharedGuiKit.SEPARATOR);
        cy += 4;
        g.drawString(font, Component.literal("§e§l可投票地图"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        if (mapIds.isEmpty()) {
            g.drawString(font, "§7暂无地图条目（服务端 ensureDefaults 后出现）", labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            for (String id : mapIds) {
                MapVoteEntry e = s.maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
                boolean hover = SharedGuiKit.inBounds(mx, my, labelX, cy, innerW, ROW_H);
                g.fill(labelX, cy, labelX + innerW, cy + ROW_H - 2,
                        hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW);
                SharedGuiKit.drawAccentStripe(g, labelX, cy, ROW_H - 2, SharedGuiKit.accentFor(id));

                int tX = labelX + 6;
                int tW = 36;
                g.fill(tX, cy + 4, tX + tW, cy + 18, e.enabled ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
                g.drawString(font, e.enabled ? "§a开" : "§c关", tX + 8, cy + 6, 0xFFFFFFFF, false);

                String shortId = id.length() > 22 ? id.substring(0, 20) + "…" : id;
                g.drawString(font, "§7" + shortId, tX + tW + 6, cy + 6, SharedGuiKit.TEXT_SECONDARY, false);

                EditBox nameBox = mapNameFields.get(id);
                if (nameBox != null) {
                    nameBox.setX(labelX + 180);
                    nameBox.setY(cy + 4);
                    nameBox.setWidth(Math.max(60, innerW - 200));
                    nameBox.render(g, mx, my, delta);
                }

                mapHits.add(new RowHit(id, tX, tW, 0, 0, cy, ROW_H - 2, false));
                cy += ROW_H;
            }
        }

        cy += SECTION_GAP + 4;
        int saveW = 80;
        int saveX = labelX;
        g.fill(saveX, cy, saveX + saveW, cy + 18, 0xFF1B3A2A);
        g.drawString(font, "§a保存", saveX + 28, cy + 5, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("save", saveX, cy, saveW, 18));
        cy += ROW_H + 8;

        contentHeight = cy - listTop + (int) contentScroll;
        int maxScroll = Math.max(0, contentHeight - listH);
        contentScroll = Mth.clamp(contentScroll, 0, maxScroll);
        SharedGuiKit.drawScrollbar(g, x + w - 4, listTop, listH, contentScroll, maxScroll, 3);
        g.disableScissor();

        if (!editable) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改"),
                    labelX, y + h - 14, 0xFF5555, false);
        }
    }

    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        ensureWidgetsInitialized();
        if (modeDurationField != null && modeDurationField.mouseClicked(mx, my, btn)) return true;
        if (mapDurationField != null && mapDurationField.mouseClicked(mx, my, btn)) return true;
        for (EditBox box : modeNameFields.values()) {
            if (box.mouseClicked(mx, my, btn)) return true;
        }
        for (EditBox box : mapNameFields.values()) {
            if (box.mouseClicked(mx, my, btn)) return true;
        }

        for (ButtonHit hit : buttonHits) {
            if (SharedGuiKit.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                if ("toggle_enabled".equals(hit.action())) {
                    if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
                    ModeMapVoteSettings s = settings();
                    s.enabled = !s.enabled;
                    persist();
                    return true;
                }
                if ("save".equals(hit.action())) {
                    if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
                    commitAndSave();
                    return true;
                }
            }
        }

        for (RowHit hit : modeHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
                ModeVoteEntry e = settings().modes.computeIfAbsent(hit.id(), k -> ModeVoteEntry.createDefault());
                e.enabled = !e.enabled;
                persist();
                return true;
            }
            if (hit.mapsW() > 0 && mx >= hit.mapsX() && mx < hit.mapsX() + hit.mapsW()) {
                if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
                commitFieldsToSettings();
                Minecraft.getInstance().setScreen(new ModeAllowedMapsScreen(root, hit.id(), editable));
                return true;
            }
        }

        for (RowHit hit : mapHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
                MapVoteEntry e = settings().maps.computeIfAbsent(hit.id(), k -> MapVoteEntry.createDefault());
                e.enabled = !e.enabled;
                persist();
                return true;
            }
        }

        if (my >= y && my < y + h) {
            draggingContent = true;
            dragStartY = my;
            dragStartScroll = contentScroll;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (draggingContent) {
            contentScroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, Math.max(0, contentHeight));
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        contentScroll = Mth.clamp(contentScroll - sy * 18, 0, Math.max(0, contentHeight));
        return true;
    }

    public boolean keyPressed(int key, int scan, int mod) {
        if (modeDurationField != null && modeDurationField.isFocused() && modeDurationField.keyPressed(key, scan, mod)) return true;
        if (mapDurationField != null && mapDurationField.isFocused() && mapDurationField.keyPressed(key, scan, mod)) return true;
        for (EditBox box : modeNameFields.values()) {
            if (box.isFocused() && box.keyPressed(key, scan, mod)) return true;
        }
        for (EditBox box : mapNameFields.values()) {
            if (box.isFocused() && box.keyPressed(key, scan, mod)) return true;
        }
        return false;
    }

    public boolean charTyped(char ch, int mod) {
        if (modeDurationField != null && modeDurationField.isFocused() && modeDurationField.charTyped(ch, mod)) return true;
        if (mapDurationField != null && mapDurationField.isFocused() && mapDurationField.charTyped(ch, mod)) return true;
        for (EditBox box : modeNameFields.values()) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        for (EditBox box : mapNameFields.values()) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        return false;
    }

    private int clampDuration(int v) {
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, v));
    }

    private void commitFieldsToSettings() {
        ModeMapVoteSettings s = settings();
        try {
            String v = modeDurationField.getValue().trim();
            if (!v.isEmpty()) s.modeDurationSeconds = clampDuration(Integer.parseInt(v));
        } catch (NumberFormatException ignored) {}
        try {
            String v = mapDurationField.getValue().trim();
            if (!v.isEmpty()) s.mapDurationSeconds = clampDuration(Integer.parseInt(v));
        } catch (NumberFormatException ignored) {}
        modeDurationField.setValue(String.valueOf(s.modeDurationSeconds));
        mapDurationField.setValue(String.valueOf(s.mapDurationSeconds));

        for (var e : modeNameFields.entrySet()) {
            ModeVoteEntry me = s.modes.computeIfAbsent(e.getKey(), k -> ModeVoteEntry.createDefault());
            me.displayName = e.getValue().getValue() != null ? e.getValue().getValue().trim() : "";
        }
        for (var e : mapNameFields.entrySet()) {
            MapVoteEntry me = s.maps.computeIfAbsent(e.getKey(), k -> MapVoteEntry.createDefault());
            me.displayName = e.getValue().getValue() != null ? e.getValue().getValue().trim() : "";
        }
    }

    private void persist() {
        ModeMapVoteSettings s = settings();
        ConfigManager.getInstance().setModeMapVoteSettings(s);
    }

    private void commitAndSave() {
        commitFieldsToSettings();
        persist();
        ConfigManager.getInstance().save();
        var p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(Component.literal("§a投票设置已保存"), true);
        }
    }
}
