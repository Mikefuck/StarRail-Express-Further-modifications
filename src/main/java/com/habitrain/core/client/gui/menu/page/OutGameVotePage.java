package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.MapVoteProfileScreen;
import com.habitrain.core.client.gui.menu.ModeAllowedMapsScreen;
import com.habitrain.core.client.gui.menu.ui.SubTabBar;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapPlayerCountSettings;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.config.ModeVoteEntry;
import com.habitrain.core.config.SREIntegration;
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
import java.util.Set;

/**
 * 游戏外·投票：内层子 Tab 拆分旧 VoteTabScreen。
 * 主设置 / 按人数抽图 / 可投票模式 / 可投票地图。
 */
public class OutGameVotePage implements ConfigPage {

    private static final String[] SUB_LABELS = {"主设置", "按人数抽图", "可投票模式", "可投票地图"};

    private static final int PAD = 12;
    private static final int ROW_H = 24;
    private static final int HEADER_H = 18;
    private static final int SECTION_GAP = 8;
    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 120;
    private static final int ACCENT = MenuTheme.ACCENT_BLUE;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private int innerTab = 0;
    private int subHitThisFrame = -1;
    private final SubTabBar subTabBar = new SubTabBar(SUB_LABELS, ACCENT);

    private boolean widgetsInitialized = false;
    private EditBox modeDurationField;
    private EditBox mapDurationField;
    private EditBox drawCountField;
    private final Map<String, EditBox> mapMinFields = new LinkedHashMap<>();
    private final Map<String, EditBox> mapMaxFields = new LinkedHashMap<>();

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
     * Outside of a world (title screen), mapIds is kept empty so no stale maps are shown.
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

        mapIds.clear();
        if (isInWorld()) {
            Set<String> maps = new LinkedHashSet<>(s.maps.keySet());
            for (ModeVoteEntry me : s.modes.values()) {
                if (me.allowedMaps != null) maps.addAll(me.allowedMaps);
            }
            maps.removeIf(SREIntegration::isReservedMapId);
            mapIds.addAll(maps);
        }
    }

    private boolean isInWorld() {
        try {
            return Minecraft.getInstance().level != null;
        } catch (Throwable ignored) {
            return false;
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

        drawCountField = new EditBox(font, -10000, -10000, 40, 14, Component.literal(""));
        drawCountField.setMaxLength(2);
        drawCountField.setValue(String.valueOf(s.playerCountOrDefault().drawCount));
        drawCountField.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
        drawCountField.setEditable(editable);

        rebuildNameFields();
        rebuildPlayerCountFields();
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
                EditBox box = new EditBox(font, -10000, -10000, 120, 14, Component.literal(""));
                box.setMaxLength(64);
                String dn = resolveMapDisplay(id);
                box.setValue(dn);
                box.setEditable(editable);
                mapNameFields.put(id, box);
            }
        }
    }

    /** Create per-map min/max player EditBoxes for the 按人数抽图 tab (missing ids only). */
    private void rebuildPlayerCountFields() {
        ModeMapVoteSettings s = settings();
        for (String id : mapIds) {
            if (!mapMinFields.containsKey(id)) {
                MapVoteEntry e = s.maps.get(id);
                EditBox box = new EditBox(font, -10000, -10000, 40, 14, Component.literal(""));
                box.setMaxLength(3);
                box.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
                box.setValue(e != null && e.minPlayers > 0 ? String.valueOf(e.minPlayers) : "0");
                box.setEditable(editable);
                mapMinFields.put(id, box);
            }
            if (!mapMaxFields.containsKey(id)) {
                MapVoteEntry e = s.maps.get(id);
                EditBox box = new EditBox(font, -10000, -10000, 40, 14, Component.literal(""));
                box.setMaxLength(3);
                box.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
                box.setValue(e != null && e.maxPlayers > 0 ? String.valueOf(e.maxPlayers) : "0");
                box.setEditable(editable);
                mapMaxFields.put(id, box);
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

    private String resolveMapDisplay(String id) {
        ModeMapVoteSettings s = settings();
        MapVoteEntry e = s.maps.get(id);
        if (e != null && e.displayName != null && !e.displayName.isBlank()) {
            return e.displayName;
        }
        String sreName = SREIntegration.getMapDisplayName(id);
        if (sreName != null && !sreName.isBlank()) {
            return sreName;
        }
        return id;
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
        rebuildPlayerCountFields();

        // 命中列表每帧重建；不清空会在滚动后把旧坐标的行当作当前命中（错关其他选项）。
        modeHits.clear();
        mapHits.clear();
        buttonHits.clear();

        subHitThisFrame = subTabBar.render(g, font, x, y, w, innerTab, mx, my);
        int contentY = y + SubTabBar.H + 4;
        int contentH = h - SubTabBar.H - 4;
        switch (innerTab) {
            case 0 -> renderMain(g, mx, my, delta, x, contentY, w, contentH);
            case 1 -> renderPlayerCountDraw(g, mx, my, delta, x, contentY, w, contentH);
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
        g.drawString(font, "模式 / 地图投票", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H;
        ModeMapVoteSettings s = settings();
        int toggleW = 70;
        int toggleX = labelX;
        g.fill(toggleX, cy, toggleX + toggleW, cy + 16, s.enabled ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        g.drawString(font, s.enabled ? "§a已启用" : "§c已停用", toggleX + 6, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("toggle_enabled", toggleX, cy, toggleW, 16));
        cy += ROW_H + 4;

        // ===== 时长 =====
        g.drawString(font, "投票时长 / 秒（5–120）", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
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

    private void renderPlayerCountDraw(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int listTop = y;
        int listH = h;
        listHeight = listH;
        g.enableScissor(x, listTop, x + w, listTop + listH);

        int cy = listTop + 6 - (int) scroll[1];
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        ModeMapVoteSettings s = settings();
        MapPlayerCountSettings pc = s.playerCountOrDefault();

        // ===== 按人数抽图 =====
        g.fill(labelX - 2, cy - 2, labelX + innerW + 2, cy - 1, MenuTheme.SEPARATOR);
        cy += 4;
        g.drawString(font, "按人数抽图", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H;

        // row: enable draw + draw count
        int rToggleW = 70;
        g.fill(labelX, cy, labelX + rToggleW, cy + 16,
                pc.enabled ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        g.drawString(font, pc.enabled ? "§a抽图开" : "§c抽图关", labelX + 6, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("pc_toggle", labelX, cy, rToggleW, 16));

        g.drawString(font, "抽取数量:", labelX + rToggleW + 10, cy + 4, MenuTheme.TEXT_SECONDARY, false);
        drawCountField.setX(labelX + rToggleW + 10 + 56);
        drawCountField.setY(cy);
        drawCountField.setWidth(40);
        drawCountField.render(g, mx, my, delta);
        g.drawString(font, "§7(1–" + MapPlayerCountSettings.MAX_DRAW_COUNT + ")",
                labelX + rToggleW + 10 + 100, cy + 4, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H + 2;

        g.drawString(font, "§7根据当前对局人数抽取地图；不足时补抽其他地图并标注“玩家人数不建议选择此地图”",
                labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H + SECTION_GAP;

        // ===== 每张地图的推荐人数 =====
        g.drawString(font, "§e每张地图的推荐人数（0 = 不限制）", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H;
        if (mapIds.isEmpty()) {
            g.drawString(font, isInWorld() ? "§7暂无地图条目（启动投票或注册地图后出现）" : "§7暂无地图条目（需在世界中配置）", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            for (String id : mapIds) {
                boolean hover = MenuTheme.inBounds(mx, my, labelX, cy, innerW, ROW_H);
                g.fill(labelX, cy, labelX + innerW, cy + ROW_H - 2,
                        hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
                MenuTheme.drawAccentStripe(g, labelX, cy, ROW_H - 2, MenuTheme.accentFor(id));

                String shortId = id.length() > 14 ? id.substring(0, 12) + "…" : id;
                g.drawString(font, "§7" + shortId, labelX + 6, cy + 6, MenuTheme.TEXT_SECONDARY, false);

                int minX = labelX + innerW - 150;
                int maxX = labelX + innerW - 80;
                g.drawString(font, "§7最小", minX - 26, cy + 6, MenuTheme.TEXT_SECONDARY, false);
                EditBox minBox = mapMinFields.get(id);
                if (minBox != null) {
                    minBox.setX(minX);
                    minBox.setY(cy + 4);
                    minBox.setWidth(40);
                    minBox.render(g, mx, my, delta);
                }
                g.drawString(font, "§7最大", maxX - 26, cy + 6, MenuTheme.TEXT_SECONDARY, false);
                EditBox maxBox = mapMaxFields.get(id);
                if (maxBox != null) {
                    maxBox.setX(maxX);
                    maxBox.setY(cy + 4);
                    maxBox.setWidth(40);
                    maxBox.render(g, mx, my, delta);
                }
                cy += ROW_H;
            }
        }

        cy += SECTION_GAP;
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
        g.drawString(font, "可投票模式", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H;
        if (modeIds.isEmpty()) {
            g.drawString(font, "§7暂无已注册的模式", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
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
                int mapsBtnW = 72;
                int mapsBtnX = labelX + innerW - mapsBtnW - 6;
                if (nameBox != null) {
                    nameBox.setX(labelX + 180);
                    nameBox.setY(cy + 4);
                    nameBox.setWidth(Math.max(40, mapsBtnX - nameBox.getX() - 6));
                    nameBox.render(g, mx, my, delta);
                }

                boolean mapsBtnHover = MenuTheme.inBounds(mx, my, mapsBtnX, cy + 3, mapsBtnW, 16);
                MenuTheme.button(g, font, "可选地图…", mapsBtnX, cy + 3, mapsBtnW, 16,
                        ACCENT, editable, mapsBtnHover);

                int orderW = 18;
                int orderX = mapsBtnX - orderW - 4;
                boolean upHover = MenuTheme.inBounds(mx, my, orderX, cy + 3, orderW, 16);
                MenuTheme.button(g, font, "▲", orderX, cy + 3, orderW, 16,
                        ACCENT, editable, upHover);
                buttonHits.add(new ButtonHit("mode_up:" + id, orderX, cy + 3, orderW, 16));

                int downX = orderX - orderW - 2;
                boolean downHover = MenuTheme.inBounds(mx, my, downX, cy + 3, orderW, 16);
                MenuTheme.button(g, font, "▼", downX, cy + 3, orderW, 16,
                        ACCENT, editable, downHover);
                buttonHits.add(new ButtonHit("mode_down:" + id, downX, cy + 3, orderW, 16));

                modeHits.add(new RowHit(id, tX, tW, mapsBtnX, mapsBtnW, cy, ROW_H - 2, true));
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
        g.drawString(font, "可投票地图", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H;
        if (mapIds.isEmpty()) {
            g.drawString(font, isInWorld() ? "§7暂无地图条目（服务端启动扫描后出现）" : "§7暂无地图条目（需在世界中配置）", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
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

                String shortId = id.length() > 20 ? id.substring(0, 18) + "…" : id;
                g.drawString(font, "§7" + shortId, tX + tW + 6, cy + 6, MenuTheme.TEXT_SECONDARY, false);

                int profileW = 60;
                int profileX = labelX + innerW - profileW - 4;

                EditBox nameBox = mapNameFields.get(id);
                if (nameBox != null) {
                    nameBox.setX(labelX + 160);
                    nameBox.setY(cy + 4);
                    nameBox.setWidth(Math.max(30, profileX - nameBox.getX() - 6));
                    nameBox.render(g, mx, my, delta);
                }

                boolean profileHover = MenuTheme.inBounds(mx, my, profileX, cy + 3, profileW, 16);
                MenuTheme.button(g, font,
                        Component.translatable("config.habitrain_core.map_profile.open").getString(),
                        profileX, cy + 3, profileW, 16,
                        ACCENT, editable, profileHover);
                buttonHits.add(new ButtonHit("map_profile:" + id,
                        profileX, cy + 3, profileW, 16));

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
            if (subHitThisFrame >= 0) MenuSounds.playClick();
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
            case 1 -> { return clickPlayerCountDraw(mx, my, btn); }
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
                    MenuSounds.playClick();
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

    private boolean clickPlayerCountDraw(double mx, double my, int btn) {
        if (tryFocusEditBox(drawCountField, mx, my)) return true;
        for (EditBox box : mapMinFields.values()) {
            if (tryFocusEditBox(box, mx, my)) return true;
        }
        for (EditBox box : mapMaxFields.values()) {
            if (tryFocusEditBox(box, mx, my)) return true;
        }
        for (ButtonHit hit : buttonHits) {
            if (!MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            MenuSounds.playClick();
            switch (hit.action()) {
                case "pc_toggle" -> {
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    MapPlayerCountSettings pc = settings().playerCountOrDefault();
                    pc.enabled = !pc.enabled;
                    persist();
                    ConfigManager.getInstance().save();
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
                MenuSounds.playClick();
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
                MenuSounds.playClick();
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
                MenuSounds.playClick();
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                // User edit: create entry only now
                ModeVoteEntry e = settings().modes.computeIfAbsent(hit.id(), k -> ModeVoteEntry.createDefault());
                e.enabled = !e.enabled;
                persist();
                return true;
            }
            if (hit.mapsW() > 0 && mx >= hit.mapsX() && mx < hit.mapsX() + hit.mapsW()) {
                MenuSounds.playClick();
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
        for (ButtonHit hit : buttonHits) {
            if (!MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            if (hit.action() != null && hit.action().startsWith("map_profile:")) {
                MenuSounds.playClick();
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                flushPending();
                String id = hit.action().substring("map_profile:".length());
                Minecraft.getInstance().setScreen(new MapVoteProfileScreen(root, id));
                return true;
            }
        }
        for (RowHit hit : mapHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                MenuSounds.playClick();
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                MapVoteEntry e = settings().maps.computeIfAbsent(hit.id(), k -> {
                    MapVoteEntry created = MapVoteEntry.createDefault();
                    String dn = resolveMapDisplay(hit.id());
                    if (!dn.equals(hit.id())) created.displayName = dn;
                    return created;
                });
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
        if (drawCountField != null) drawCountField.setFocused(false);
        for (EditBox box : modeNameFields.values()) {
            box.setFocused(false);
        }
        for (EditBox box : mapNameFields.values()) {
            box.setFocused(false);
        }
        for (EditBox box : mapMinFields.values()) {
            box.setFocused(false);
        }
        for (EditBox box : mapMaxFields.values()) {
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
        if (drawCountField != null && drawCountField.isFocused() && drawCountField.keyPressed(key, scan, mod)) return true;
        for (EditBox box : modeNameFields.values()) {
            if (box.isFocused() && box.keyPressed(key, scan, mod)) return true;
        }
        for (EditBox box : mapNameFields.values()) {
            if (box.isFocused() && box.keyPressed(key, scan, mod)) return true;
        }
        for (EditBox box : mapMinFields.values()) {
            if (box.isFocused() && box.keyPressed(key, scan, mod)) return true;
        }
        for (EditBox box : mapMaxFields.values()) {
            if (box.isFocused() && box.keyPressed(key, scan, mod)) return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (modeDurationField != null && modeDurationField.isFocused() && modeDurationField.charTyped(ch, mod)) return true;
        if (mapDurationField != null && mapDurationField.isFocused() && mapDurationField.charTyped(ch, mod)) return true;
        if (drawCountField != null && drawCountField.isFocused() && drawCountField.charTyped(ch, mod)) return true;
        for (EditBox box : modeNameFields.values()) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        for (EditBox box : mapNameFields.values()) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        for (EditBox box : mapMinFields.values()) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        for (EditBox box : mapMaxFields.values()) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        return false;
    }

    private int clampDuration(int v) {
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, v));
    }

    /** Parse a player-count edit field; invalid/blank falls back to {@code fallback}. */
    private int parseCount(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 0) return 0;
            return Math.min(v, 99);
        } catch (NumberFormatException e) {
            return fallback;
        }
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

        MapPlayerCountSettings pc = s.playerCountOrDefault();
        try {
            String v = drawCountField.getValue().trim();
            if (!v.isEmpty()) pc.drawCount = pc.clampDrawCount(Integer.parseInt(v));
        } catch (NumberFormatException ignored) {}
        drawCountField.setValue(String.valueOf(pc.drawCount));

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
        // Per-map min/max player counts; only create entries when a value actually changed.
        for (String id : mapIds) {
            EditBox minBox = mapMinFields.get(id);
            if (minBox != null) {
                MapVoteEntry cur = s.maps.get(id);
                int current = cur != null ? cur.minPlayers : 0;
                int val = parseCount(minBox.getValue(), current);
                minBox.setValue(String.valueOf(val));
                if (val != current) {
                    MapVoteEntry me = s.maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
                    me.minPlayers = val;
                }
            }
            EditBox maxBox = mapMaxFields.get(id);
            if (maxBox != null) {
                MapVoteEntry cur = s.maps.get(id);
                int current = cur != null ? cur.maxPlayers : 0;
                int val = parseCount(maxBox.getValue(), current);
                maxBox.setValue(String.valueOf(val));
                if (val != current) {
                    MapVoteEntry me = s.maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
                    me.maxPlayers = val;
                }
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

    /** Refresh fields changed by the dedicated map introduction editor. */
    public void refreshMapEntry(String id) {
        MapVoteEntry entry = settings().maps.get(id);
        EditBox name = mapNameFields.get(id);
        if (name != null) {
            name.setValue(entry != null && entry.displayName != null && !entry.displayName.isBlank()
                    ? entry.displayName : resolveMapDisplay(id));
        }
        EditBox min = mapMinFields.get(id);
        if (min != null) min.setValue(String.valueOf(entry != null ? entry.minPlayers : 0));
        EditBox max = mapMaxFields.get(id);
        if (max != null) max.setValue(String.valueOf(entry != null ? entry.maxPlayers : 0));
    }
}
