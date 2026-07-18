package com.habitrain.core.client.gui.config;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.EnvProfile;
import com.habitrain.core.config.EnvTimeSpec;
import com.habitrain.core.config.EnvironmentSettings;
import com.habitrain.core.config.PostMatchTimeRule;
import com.habitrain.core.game.sre.SREModeStartAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "环境设置" Tab — 大厅/对局环境、局后时间、动态雨。
 */
public class EnvironmentTabScreen {

    public static final int SUB_LOBBY = 0;
    public static final int SUB_MATCH = 1;
    public static final int SUB_POST = 2;
    public static final int SUB_RAIN = 3;

    private static final String[] SUB_LABELS = {"大厅环境", "对局环境", "局后时间", "动态雨"};
    private static final int ACCENT = 0xFF55C28A;
    private static final int PAD = 12;
    private static final int ROW_H = 22;
    private static final int HEADER_H = 16;
    private static final int SUB_TAB_H = 20;

    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;

    private int subTab = SUB_LOBBY;
    /** null => editing matchDefaultProfile; non-null => map id override */
    private String selectedMapId = null;

    private boolean widgetsInitialized = false;
    private EditBox profileTickField;
    private EditBox profileFogEndField;
    private EditBox goodTickField;
    private EditBox otherTickField;
    private EditBox minPlayersField;

    private double contentScroll = 0;
    private boolean draggingContent = false;
    private double dragStartY = 0;
    private double dragStartScroll = 0;
    private int contentHeight = 0;
    private int listHeight = 0;

    private final List<ButtonHit> buttonHits = new ArrayList<>();
    private final List<MapRowHit> mapHits = new ArrayList<>();

    private record ButtonHit(String action, int x, int y, int w, int h) {}
    private record MapRowHit(String mapId, int x, int y, int w, int h) {}

    public EnvironmentTabScreen(ConfigRootScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
    }

    private EnvironmentSettings settings() {
        return ConfigManager.getInstance().getEnvironmentSettings();
    }

    private void dirty() {
        ConfigManager.getInstance().markEnvironmentDirty();
    }

    private void playClick() {
        try {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        } catch (Throwable ignored) {}
    }

    private void saveNow() {
        dirty();
        try {
            ConfigManager.getInstance().save();
        } catch (Throwable ignored) {}
    }

    private void ensureWidgetsInitialized() {
        if (widgetsInitialized) return;
        widgetsInitialized = true;

        profileTickField = new EditBox(font, -10000, -10000, 56, 14, Component.literal(""));
        profileTickField.setMaxLength(5);
        profileTickField.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
        profileTickField.setEditable(editable);

        profileFogEndField = new EditBox(font, -10000, -10000, 56, 14, Component.literal(""));
        profileFogEndField.setMaxLength(8);
        profileFogEndField.setFilter(v -> v.isEmpty() || v.matches("\\d*\\.?\\d*"));
        profileFogEndField.setEditable(editable);

        goodTickField = new EditBox(font, -10000, -10000, 56, 14, Component.literal(""));
        goodTickField.setMaxLength(5);
        goodTickField.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
        goodTickField.setEditable(editable);

        otherTickField = new EditBox(font, -10000, -10000, 56, 14, Component.literal(""));
        otherTickField.setMaxLength(5);
        otherTickField.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
        otherTickField.setEditable(editable);

        minPlayersField = new EditBox(font, -10000, -10000, 48, 14, Component.literal(""));
        minPlayersField.setMaxLength(4);
        minPlayersField.setFilter(v -> v.isEmpty() || v.matches("\\d*"));
        minPlayersField.setEditable(editable);

        syncFieldsFromSettings(true);
    }

    private void syncFieldsFromSettings(boolean force) {
        EnvironmentSettings s = settings();
        EnvProfile profile = currentProfile();
        if (profile != null && profile.time != null) {
            if (force || profileTickField == null || !profileTickField.isFocused()) {
                profileTickField.setValue(String.valueOf(EnvTimeSpec.clampTick(profile.time.tick)));
            }
            if (force || profileFogEndField == null || !profileFogEndField.isFocused()) {
                float fe = profile.fogEnd;
                String fogStr = (fe == (int) fe) ? String.valueOf((int) fe) : String.valueOf(fe);
                profileFogEndField.setValue(fogStr);
            }
        }
        if (s.goodWin != null && s.goodWin.time != null) {
            if (force || !goodTickField.isFocused()) {
                goodTickField.setValue(String.valueOf(EnvTimeSpec.clampTick(s.goodWin.time.tick)));
            }
        }
        if (s.otherWin != null && s.otherWin.time != null) {
            if (force || !otherTickField.isFocused()) {
                otherTickField.setValue(String.valueOf(EnvTimeSpec.clampTick(s.otherWin.time.tick)));
            }
        }
        if (force || !minPlayersField.isFocused()) {
            minPlayersField.setValue(String.valueOf(s.clampedMinPlayers()));
        }
    }

    private EnvProfile currentProfile() {
        EnvironmentSettings s = settings();
        if (subTab == SUB_LOBBY) {
            if (s.lobby == null) s.lobby = EnvProfile.createLobbyDefault();
            return s.lobby;
        }
        if (subTab == SUB_MATCH) {
            if (selectedMapId == null) {
                if (s.matchDefaultProfile == null) s.matchDefaultProfile = EnvProfile.createMatchDefault();
                return s.matchDefaultProfile;
            }
            EnvProfile p = s.matchMaps.get(selectedMapId);
            if (p == null) {
                p = EnvProfile.createMatchDefault();
                s.matchMaps.put(selectedMapId, p);
                dirty();
            }
            return p;
        }
        return null;
    }

    private List<String> collectMapIds() {
        EnvironmentSettings s = settings();
        Set<String> ids = new LinkedHashSet<>();
        if (s.matchMaps != null) ids.addAll(s.matchMaps.keySet());
        try {
            var vote = ConfigManager.getInstance().getModeMapVoteSettings();
            if (vote != null && vote.maps != null) ids.addAll(vote.maps.keySet());
        } catch (Throwable ignored) {}
        try {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                ServerLevel overworld = server.overworld();
                if (overworld != null) {
                    ids.addAll(SREModeStartAdapter.getAvailableMaps(overworld));
                }
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(ids);
    }

    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        ensureWidgetsInitialized();
        buttonHits.clear();
        mapHits.clear();

        int listTop = y;
        int listH = h;
        listHeight = listH;
        g.enableScissor(x, listTop, x + w, listTop + listH);

        int cy = listTop + 6 - (int) contentScroll;
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        // ===== Sub-tabs =====
        int tabW = Math.max(60, (innerW - 6) / SUB_LABELS.length);
        for (int i = 0; i < SUB_LABELS.length; i++) {
            int tx = labelX + i * (tabW + 2);
            boolean sel = subTab == i;
            boolean hover = SharedGuiKit.inBounds(mx, my, tx, cy, tabW, SUB_TAB_H);
            g.fill(tx, cy, tx + tabW, cy + SUB_TAB_H,
                    sel ? ACCENT : (hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW));
            int textW = font.width(SUB_LABELS[i]);
            g.drawString(font, SUB_LABELS[i], tx + (tabW - textW) / 2, cy + 6,
                    sel ? 0xFF101410 : SharedGuiKit.TEXT_PRIMARY, false);
            buttonHits.add(new ButtonHit("sub:" + i, tx, cy, tabW, SUB_TAB_H));
        }
        cy += SUB_TAB_H + 8;
        g.fill(labelX, cy - 2, labelX + innerW, cy - 1, SharedGuiKit.SEPARATOR);

        switch (subTab) {
            case SUB_LOBBY -> cy = renderLobby(g, mx, my, delta, labelX, cy, innerW);
            case SUB_MATCH -> cy = renderMatch(g, mx, my, delta, labelX, cy, innerW);
            case SUB_POST -> cy = renderPost(g, mx, my, delta, labelX, cy, innerW);
            case SUB_RAIN -> cy = renderRain(g, mx, my, delta, labelX, cy, innerW);
            default -> {}
        }

        // Save button on every sub-page
        cy += 8;
        g.fill(labelX, cy - 2, labelX + innerW, cy - 1, SharedGuiKit.SEPARATOR);
        cy += 8;
        int saveW = 72;
        boolean saveHover = SharedGuiKit.inBounds(mx, my, labelX, cy, saveW, 18);
        g.fill(labelX, cy, labelX + saveW, cy + 18,
                saveHover ? 0xFF2A6B4A : 0xFF1B4A32);
        g.drawString(font, "§a保存", labelX + 22, cy + 5, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("save", labelX, cy, saveW, 18));
        g.drawString(font, "§7写入配置文件（关闭面板也会自动保存）",
                labelX + saveW + 10, cy + 5, SharedGuiKit.TEXT_SECONDARY, false);
        cy += 24;

        contentHeight = cy - listTop + (int) contentScroll + 12;
        int maxScroll = Math.max(0, contentHeight - listH);
        contentScroll = Mth.clamp(contentScroll, 0, maxScroll);
        SharedGuiKit.drawScrollbar(g, x + w - 4, listTop, listH, contentScroll, maxScroll, 3);
        g.disableScissor();

        if (!editable) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改"),
                    labelX, y + h - 14, 0xFF5555, false);
        }
    }

    private int renderLobby(GuiGraphics g, int mx, int my, float delta, int labelX, int cy, int innerW) {
        g.drawString(font, Component.literal("§e§l大厅环境配置"), labelX, cy, ACCENT, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7控制大厅（非对局）时段的时间、天气与雾效", labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += ROW_H;
        EnvProfile profile = currentProfile();
        return renderProfileEditor(g, mx, my, delta, labelX, cy, innerW, profile, "profile");
    }

    private int renderMatch(GuiGraphics g, int mx, int my, float delta, int labelX, int cy, int innerW) {
        g.drawString(font, Component.literal("§e§l对局环境配置"), labelX, cy, ACCENT, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7左侧选地图覆盖；无覆盖时使用默认配置", labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += ROW_H;

        int listW = Math.min(140, Math.max(100, innerW / 3));
        int editorX = labelX + listW + 10;
        int editorW = innerW - listW - 10;
        int listStartY = cy;

        // Left map list
        List<String> maps = collectMapIds();
        int rowY = listStartY;

        boolean defSel = selectedMapId == null;
        boolean defHover = SharedGuiKit.inBounds(mx, my, labelX, rowY, listW, ROW_H);
        g.fill(labelX, rowY, labelX + listW, rowY + ROW_H - 2,
                defSel ? SharedGuiKit.BG_ROW_SELECTED : (defHover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW));
        SharedGuiKit.drawAccentStripe(g, labelX, rowY, ROW_H - 2, ACCENT);
        g.drawString(font, "§e默认", labelX + 8, rowY + 6, SharedGuiKit.TEXT_PRIMARY, false);
        mapHits.add(new MapRowHit(null, labelX, rowY, listW, ROW_H - 2));
        rowY += ROW_H;

        for (String id : maps) {
            boolean sel = id.equals(selectedMapId);
            boolean hover = SharedGuiKit.inBounds(mx, my, labelX, rowY, listW, ROW_H);
            g.fill(labelX, rowY, labelX + listW, rowY + ROW_H - 2,
                    sel ? SharedGuiKit.BG_ROW_SELECTED : (hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW));
            SharedGuiKit.drawAccentStripe(g, labelX, rowY, ROW_H - 2, SharedGuiKit.accentFor(id));
            boolean hasOverride = settings().matchMaps.containsKey(id);
            String shortId = id.length() > 14 ? id.substring(0, 12) + "…" : id;
            g.drawString(font, (hasOverride ? "§a" : "§7") + shortId, labelX + 8, rowY + 6,
                    SharedGuiKit.TEXT_PRIMARY, false);
            mapHits.add(new MapRowHit(id, labelX, rowY, listW, ROW_H - 2));
            rowY += ROW_H;
        }

        if (maps.isEmpty()) {
            g.drawString(font, "§8无地图", labelX + 8, rowY + 4, SharedGuiKit.TEXT_SECONDARY, false);
            rowY += ROW_H;
        }

        // Right editor
        int editorCy = listStartY;
        String title = selectedMapId == null
                ? "§e默认 (defaultProfile)"
                : "§e地图: §f" + selectedMapId;
        g.drawString(font, Component.literal(title), editorX, editorCy, ACCENT, false);
        editorCy += HEADER_H + 2;

        if (selectedMapId != null) {
            int delW = 100;
            g.fill(editorX, editorCy, editorX + delW, editorCy + 16, 0xFF3A1B1B);
            g.drawString(font, "§c删除地图覆盖", editorX + 6, editorCy + 4, 0xFFFFFFFF, false);
            buttonHits.add(new ButtonHit("delete_map", editorX, editorCy, delW, 16));
            editorCy += ROW_H + 2;
        }

        EnvProfile profile = currentProfile();
        editorCy = renderProfileEditor(g, mx, my, delta, editorX, editorCy, editorW, profile, "profile");

        return Math.max(rowY, editorCy) + 4;
    }

    private int renderPost(GuiGraphics g, int mx, int my, float delta, int labelX, int cy, int innerW) {
        g.drawString(font, Component.literal("§e§l局后时间"), labelX, cy, ACCENT, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7好人 = isInnocentWin()；其余走杀手/中立", labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += ROW_H;

        EnvironmentSettings s = settings();
        if (s.goodWin == null) s.goodWin = PostMatchTimeRule.createDefault();
        if (s.otherWin == null) s.otherWin = PostMatchTimeRule.createDefault();
        if (s.goodWin.time == null) s.goodWin.time = EnvTimeSpec.createDefault();
        if (s.otherWin.time == null) s.otherWin.time = EnvTimeSpec.createDefault();

        g.drawString(font, Component.literal("§a§l好人胜利"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        cy = renderPostRule(g, mx, my, delta, labelX, cy, innerW, s.goodWin, "good", goodTickField);

        cy += 6;
        g.fill(labelX, cy, labelX + innerW, cy + 1, SharedGuiKit.SEPARATOR);
        cy += 8;

        g.drawString(font, Component.literal("§c§l杀手/中立等"), labelX, cy, ACCENT, false);
        cy += HEADER_H;
        cy = renderPostRule(g, mx, my, delta, labelX, cy, innerW, s.otherWin, "other", otherTickField);
        return cy;
    }

    private int renderPostRule(GuiGraphics g, int mx, int my, float delta,
                               int labelX, int cy, int innerW,
                               PostMatchTimeRule rule, String prefix, EditBox tickBox) {
        cy = drawToggle(g, labelX, cy, "启用", rule.enabled, prefix + ":enabled");
        cy = renderTimeSpecEditor(g, mx, my, delta, labelX, cy, innerW, rule.time, prefix, tickBox);
        return cy;
    }

    private int renderRain(GuiGraphics g, int mx, int my, float delta, int labelX, int cy, int innerW) {
        g.drawString(font, Component.literal("§e§l动态雨（低人数）"), labelX, cy, ACCENT, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7人数低于阈值时强制下雨（对局中）", labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += ROW_H;

        EnvironmentSettings s = settings();
        cy = drawToggle(g, labelX, cy, "启用低人数动态雨", s.lowPlayerRainEnabled, "rain:enabled");

        g.drawString(font, "最少玩家数:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
        if (!minPlayersField.isFocused()) {
            minPlayersField.setValue(String.valueOf(s.clampedMinPlayers()));
        }
        minPlayersField.setX(labelX + 80);
        minPlayersField.setY(cy);
        minPlayersField.setWidth(48);
        minPlayersField.render(g, mx, my, delta);

        int applyX = labelX + 136;
        g.fill(applyX, cy - 1, applyX + 40, cy + 15, SharedGuiKit.BG_EDIT);
        g.drawString(font, "应用", applyX + 8, cy + 3, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit("rain:apply_min", applyX, cy - 1, 40, 16));
        cy += ROW_H + 4;
        g.drawString(font, "§7当前生效阈值: " + s.clampedMinPlayers() + "（≥1）",
                labelX, cy, SharedGuiKit.TEXT_SECONDARY, false);
        cy += ROW_H;
        return cy;
    }

    private int renderProfileEditor(GuiGraphics g, int mx, int my, float delta,
                                    int labelX, int cy, int innerW,
                                    EnvProfile profile, String prefix) {
        if (profile == null) {
            g.drawString(font, "§c无配置对象", labelX, cy, 0xFFFF5555, false);
            return cy + ROW_H;
        }
        if (profile.time == null) profile.time = EnvTimeSpec.createDefault();
        if (profile.weather == null) profile.weather = EnvProfile.Weather.CLEAR;

        cy = drawToggle(g, labelX, cy, "启用环境覆盖", profile.enabled, prefix + ":enabled");

        // Time mode
        g.drawString(font, "时间模式:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
        int modeX = labelX + 60;
        int modeW = 70;
        boolean isPreset = profile.time.mode != EnvTimeSpec.Mode.TICK;
        g.fill(modeX, cy, modeX + modeW, cy + 16, SharedGuiKit.BG_EDIT);
        g.drawString(font, isPreset ? "PRESET" : "TICK", modeX + 12, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(prefix + ":time_mode", modeX, cy, modeW, 16));
        cy += ROW_H;

        if (isPreset) {
            if (profile.time.preset == null) profile.time.preset = EnvTimeSpec.Preset.DAY;
            g.drawString(font, "预设:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
            int preX = labelX + 40;
            int preW = 90;
            g.fill(preX, cy, preX + preW, cy + 16, SharedGuiKit.BG_EDIT);
            g.drawString(font, profile.time.preset.name(), preX + 8, cy + 4, 0xFFFFFFFF, false);
            buttonHits.add(new ButtonHit(prefix + ":preset", preX, cy, preW, 16));
            g.drawString(font, "§7tick=" + profile.time.preset.time, preX + preW + 8, cy + 4,
                    SharedGuiKit.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            g.drawString(font, "Tick(0-23999):", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
            if (!profileTickField.isFocused()) {
                String want = String.valueOf(EnvTimeSpec.clampTick(profile.time.tick));
                if (!want.equals(profileTickField.getValue())) {
                    profileTickField.setValue(want);
                }
            }
            profileTickField.setX(labelX + 90);
            profileTickField.setY(cy);
            profileTickField.setWidth(56);
            profileTickField.render(g, mx, my, delta);
            int applyX = labelX + 154;
            g.fill(applyX, cy - 1, applyX + 40, cy + 15, SharedGuiKit.BG_EDIT);
            g.drawString(font, "应用", applyX + 8, cy + 3, 0xFFFFFFFF, false);
            buttonHits.add(new ButtonHit(prefix + ":apply_tick", applyX, cy - 1, 40, 16));
            cy += ROW_H;
        }

        // Weather
        g.drawString(font, "天气:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
        int wx = labelX + 40;
        int ww = 80;
        g.fill(wx, cy, wx + ww, cy + 16, SharedGuiKit.BG_EDIT);
        g.drawString(font, profile.weather.name(), wx + 10, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(prefix + ":weather", wx, cy, ww, 16));
        cy += ROW_H;

        cy = drawToggle(g, labelX, cy, "雪", profile.snow, prefix + ":snow");
        cy = drawToggle(g, labelX, cy, "沙尘", profile.sand, prefix + ":sand");
        cy = drawToggle(g, labelX, cy, "雾", profile.fog, prefix + ":fog");

        g.drawString(font, "雾距离 fogEnd:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
        // Only push model→field when not focused, so typing is not wiped every frame.
        if (!profileFogEndField.isFocused()) {
            float fe = profile.fogEnd;
            String want = (fe == (int) fe) ? String.valueOf((int) fe) : String.valueOf(fe);
            if (!want.equals(profileFogEndField.getValue())) {
                profileFogEndField.setValue(want);
            }
        }
        profileFogEndField.setX(labelX + 100);
        profileFogEndField.setY(cy);
        profileFogEndField.setWidth(56);
        profileFogEndField.setEditable(editable);
        profileFogEndField.render(g, mx, my, delta);
        int fogApplyX = labelX + 164;
        g.fill(fogApplyX, cy - 1, fogApplyX + 40, cy + 15, SharedGuiKit.BG_EDIT);
        g.drawString(font, "应用", fogApplyX + 8, cy + 3, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(prefix + ":apply_fog", fogApplyX, cy - 1, 40, 16));
        cy += ROW_H;

        cy = drawToggle(g, labelX, cy, "日夜循环 daylightCycle", profile.daylightCycle, prefix + ":daylight");
        cy = drawToggle(g, labelX, cy, "天气循环 weatherCycle", profile.weatherCycle, prefix + ":weatherCycle");
        return cy;
    }

    private int renderTimeSpecEditor(GuiGraphics g, int mx, int my, float delta,
                                     int labelX, int cy, int innerW,
                                     EnvTimeSpec time, String prefix, EditBox tickBox) {
        if (time == null) return cy;
        g.drawString(font, "时间模式:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
        int modeX = labelX + 60;
        int modeW = 70;
        boolean isPreset = time.mode != EnvTimeSpec.Mode.TICK;
        g.fill(modeX, cy, modeX + modeW, cy + 16, SharedGuiKit.BG_EDIT);
        g.drawString(font, isPreset ? "PRESET" : "TICK", modeX + 12, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(prefix + ":time_mode", modeX, cy, modeW, 16));
        cy += ROW_H;

        if (isPreset) {
            if (time.preset == null) time.preset = EnvTimeSpec.Preset.DAY;
            g.drawString(font, "预设:", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
            int preX = labelX + 40;
            int preW = 90;
            g.fill(preX, cy, preX + preW, cy + 16, SharedGuiKit.BG_EDIT);
            g.drawString(font, time.preset.name(), preX + 8, cy + 4, 0xFFFFFFFF, false);
            buttonHits.add(new ButtonHit(prefix + ":preset", preX, cy, preW, 16));
            g.drawString(font, "§7tick=" + time.preset.time, preX + preW + 8, cy + 4,
                    SharedGuiKit.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            g.drawString(font, "Tick(0-23999):", labelX, cy + 2, SharedGuiKit.TEXT_SECONDARY, false);
            if (!tickBox.isFocused()) {
                tickBox.setValue(String.valueOf(EnvTimeSpec.clampTick(time.tick)));
            }
            tickBox.setX(labelX + 90);
            tickBox.setY(cy);
            tickBox.setWidth(56);
            tickBox.render(g, mx, my, delta);
            int applyX = labelX + 154;
            g.fill(applyX, cy - 1, applyX + 40, cy + 15, SharedGuiKit.BG_EDIT);
            g.drawString(font, "应用", applyX + 8, cy + 3, 0xFFFFFFFF, false);
            buttonHits.add(new ButtonHit(prefix + ":apply_tick", applyX, cy - 1, 40, 16));
            cy += ROW_H;
        }
        return cy;
    }

    private int drawToggle(GuiGraphics g, int labelX, int cy, String label, boolean on, String action) {
        int tw = 50;
        g.fill(labelX, cy, labelX + tw, cy + 16, on ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
        g.drawString(font, on ? "§a开" : "§c关", labelX + 16, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(action, labelX, cy, tw, 16));
        g.drawString(font, label, labelX + tw + 8, cy + 4, SharedGuiKit.TEXT_PRIMARY, false);
        return cy + ROW_H;
    }

    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        ensureWidgetsInitialized();

        // EditBoxes first (manual bounds so focus works even when widgets sit under scissor)
        if (tryFocusEditBox(profileTickField, mx, my, subTab == SUB_LOBBY || subTab == SUB_MATCH)) return true;
        if (tryFocusEditBox(profileFogEndField, mx, my, subTab == SUB_LOBBY || subTab == SUB_MATCH)) return true;
        if (tryFocusEditBox(goodTickField, mx, my, subTab == SUB_POST)) return true;
        if (tryFocusEditBox(otherTickField, mx, my, subTab == SUB_POST)) return true;
        if (tryFocusEditBox(minPlayersField, mx, my, subTab == SUB_RAIN)) return true;

        // Map list selection
        for (MapRowHit hit : mapHits) {
            if (SharedGuiKit.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                playClick();
                selectedMapId = hit.mapId();
                if (selectedMapId != null) {
                    EnvironmentSettings s = settings();
                    if (!s.matchMaps.containsKey(selectedMapId)) {
                        if (!editable) {
                            LiveConfigAccess.showDeniedMessage();
                            selectedMapId = null;
                            return true;
                        }
                        s.matchMaps.put(selectedMapId, EnvProfile.createMatchDefault());
                        dirty();
                    }
                }
                // Refresh profile fields for new selection
                EnvProfile p = currentProfile();
                if (p != null && p.time != null) {
                    profileTickField.setValue(String.valueOf(EnvTimeSpec.clampTick(p.time.tick)));
                    float fe = p.fogEnd;
                    profileFogEndField.setValue((fe == (int) fe) ? String.valueOf((int) fe) : String.valueOf(fe));
                }
                unfocusAll();
                return true;
            }
        }

        for (ButtonHit hit : buttonHits) {
            if (!SharedGuiKit.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            playClick();
            return handleAction(hit.action());
        }

        // Click empty content: unfocus edit boxes, allow drag
        unfocusAll();
        if (my >= y && my < y + h) {
            draggingContent = true;
            dragStartY = my;
            dragStartScroll = contentScroll;
            return true;
        }
        return false;
    }

    private boolean handleAction(String action) {
        if (action.startsWith("sub:")) {
            int idx = Integer.parseInt(action.substring(4));
            if (idx != subTab) {
                subTab = idx;
                contentScroll = 0;
                unfocusAll();
                syncFieldsFromSettings(true);
            }
            return true;
        }

        if ("save".equals(action)) {
            if (!editable) {
                LiveConfigAccess.showDeniedMessage();
                return true;
            }
            // Flush focused numeric fields before save
            flushFocusedFields();
            saveNow();
            return true;
        }

        // All other actions require edit permission
        if (!editable) {
            LiveConfigAccess.showDeniedMessage();
            return true;
        }

        if ("delete_map".equals(action)) {
            if (selectedMapId != null) {
                settings().matchMaps.remove(selectedMapId);
                dirty();
                selectedMapId = null;
                syncFieldsFromSettings(true);
            }
            return true;
        }

        if ("rain:enabled".equals(action)) {
            settings().lowPlayerRainEnabled = !settings().lowPlayerRainEnabled;
            dirty();
            return true;
        }
        if ("rain:apply_min".equals(action)) {
            try {
                int v = Integer.parseInt(minPlayersField.getValue().trim());
                settings().lowPlayerRainMinPlayers = Math.max(1, v);
                minPlayersField.setValue(String.valueOf(settings().clampedMinPlayers()));
                dirty();
            } catch (NumberFormatException ignored) {}
            return true;
        }

        // Post-match good/other
        if (action.startsWith("good:") || action.startsWith("other:")) {
            boolean good = action.startsWith("good:");
            String op = action.substring(action.indexOf(':') + 1);
            EnvironmentSettings s = settings();
            PostMatchTimeRule rule = good ? s.goodWin : s.otherWin;
            if (rule == null) {
                rule = PostMatchTimeRule.createDefault();
                if (good) s.goodWin = rule; else s.otherWin = rule;
            }
            if (rule.time == null) rule.time = EnvTimeSpec.createDefault();
            EditBox tickBox = good ? goodTickField : otherTickField;
            applyTimeOrToggle(rule, null, op, tickBox, true);
            return true;
        }

        // Profile editor actions (lobby/match)
        if (action.startsWith("profile:")) {
            String op = action.substring("profile:".length());
            EnvProfile profile = currentProfile();
            if (profile == null) return true;
            if (profile.time == null) profile.time = EnvTimeSpec.createDefault();
            applyTimeOrToggle(null, profile, op, profileTickField, false);
            return true;
        }

        return true;
    }

    private void applyTimeOrToggle(PostMatchTimeRule rule, EnvProfile profile, String op,
                                   EditBox tickBox, boolean postRule) {
        EnvTimeSpec time = postRule
                ? (rule.time != null ? rule.time : (rule.time = EnvTimeSpec.createDefault()))
                : (profile.time != null ? profile.time : (profile.time = EnvTimeSpec.createDefault()));

        switch (op) {
            case "enabled" -> {
                if (postRule) rule.enabled = !rule.enabled;
                else profile.enabled = !profile.enabled;
                dirty();
            }
            case "time_mode" -> {
                time.mode = (time.mode == EnvTimeSpec.Mode.TICK)
                        ? EnvTimeSpec.Mode.PRESET : EnvTimeSpec.Mode.TICK;
                dirty();
            }
            case "preset" -> {
                EnvTimeSpec.Preset[] all = EnvTimeSpec.Preset.values();
                int idx = 0;
                if (time.preset != null) {
                    for (int i = 0; i < all.length; i++) {
                        if (all[i] == time.preset) { idx = i; break; }
                    }
                }
                time.preset = all[(idx + 1) % all.length];
                time.mode = EnvTimeSpec.Mode.PRESET;
                dirty();
            }
            case "apply_tick" -> {
                try {
                    int v = Integer.parseInt(tickBox.getValue().trim());
                    time.tick = EnvTimeSpec.clampTick(v);
                    time.mode = EnvTimeSpec.Mode.TICK;
                    tickBox.setValue(String.valueOf(time.tick));
                    dirty();
                } catch (NumberFormatException ignored) {}
            }
            case "weather" -> {
                if (profile == null) return;
                EnvProfile.Weather[] all = EnvProfile.Weather.values();
                int idx = 0;
                if (profile.weather != null) {
                    for (int i = 0; i < all.length; i++) {
                        if (all[i] == profile.weather) { idx = i; break; }
                    }
                }
                profile.weather = all[(idx + 1) % all.length];
                dirty();
            }
            case "snow" -> { if (profile != null) { profile.snow = !profile.snow; dirty(); } }
            case "sand" -> { if (profile != null) { profile.sand = !profile.sand; dirty(); } }
            case "fog" -> { if (profile != null) { profile.fog = !profile.fog; dirty(); } }
            case "apply_fog" -> {
                if (profile == null) return;
                try {
                    float v = Float.parseFloat(profileFogEndField.getValue().trim());
                    if (v < 0) v = 0;
                    profile.fogEnd = v;
                    String fogStr = (v == (int) v) ? String.valueOf((int) v) : String.valueOf(v);
                    profileFogEndField.setValue(fogStr);
                    dirty();
                } catch (NumberFormatException ignored) {}
            }
            case "daylight" -> { if (profile != null) { profile.daylightCycle = !profile.daylightCycle; dirty(); } }
            case "weatherCycle" -> { if (profile != null) { profile.weatherCycle = !profile.weatherCycle; dirty(); } }
            default -> {}
        }
    }

    private boolean tryFocusEditBox(EditBox box, double mx, double my, boolean activeTab) {
        if (!activeTab || box == null) return false;
        int bx = box.getX();
        int by = box.getY();
        int bw = box.getWidth();
        int bh = box.getHeight();
        // Ignore parked widgets still at sentinel coords
        if (bx < -1000 || by < -1000) return false;
        if (mx < bx || mx >= bx + bw || my < by || my >= by + bh) return false;
        if (!editable) {
            LiveConfigAccess.showDeniedMessage();
            return true;
        }
        unfocusAll();
        box.setFocused(true);
        box.setEditable(true);
        box.mouseClicked(mx, my, 0);
        playClick();
        return true;
    }

    private void flushFocusedFields() {
        if (profileFogEndField != null && profileFogEndField.isFocused()) {
            EnvProfile profile = currentProfile();
            if (profile != null) {
                try {
                    float v = Float.parseFloat(profileFogEndField.getValue().trim());
                    if (v < 0) v = 0;
                    profile.fogEnd = v;
                    dirty();
                } catch (NumberFormatException ignored) {}
            }
        }
        if (profileTickField != null && profileTickField.isFocused()) {
            EnvProfile profile = currentProfile();
            if (profile != null) {
                if (profile.time == null) profile.time = EnvTimeSpec.createDefault();
                try {
                    int v = Integer.parseInt(profileTickField.getValue().trim());
                    profile.time.tick = EnvTimeSpec.clampTick(v);
                    profile.time.mode = EnvTimeSpec.Mode.TICK;
                    dirty();
                } catch (NumberFormatException ignored) {}
            }
        }
        if (goodTickField != null && goodTickField.isFocused()) {
            EnvironmentSettings s = settings();
            if (s.goodWin == null) s.goodWin = PostMatchTimeRule.createDefault();
            if (s.goodWin.time == null) s.goodWin.time = EnvTimeSpec.createDefault();
            try {
                int v = Integer.parseInt(goodTickField.getValue().trim());
                s.goodWin.time.tick = EnvTimeSpec.clampTick(v);
                s.goodWin.time.mode = EnvTimeSpec.Mode.TICK;
                dirty();
            } catch (NumberFormatException ignored) {}
        }
        if (otherTickField != null && otherTickField.isFocused()) {
            EnvironmentSettings s = settings();
            if (s.otherWin == null) s.otherWin = PostMatchTimeRule.createDefault();
            if (s.otherWin.time == null) s.otherWin.time = EnvTimeSpec.createDefault();
            try {
                int v = Integer.parseInt(otherTickField.getValue().trim());
                s.otherWin.time.tick = EnvTimeSpec.clampTick(v);
                s.otherWin.time.mode = EnvTimeSpec.Mode.TICK;
                dirty();
            } catch (NumberFormatException ignored) {}
        }
        if (minPlayersField != null && minPlayersField.isFocused()) {
            try {
                int v = Integer.parseInt(minPlayersField.getValue().trim());
                settings().lowPlayerRainMinPlayers = Math.max(1, v);
                dirty();
            } catch (NumberFormatException ignored) {}
        }
    }

    private void unfocusAll() {
        if (profileTickField != null) profileTickField.setFocused(false);
        if (profileFogEndField != null) profileFogEndField.setFocused(false);
        if (goodTickField != null) goodTickField.setFocused(false);
        if (otherTickField != null) otherTickField.setFocused(false);
        if (minPlayersField != null) minPlayersField.setFocused(false);
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (draggingContent) {
            int maxScroll = Math.max(0, contentHeight - listHeight);
            contentScroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, maxScroll);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        if (draggingContent) {
            draggingContent = false;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        int maxScroll = Math.max(0, contentHeight - listHeight);
        contentScroll = Mth.clamp(contentScroll - sy * 18, 0, maxScroll);
        return true;
    }

    public boolean keyPressed(int key, int scan, int mod) {
        if (profileTickField != null && profileTickField.isFocused() && profileTickField.keyPressed(key, scan, mod)) return true;
        if (profileFogEndField != null && profileFogEndField.isFocused() && profileFogEndField.keyPressed(key, scan, mod)) return true;
        if (goodTickField != null && goodTickField.isFocused() && goodTickField.keyPressed(key, scan, mod)) return true;
        if (otherTickField != null && otherTickField.isFocused() && otherTickField.keyPressed(key, scan, mod)) return true;
        if (minPlayersField != null && minPlayersField.isFocused() && minPlayersField.keyPressed(key, scan, mod)) return true;
        return false;
    }

    public boolean charTyped(char ch, int mod) {
        if (profileTickField != null && profileTickField.isFocused() && profileTickField.charTyped(ch, mod)) return true;
        if (profileFogEndField != null && profileFogEndField.isFocused() && profileFogEndField.charTyped(ch, mod)) return true;
        if (goodTickField != null && goodTickField.isFocused() && goodTickField.charTyped(ch, mod)) return true;
        if (otherTickField != null && otherTickField.isFocused() && otherTickField.charTyped(ch, mod)) return true;
        if (minPlayersField != null && minPlayersField.isFocused() && minPlayersField.charTyped(ch, mod)) return true;
        return false;
    }
}
