package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MapPoolEditorScreen;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ModeAllowedMapsScreen;
import com.habitrain.core.client.gui.menu.ui.SubTabBar;
import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapPoolEntry;
import com.habitrain.core.config.MapPoolRotationSettings;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.config.ModeVoteEntry;
import com.habitrain.core.vote.MapPoolRotationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 游戏外·投票：内层子 Tab 拆分旧 VoteTabScreen。
 * 主设置 / 地图池轮换 / 可投票模式 / 可投票地图。
 */
public class OutGameVotePage implements ConfigPage {

    private static final String[] SUB_LABELS = {"主设置", "地图池轮换", "可投票模式", "可投票地图"};
    private static final int[] SUB_ACCENTS = {0xFF7C9CFF, 0xFF7C9CFF, 0xFF7C9CFF, 0xFF7C9CFF};

    private static final int PAD = 12;
    private static final int ROW_H = 24;
    private static final int HEADER_H = 18;
    private static final int SECTION_GAP = 8;
    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 120;
    private static final int ACCENT = 0xFF7C9CFF;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private int innerTab = 0;
    private int subHitThisFrame = -1;
    private final SubTabBar subTabBar = new SubTabBar(SUB_LABELS, SUB_ACCENTS);

    private boolean widgetsInitialized = false;
    private EditBox modeDurationField;
    private EditBox mapDurationField;

    // 每个内层子 Tab 独立的滚动状态
    private final double[] scroll = new double[4];
    private final boolean[] dragging = new boolean[4];
    private final double[] dragStartY = new double[4];
    private final double[] dragStartScroll = new double[4];
    private final int[] contentHeight = new int[4];
    private int listHeight = 0;

    private final List<RowHit> modeHits = new ArrayList<>();
    private final List<RowHit> mapHits = new ArrayList<>();
    private final List<ButtonHit> buttonHits = new ArrayList<>();

    private final List<String> modeIds = new ArrayList<>();
    private final List<String> mapIds = new ArrayList<>();
    private final Map<String, EditBox> modeNameFields = new LinkedHashMap<>();
    private final Map<String, EditBox> mapNameFields = new LinkedHashMap<>();

    private record RowHit(String id, int toggleX, int toggleW, int mapsX, int mapsW, int y, int h, boolean mode) {}
    private record ButtonHit(String action, int x, int y, int w, int h) {}

    public OutGameVotePage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        rebuildIdLists();
    }

    @Override public boolean canSave() { return true; }

    /** 投票页所有改动即时生效；写盘统一由根 SaveBar 处理。 */
    @Override public void save() { /* no-op */ }

    /** 提交未确认文本（时长/显示名）并标记脏。 */
    @Override public void flushPending() {
        if (!editable || !widgetsInitialized) return;
        commitFieldsToSettings();
        persist();
    }

    private ModeMapVoteSettings settings() {
        return ConfigManager.getInstance().getModeMapVoteSettings();
    }

    /**
     * Build display id lists from registry + existing settings keys only.
     * Does NOT insert default entries into ConfigManager settings.
     */
    private void rebuildIdLists() {
        ModeMapVoteSettings s = settings();
        // Prefer persisted LinkedHashMap order, then append registry-only ids.
        LinkedHashSet<String> modes = new LinkedHashSet<>(s.modes.keySet());
        try {
            modes.addAll(GameModeRegistry.getAllIds());
        } catch (Throwable ignored) {
            // client-safe: registry may be empty
        }
        modeIds.clear();
        modeIds.addAll(modes);

        Set<String> maps = new LinkedHashSet<>(s.maps.keySet());
        for (ModeVoteEntry me : s.modes.values()) {
            if (me.allowedMaps != null) maps.addAll(me.allowedMaps);
        }
        mapIds.clear();
        mapIds.addAll(maps);
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

    /** Create display EditBoxes without writing missing ids into settings. */
    private void rebuildNameFields() {
        ModeMapVoteSettings s = settings();
        for (String id : modeIds) {
            if (!modeNameFields.containsKey(id)) {
                ModeVoteEntry e = s.modes.get(id);
                EditBox box = new EditBox(font, -10000, -10000, 120, 14, Component.literal(""));
                box.setMaxLength(64);
                String dn = (e != null && e.displayName != null && !e.displayName.isEmpty())
                        ? e.displayName : resolveModeDisplay(id);
                box.setValue(dn);
                box.setEditable(editable);
                modeNameFields.put(id, box);
            }
        }
        for (String id : mapIds) {
            if (!mapNameFields.containsKey(id)) {
                MapVoteEntry e = s.maps.get(id);
                EditBox box = new EditBox(font, -10000, -10000, 120, 14, Component.literal(""));
                box.setMaxLength(64);
                String dn = (e != null && e.displayName != null && !e.displayName.isEmpty())
                        ? e.displayName : id;
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

    private boolean modeEnabled(ModeMapVoteSettings s, String id) {
        ModeVoteEntry e = s.modes.get(id);
        return e == null || e.enabled;
    }

    private boolean mapEnabled(ModeMapVoteSettings s, String id) {
        MapVoteEntry e = s.maps.get(id);
        return e == null || e.enabled;
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        ensureWidgetsInitialized();
        rebuildIdLists();
        rebuildNameFields();

        subHitThisFrame = subTabBar.render(g, font, x, y, w, innerTab, mx, my);
        int contentY = y + SubTabBar.H + 4;
        int contentH = h - SubTabBar.H - 4;
        switch (innerTab) {
            case 0 -> renderMain(g, mx, my, delta, x, contentY, w, contentH);
            case 1 -> renderPoolRotation(g, mx, my, delta, x, contentY, w, contentH);
            case 2 -> renderVoteModes(g, mx, my, delta, x, contentY, w, contentH);
            default -> renderVoteMaps(g, mx, my, delta, x, contentY, w, contentH);
        }

        if (!editable) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改"),
                    x, y + h - 14, 0xFF5555, false);
        }
    }

    private void renderMain(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int listTop = y;
        int listH = h;
        listHeight = listH;
        g.enableScissor(x, listTop, x + w, listTop + listH);

        int cy = listTop + 6 - (int) scroll[0];
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        // ===== 总开关 =====
        g.drawString(font, Component.literal("§e§l模式/地图投票"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        ModeMapVoteSettings s = settings();
        int toggleW = 70;
        int toggleX = labelX;
        g.fill(toggleX, cy, toggleX + toggleW, cy + 16, s.enabled ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        g.drawString(font, s.enabled ? "§a已启用" : "§c已停用", toggleX + 6, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("toggle_enabled", toggleX, cy, toggleW, 16));
        cy += ROW_H + 4;

        // ===== 时长 =====
        g.drawString(font, Component.literal("§e§l投票时长（秒，5–120）"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        g.drawString(font, "模式投票:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        modeDurationField.setX(labelX + 60);
        modeDurationField.setY(cy);
        modeDurationField.setWidth(48);
        modeDurationField.render(g, mx, my, delta);
        g.drawString(font, "地图投票:", labelX + 130, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        mapDurationField.setX(labelX + 190);
        mapDurationField.setY(cy);
        mapDurationField.setWidth(48);
        mapDurationField.render(g, mx, my, delta);
        cy += ROW_H + SECTION_GAP;

        contentHeight[0] = cy - listTop + (int) scroll[0];
        int maxScroll = Math.max(0, contentHeight[0] - listH);
        scroll[0] = Mth.clamp(scroll[0], 0, maxScroll);
        MenuTheme.drawScrollbar(g, x + w - 4, listTop, listH, scroll[0], maxScroll, 3);
        g.disableScissor();
    }

    private void renderPoolRotation(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int listTop = y;
        int listH = h;
        listHeight = listH;
        g.enableScissor(x, listTop, x + w, listTop + listH);

        int cy = listTop + 6 - (int) scroll[1];
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        ModeMapVoteSettings s = settings();

        // ===== 地图池轮换 =====
        g.fill(labelX - 2, cy - 2, labelX + innerW + 2, cy - 1, MenuTheme.SEPARATOR);
        cy += 4;
        g.drawString(font, Component.literal("§e§l地图池轮换"), labelX, cy, ACCENT, false);
        cy += HEADER_H;

        MapPoolRotationSettings rot = s.rotationOrDefault();
        MapPoolEntry curPool = rot.poolAt(rot.activePoolIndex);
        int poolCount = curPool.mapIds != null ? curPool.mapIds.size() : 0;

        // row: enable rotation
        int rToggleW = 70;
        g.fill(labelX, cy, labelX + rToggleW, cy + 16,
                rot.enabled ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        g.drawString(font, rot.enabled ? "§a轮换开" : "§c轮换关", labelX + 6, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("pool_toggle", labelX, cy, rToggleW, 16));

        int autoX = labelX + rToggleW + 8;
        int autoW = 80;
        g.fill(autoX, cy, autoX + autoW, cy + 16,
                rot.autoRepartition ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        g.drawString(font, rot.autoRepartition ? "§a自动重分" : "§c不重分", autoX + 6, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("pool_auto", autoX, cy, autoW, 16));

        int modeX = autoX + autoW + 8;
        int modeW = 100;
        boolean direct = rot.isDirectPick();
        g.fill(modeX, cy, modeX + modeW, cy + 16, MenuTheme.BG_EDIT);
        g.drawString(font, direct ? "§e直接抽图" : "§e限制投票", modeX + 8, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("pool_apply_mode", modeX, cy, modeW, 16));
        cy += ROW_H;

        String summary = "每局轮换 · 共" + rot.poolCount() + "池 · 当前池" + (rot.activePoolIndex + 1) + " · "
                + (curPool.displayName != null ? curPool.displayName : "")
                + " · " + poolCount + "图";
        g.drawString(font, "§7" + summary, labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H;

        int editW = 80;
        g.fill(labelX, cy, labelX + editW, cy + 16, MenuTheme.BG_EDIT);
        g.drawString(font, "§e编辑池子", labelX + 12, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("pool_edit", labelX, cy, editW, 16));

        int skipX = labelX + editW + 8;
        int skipW = 80;
        g.fill(skipX, cy, skipX + skipW, cy + 16, editable ? 0xFF3A2A1B : MenuTheme.BG_DISABLED);
        g.drawString(font, "§6跳过当前", skipX + 12, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("pool_skip", skipX, cy, skipW, 16));
        cy += ROW_H + SECTION_GAP;

        contentHeight[1] = cy - listTop + (int) scroll[1];
        int maxScroll = Math.max(0, contentHeight[1] - listH);
        scroll[1] = Mth.clamp(scroll[1], 0, maxScroll);
        MenuTheme.drawScrollbar(g, x + w - 4, listTop, listH, scroll[1], maxScroll, 3);
        g.disableScissor();
    }

    private void renderVoteModes(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int listTop = y;
        int listH = h;
        listHeight = listH;
        g.enableScissor(x, listTop, x + w, listTop + listH);

        int cy = listTop + 6 - (int) scroll[2];
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        ModeMapVoteSettings s = settings();

        // ===== 模式列表 =====
        g.fill(labelX - 2, cy - 2, labelX + innerW + 2, cy - 1, MenuTheme.SEPARATOR);
        cy += 4;
        g.drawString(font, Component.literal("§e§l可投票模式"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        if (modeIds.isEmpty()) {
            g.drawString(font, "§7暂无模式条目（启动投票或注册模式后出现）", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            for (String id : modeIds) {
                boolean enabled = modeEnabled(s, id);
                boolean hover = MenuTheme.inBounds(mx, my, labelX, cy, innerW, ROW_H);
                g.fill(labelX, cy, labelX + innerW, cy + ROW_H - 2,
                        hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
                MenuTheme.drawAccentStripe(g, labelX, cy, ROW_H - 2, MenuTheme.accentFor(id));

                int tX = labelX + 6;
                int tW = 36;
                g.fill(tX, cy + 4, tX + tW, cy + 18, enabled ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
                g.drawString(font, enabled ? "§a开" : "§c关", tX + 8, cy + 6, 0xFFFFFFFF, false);

                String shortId = id.length() > 18 ? id.substring(0, 16) + "…" : id;
                g.drawString(font, "§7" + shortId, tX + tW + 6, cy + 6, MenuTheme.TEXT_SECONDARY, false);

                EditBox nameBox = modeNameFields.get(id);
                if (nameBox != null) {
                    nameBox.setX(labelX + 160);
                    nameBox.setY(cy + 4);
                    nameBox.setWidth(Math.max(40, innerW - 340));
                    nameBox.render(g, mx, my, delta);
                }

                int mapsW = 60;
                int mapsX = labelX + innerW - mapsW - 6;
                g.fill(mapsX, cy + 4, mapsX + mapsW, cy + 18, MenuTheme.BG_EDIT);
                g.drawString(font, "§e可选地图", mapsX + 4, cy + 6, 0xFFFFFFFF, false);

                int upW = 16;
                int downW = 16;
                int downX = mapsX - 6 - downW;
                int upX = downX - 4 - upW;
                g.fill(upX, cy + 4, upX + upW, cy + 18, MenuTheme.BG_EDIT);
                g.drawString(font, "§f↑", upX + 4, cy + 6, 0xFFFFFFFF, false);
                g.fill(downX, cy + 4, downX + downW, cy + 18, MenuTheme.BG_EDIT);
                g.drawString(font, "§f↓", downX + 4, cy + 6, 0xFFFFFFFF, false);
                buttonHits.add(new ButtonHit("mode_up:" + id, upX, cy + 4, upW, 14));
                buttonHits.add(new ButtonHit("mode_down:" + id, downX, cy + 4, downW, 14));

                modeHits.add(new RowHit(id, tX, tW, mapsX, mapsW, cy, ROW_H - 2, true));
                cy += ROW_H;
            }
        }

        cy += SECTION_GAP;
        contentHeight[2] = cy - listTop + (int) scroll[2];
        int maxScroll = Math.max(0, contentHeight[2] - listH);
        scroll[2] = Mth.clamp(scroll[2], 0, maxScroll);
        MenuTheme.drawScrollbar(g, x + w - 4, listTop, listH, scroll[2], maxScroll, 3);
        g.disableScissor();
    }

    private void renderVoteMaps(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int listTop = y;
        int listH = h;
        listHeight = listH;
        g.enableScissor(x, listTop, x + w, listTop + listH);

        int cy = listTop + 6 - (int) scroll[3];
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        ModeMapVoteSettings s = settings();

        // ===== 地图列表 =====
        g.fill(labelX - 2, cy - 2, labelX + innerW + 2, cy - 1, MenuTheme.SEPARATOR);
        cy += 4;
        g.drawString(font, Component.literal("§e§l可投票地图"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        if (mapIds.isEmpty()) {
            g.drawString(font, "§7暂无地图条目（服务端 ensureDefaults 后出现）", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            for (String id : mapIds) {
                boolean enabled = mapEnabled(s, id);
                boolean hover = MenuTheme.inBounds(mx, my, labelX, cy, innerW, ROW_H);
                g.fill(labelX, cy, labelX + innerW, cy + ROW_H - 2,
                        hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
                MenuTheme.drawAccentStripe(g, labelX, cy, ROW_H - 2, MenuTheme.accentFor(id));

                int tX = labelX + 6;
                int tW = 36;
                g.fill(tX, cy + 4, tX + tW, cy + 18, enabled ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
                g.drawString(font, enabled ? "§a开" : "§c关", tX + 8, cy + 6, 0xFFFFFFFF, false);

                String shortId = id.length() > 22 ? id.substring(0, 20) + "…" : id;
                g.drawString(font, "§7" + shortId, tX + tW + 6, cy + 6, MenuTheme.TEXT_SECONDARY, false);

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
        contentHeight[3] = cy - listTop + (int) scroll[3];
        int maxScroll = Math.max(0, contentHeight[3] - listH);
        scroll[3] = Mth.clamp(scroll[3], 0, maxScroll);
        MenuTheme.drawScrollbar(g, x + w - 4, listTop, listH, scroll[3], maxScroll, 3);
        g.disableScissor();
    }

    // ---------------- 输入 ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        ensureWidgetsInitialized();
        clearAllEditFocus();

        // 先处理内层子 Tab
        if (MenuTheme.inBounds(mx, my, x, y, w, SubTabBar.H)) {
            if (subHitThisFrame >= 0 && subHitThisFrame != innerTab) {
                flushPending();
                clearAllEditFocus();
                innerTab = subHitThisFrame;
            }
            return true;
        }

        int contentY = y + SubTabBar.H + 4;
        int contentH = h - SubTabBar.H - 4;
        if (my < contentY || my >= contentY + contentH) return false;

        switch (innerTab) {
            case 0 -> { return clickMain(mx, my, btn); }
            case 1 -> { return clickPoolRotation(mx, my, btn); }
            case 2 -> { return clickVoteModes(mx, my, btn); }
            default -> { return clickVoteMaps(mx, my, btn); }
        }
    }

    private boolean clickMain(double mx, double my, int btn) {
        if (tryFocusEditBox(modeDurationField, mx, my)) return true;
        if (tryFocusEditBox(mapDurationField, mx, my)) return true;
        for (ButtonHit hit : buttonHits) {
            if (MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                if ("toggle_enabled".equals(hit.action())) {
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    ModeMapVoteSettings s = settings();
                    s.enabled = !s.enabled;
                    persist();
                    return true;
                }
            }
        }
        // Content drag only on true miss (after unfocus) so fields stay selectable.
        dragging[0] = true;
        dragStartY[0] = my;
        dragStartScroll[0] = scroll[0];
        return true;
    }

    private boolean clickPoolRotation(double mx, double my, int btn) {
        for (ButtonHit hit : buttonHits) {
            if (!MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            switch (hit.action()) {
                case "pool_toggle" -> {
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    ModeMapVoteSettings s = settings();
                    MapPoolRotationSettings rot = s.rotationOrDefault();
                    rot.enabled = !rot.enabled;
                    if (rot.enabled) {
                        MapPoolRotationService.ensureSeededIfNeeded(s, new Random());
                    }
                    persist();
                    ConfigManager.getInstance().save();
                    return true;
                }
                case "pool_auto" -> {
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    ModeMapVoteSettings s = settings();
                    MapPoolRotationSettings rot = s.rotationOrDefault();
                    rot.autoRepartition = !rot.autoRepartition;
                    persist();
                    ConfigManager.getInstance().save();
                    return true;
                }
                case "pool_apply_mode" -> {
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    ModeMapVoteSettings s = settings();
                    MapPoolRotationSettings rot = s.rotationOrDefault();
                    rot.applyMode = rot.isDirectPick()
                            ? MapPoolRotationSettings.APPLY_LIMIT_VOTE
                            : MapPoolRotationSettings.APPLY_DIRECT_PICK;
                    persist();
                    ConfigManager.getInstance().save();
                    return true;
                }
                case "pool_edit" -> {
                    // 全员可进编辑页浏览；非 OP 只读
                    flushPending();
                    Minecraft.getInstance().setScreen(new MapPoolEditorScreen(root));
                    return true;
                }
                case "pool_skip" -> {
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    PayloadSenders.sendMapPoolSkip();
                    var p = Minecraft.getInstance().player;
                    if (p != null) {
                        p.displayClientMessage(Component.literal("§e已请求跳过当前地图池…"), true);
                    }
                    return true;
                }
                default -> { return true; }
            }
        }
        dragging[1] = true;
        dragStartY[1] = my;
        dragStartScroll[1] = scroll[1];
        return true;
    }

    private boolean clickVoteModes(double mx, double my, int btn) {
        for (EditBox box : modeNameFields.values()) {
            if (tryFocusEditBox(box, mx, my)) return true;
        }
        for (ButtonHit hit : buttonHits) {
            if (!MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            if (hit.action() != null && hit.action().startsWith("mode_up:")) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                String id = hit.action().substring("mode_up:".length());
                ensureModeEntry(id);
                if (settings().moveMode(id, -1)) {
                    rebuildIdLists();
                    persist();
                    // Save immediately so LinkedHashMap order reaches disk / C2S merge
                    // (persist alone only marks dirty; leaving the tab later is easy to miss).
                    ConfigManager.getInstance().save();
                }
                return true;
            }
            if (hit.action() != null && hit.action().startsWith("mode_down:")) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                String id = hit.action().substring("mode_down:".length());
                ensureModeEntry(id);
                if (settings().moveMode(id, +1)) {
                    rebuildIdLists();
                    persist();
                    ConfigManager.getInstance().save();
                }
                return true;
            }
        }
        for (RowHit hit : modeHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                // User edit: create entry only now
                ModeVoteEntry e = settings().modes.computeIfAbsent(hit.id(), k -> ModeVoteEntry.createDefault());
                e.enabled = !e.enabled;
                persist();
                return true;
            }
            if (hit.mapsW() > 0 && mx >= hit.mapsX() && mx < hit.mapsX() + hit.mapsW()) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                flushPending();
                Minecraft.getInstance().setScreen(new ModeAllowedMapsScreen(root, hit.id()));
                return true;
            }
        }
        dragging[2] = true;
        dragStartY[2] = my;
        dragStartScroll[2] = scroll[2];
        return true;
    }

    private boolean clickVoteMaps(double mx, double my, int btn) {
        for (EditBox box : mapNameFields.values()) {
            if (tryFocusEditBox(box, mx, my)) return true;
        }
        for (RowHit hit : mapHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                MapVoteEntry e = settings().maps.computeIfAbsent(hit.id(), k -> MapVoteEntry.createDefault());
                e.enabled = !e.enabled;
                persist();
                return true;
            }
        }
        dragging[3] = true;
        dragStartY[3] = my;
        dragStartScroll[3] = scroll[3];
        return true;
    }

    private void clearAllEditFocus() {
        if (modeDurationField != null) modeDurationField.setFocused(false);
        if (mapDurationField != null) mapDurationField.setFocused(false);
        for (EditBox box : modeNameFields.values()) {
            box.setFocused(false);
        }
        for (EditBox box : mapNameFields.values()) {
            box.setFocused(false);
        }
    }

    private boolean tryFocusEditBox(EditBox box, double mx, double my) {
        if (box == null) return false;
        int bx = box.getX();
        int by = box.getY();
        int bw = box.getWidth();
        int bh = box.getHeight();
        if (mx < bx || mx >= bx + bw || my < by || my >= by + bh) {
            return false;
        }
        if (!editable) {
            MenuPermissions.showDeniedMessage();
            return true;
        }
        box.setFocused(true);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (dragging[innerTab]) {
            int maxScroll = Math.max(0, contentHeight[innerTab] - listHeight);
            scroll[innerTab] = Mth.clamp(dragStartScroll[innerTab] + (dragStartY[innerTab] - my), 0, maxScroll);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging[innerTab]) {
            dragging[innerTab] = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        int idx = innerTab;
        int maxScroll = Math.max(0, contentHeight[idx] - listHeight);
        scroll[idx] = Mth.clamp(scroll[idx] - sy * 18, 0, maxScroll);
        return true;
    }

    @Override
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

    @Override
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
            String val = e.getValue().getValue() != null ? e.getValue().getValue().trim() : "";
            ModeVoteEntry me = s.modes.get(e.getKey());
            if (me != null) {
                me.displayName = val;
            } else if (!val.isEmpty() && !val.equals(resolveModeDisplay(e.getKey()))) {
                // Only create entry when user actually changed the display name
                me = ModeVoteEntry.createDefault();
                me.displayName = val;
                s.modes.put(e.getKey(), me);
            }
        }
        for (var e : mapNameFields.entrySet()) {
            String val = e.getValue().getValue() != null ? e.getValue().getValue().trim() : "";
            MapVoteEntry me = s.maps.get(e.getKey());
            if (me != null) {
                me.displayName = val;
            } else if (!val.isEmpty() && !val.equals(e.getKey())) {
                me = MapVoteEntry.createDefault();
                me.displayName = val;
                s.maps.put(e.getKey(), me);
            }
        }
    }

    private void ensureModeEntry(String id) {
        settings().modes.computeIfAbsent(id, k -> ModeVoteEntry.createDefault());
    }

    private void persist() {
        ModeMapVoteSettings s = settings();
        ConfigManager.getInstance().setModeMapVoteSettings(s);
    }
}
