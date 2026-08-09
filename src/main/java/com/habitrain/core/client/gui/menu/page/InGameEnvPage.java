package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.client.gui.menu.ui.ScrollArea;
import com.habitrain.core.client.gui.menu.ui.SubTabBar;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 游戏内·环境：内层子 Tab 拆分旧 EnvironmentTabScreen 的对局/局后/雨（大厅归属 OutGameLobbyEnvPage）。
 * 对局环境 / 局后时间 / 动态雨。
 * 共用的 profile 编辑器与动作分发复用 {@link EnvEditorShared}。
 */
public class InGameEnvPage implements ConfigPage {

    public static final int SUB_MATCH = 0;
    public static final int SUB_POST = 1;
    public static final int SUB_RAIN = 2;

    private static final String[] SUB_LABELS = {"对局环境", "局后时间", "动态雨"};
    private static final int ACCENT = MenuTheme.ACCENT_MINT;
    private static final int PAD = 12;
    private static final int ROW_H = 22;
    private static final int HEADER_H = 16;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private int subTab = SUB_MATCH;
    /** null => 编辑 matchDefaultProfile；非 null => 地图覆盖 id */
    private String selectedMapId = null;
    private int subHitThisFrame = -1;

    private boolean widgetsInitialized = false;
    private EditBox profileTickField;
    private EditBox profileFogEndField;
    private EditBox goodTickField;
    private EditBox otherTickField;
    private EditBox minPlayersField;

    private ScrollArea area;
    private final SubTabBar subTabBar = new SubTabBar(SUB_LABELS, ACCENT);

    private final List<EnvEditorShared.ButtonHit> buttonHits = new ArrayList<>();
    private final List<MapRowHit> mapHits = new ArrayList<>();

    private record MapRowHit(String mapId, int x, int y, int w, int h) {}

    public InGameEnvPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        this.area = new ScrollArea(0, 0, 0, 0); // 坐标在 render 里设定
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

    // ---------------- ConfigPage 契约 ----------------

    @Override public boolean canSave() { return true; }

    /** 所有改动即时生效；写盘统一由根 SaveBar 处理，这里只标记环境脏。 */
    @Override public void save() { ConfigManager.getInstance().markEnvironmentDirty(); }

    /** 提交聚焦的 profile tick/fog 文本框到当前 profile（保存/切页/关闭前调用）。 */
    @Override public void flushPending() {
        EnvEditorShared.flushFocusedFields(profileTickField, profileFogEndField, currentProfile(), editable);
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        ensureWidgetsInitialized();
        buttonHits.clear();
        mapHits.clear();

        subHitThisFrame = subTabBar.render(g, font, x, y, w, subTab, mx, my);
        int contentY = y + SubTabBar.H + 4;
        int contentH = h - SubTabBar.H - 4;
        area.setBounds(x, contentY, w, contentH);

        g.enableScissor(x, contentY, x + w, contentY + contentH);
        int contentStartY = area.getContentY();
        int cy = contentStartY + 6;
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        switch (subTab) {
            case SUB_MATCH -> cy = renderMatch(g, mx, my, delta, labelX, cy, innerW);
            case SUB_POST -> cy = renderPost(g, mx, my, delta, labelX, cy, innerW);
            case SUB_RAIN -> cy = renderRain(g, mx, my, delta, labelX, cy, innerW);
            default -> {}
        }

        cy += 8;
        area.setContentHeight(cy - contentStartY);
        area.render(g);
        g.disableScissor();

        if (!editable) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改"),
                    x + PAD, y + h - 14, 0xFF5555, false);
        }
    }

    private int renderMatch(GuiGraphics g, int mx, int my, float delta, int labelX, int cy, int innerW) {
        g.drawString(font, "对局环境配置", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7左侧选地图覆盖；无覆盖时使用默认配置", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H;

        int listW = Math.min(140, Math.max(100, innerW / 3));
        int editorX = labelX + listW + 10;
        int editorW = innerW - listW - 10;
        int listStartY = cy;

        // 左侧地图列表
        List<String> maps = collectMapIds();
        int rowY = listStartY;

        boolean defSel = selectedMapId == null;
        boolean defHover = MenuTheme.inBounds(mx, my, labelX, rowY, listW, ROW_H);
        g.fill(labelX, rowY, labelX + listW, rowY + ROW_H - 2,
                defSel ? MenuTheme.BG_ROW_SELECTED : (defHover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW));
        MenuTheme.drawAccentStripe(g, labelX, rowY, ROW_H - 2, ACCENT);
        g.drawString(font, "§e默认", labelX + 8, rowY + 6, MenuTheme.TEXT_PRIMARY, false);
        mapHits.add(new MapRowHit(null, labelX, rowY, listW, ROW_H - 2));
        rowY += ROW_H;

        for (String id : maps) {
            boolean sel = id.equals(selectedMapId);
            boolean hover = MenuTheme.inBounds(mx, my, labelX, rowY, listW, ROW_H);
            g.fill(labelX, rowY, labelX + listW, rowY + ROW_H - 2,
                    sel ? MenuTheme.BG_ROW_SELECTED : (hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW));
            MenuTheme.drawAccentStripe(g, labelX, rowY, ROW_H - 2, MenuTheme.accentFor(id));
            boolean hasOverride = settings().matchMaps.containsKey(id);
            String shortId = id.length() > 14 ? id.substring(0, 12) + "…" : id;
            g.drawString(font, (hasOverride ? "§a" : "§7") + shortId, labelX + 8, rowY + 6,
                    MenuTheme.TEXT_PRIMARY, false);
            mapHits.add(new MapRowHit(id, labelX, rowY, listW, ROW_H - 2));
            rowY += ROW_H;
        }

        if (maps.isEmpty()) {
            g.drawString(font, "§8无地图", labelX + 8, rowY + 4, MenuTheme.TEXT_SECONDARY, false);
            rowY += ROW_H;
        }

        // 右侧编辑器
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
            buttonHits.add(new EnvEditorShared.ButtonHit("delete_map", editorX, editorCy, delW, 16));
            editorCy += ROW_H + 2;
        }

        EnvProfile profile = currentProfile();
        editorCy = EnvEditorShared.renderProfileEditor(g, mx, my, delta, font, editorX, editorCy, editorW,
                profile, "profile", buttonHits, profileTickField, profileFogEndField);

        return Math.max(rowY, editorCy) + 4;
    }

    private int renderPost(GuiGraphics g, int mx, int my, float delta, int labelX, int cy, int innerW) {
        g.drawString(font, "局后时间", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7好人 = isInnocentWin()；其余走杀手/中立", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H;

        EnvironmentSettings s = settings();
        if (s.goodWin == null) s.goodWin = PostMatchTimeRule.createDefault();
        if (s.otherWin == null) s.otherWin = PostMatchTimeRule.createDefault();
        if (s.goodWin.time == null) s.goodWin.time = EnvTimeSpec.createDefault();
        if (s.otherWin.time == null) s.otherWin.time = EnvTimeSpec.createDefault();

        g.drawString(font, "好人胜利", labelX, cy, MenuTheme.ACCENT_MINT, false);
        cy += HEADER_H;
        cy = renderPostRule(g, mx, my, delta, labelX, cy, innerW, s.goodWin, "good", goodTickField);

        cy += 6;
        g.fill(labelX, cy, labelX + innerW, cy + 1, MenuTheme.SEPARATOR);
        cy += 8;

        g.drawString(font, "杀手 / 中立", labelX, cy, MenuTheme.DANGER, false);
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

    private int renderTimeSpecEditor(GuiGraphics g, int mx, int my, float delta,
                                     int labelX, int cy, int innerW,
                                     EnvTimeSpec time, String prefix, EditBox tickBox) {
        if (time == null) return cy;
        g.drawString(font, "时间模式:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        int modeX = labelX + 60;
        int modeW = 70;
        boolean isPreset = time.mode != EnvTimeSpec.Mode.TICK;
        g.fill(modeX, cy, modeX + modeW, cy + 16, MenuTheme.BG_EDIT);
        g.drawString(font, isPreset ? "PRESET" : "TICK", modeX + 12, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new EnvEditorShared.ButtonHit(prefix + ":time_mode", modeX, cy, modeW, 16));
        cy += ROW_H;

        if (isPreset) {
            if (time.preset == null) time.preset = EnvTimeSpec.Preset.DAY;
            g.drawString(font, "预设:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
            int preX = labelX + 40;
            int preW = 90;
            g.fill(preX, cy, preX + preW, cy + 16, MenuTheme.BG_EDIT);
            g.drawString(font, time.preset.name(), preX + 8, cy + 4, 0xFFFFFFFF, false);
            buttonHits.add(new EnvEditorShared.ButtonHit(prefix + ":preset", preX, cy, preW, 16));
            g.drawString(font, "§7tick=" + time.preset.time, preX + preW + 8, cy + 4,
                    MenuTheme.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            g.drawString(font, "Tick(0-23999):", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
            if (!tickBox.isFocused()) {
                tickBox.setValue(String.valueOf(EnvTimeSpec.clampTick(time.tick)));
            }
            tickBox.setX(labelX + 90);
            tickBox.setY(cy);
            tickBox.setWidth(56);
            tickBox.render(g, mx, my, delta);
            int applyX = labelX + 154;
            g.fill(applyX, cy - 1, applyX + 40, cy + 15, MenuTheme.BG_EDIT);
            g.drawString(font, "应用", applyX + 8, cy + 3, 0xFFFFFFFF, false);
            buttonHits.add(new EnvEditorShared.ButtonHit(prefix + ":apply_tick", applyX, cy - 1, 40, 16));
            cy += ROW_H;
        }
        return cy;
    }

    private int renderRain(GuiGraphics g, int mx, int my, float delta, int labelX, int cy, int innerW) {
        g.drawString(font, "动态雨 / 低人数", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7人数低于阈值时强制下雨（对局中）", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H;

        EnvironmentSettings s = settings();
        cy = drawToggle(g, labelX, cy, "启用低人数动态雨", s.lowPlayerRainEnabled, "rain:enabled");

        g.drawString(font, "最少玩家数:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        if (!minPlayersField.isFocused()) {
            minPlayersField.setValue(String.valueOf(s.clampedMinPlayers()));
        }
        minPlayersField.setX(labelX + 80);
        minPlayersField.setY(cy);
        minPlayersField.setWidth(48);
        minPlayersField.render(g, mx, my, delta);

        int applyX = labelX + 136;
        g.fill(applyX, cy - 1, applyX + 40, cy + 15, MenuTheme.BG_EDIT);
        g.drawString(font, "应用", applyX + 8, cy + 3, 0xFFFFFFFF, false);
        buttonHits.add(new EnvEditorShared.ButtonHit("rain:apply_min", applyX, cy - 1, 40, 16));
        cy += ROW_H + 4;
        g.drawString(font, "§7当前生效阈值: " + s.clampedMinPlayers() + "（≥1）",
                labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H;
        return cy;
    }

    private int drawToggle(GuiGraphics g, int labelX, int cy, String label, boolean on, String action) {
        int tw = 50;
        PillToggle.render(g, font, labelX, cy, tw, 16, on, "开", "关");
        buttonHits.add(new EnvEditorShared.ButtonHit(action, labelX, cy, tw, 16));
        g.drawString(font, label, labelX + tw + 8, cy + 4, MenuTheme.TEXT_PRIMARY, false);
        return cy + ROW_H;
    }

    // ---------------- 输入 ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        ensureWidgetsInitialized();

        // 内层子 Tab 切换（滚动归零 + 强制同步字段 + 失焦）
        if (MenuTheme.inBounds(mx, my, x, y, w, SubTabBar.H)) {
            if (subHitThisFrame >= 0) playClick();
            if (subHitThisFrame >= 0 && subHitThisFrame != subTab) {
                subTab = subHitThisFrame;
                area.reset();
                syncFieldsFromSettings(true);
                EnvEditorShared.unfocusAll(profileTickField, profileFogEndField, goodTickField, otherTickField, minPlayersField);
            }
            return true;
        }

        int contentY = y + SubTabBar.H + 4;
        int contentH = h - SubTabBar.H - 4;
        if (my < contentY || my >= contentY + contentH) return false;

        // EditBoxes first（手动边界；停靠哨兵坐标的框自然落在边界外）
        if (subTab == SUB_MATCH) {
            if (EnvEditorShared.tryFocusEditBox(mx, my, profileTickField.getX(), profileTickField.getY(),
                    profileTickField.getWidth(), profileTickField.getHeight(), profileTickField, editable)) return true;
            if (EnvEditorShared.tryFocusEditBox(mx, my, profileFogEndField.getX(), profileFogEndField.getY(),
                    profileFogEndField.getWidth(), profileFogEndField.getHeight(), profileFogEndField, editable)) return true;
        }
        if (subTab == SUB_POST) {
            if (EnvEditorShared.tryFocusEditBox(mx, my, goodTickField.getX(), goodTickField.getY(),
                    goodTickField.getWidth(), goodTickField.getHeight(), goodTickField, editable)) return true;
            if (EnvEditorShared.tryFocusEditBox(mx, my, otherTickField.getX(), otherTickField.getY(),
                    otherTickField.getWidth(), otherTickField.getHeight(), otherTickField, editable)) return true;
        }
        if (subTab == SUB_RAIN) {
            if (EnvEditorShared.tryFocusEditBox(mx, my, minPlayersField.getX(), minPlayersField.getY(),
                    minPlayersField.getWidth(), minPlayersField.getHeight(), minPlayersField, editable)) return true;
        }

        // 地图列表选择
        for (MapRowHit hit : mapHits) {
            if (MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                playClick();
                selectedMapId = hit.mapId();
                if (selectedMapId != null) {
                    EnvironmentSettings s = settings();
                    if (!s.matchMaps.containsKey(selectedMapId)) {
                        if (!editable) {
                            MenuPermissions.showDeniedMessage();
                            selectedMapId = null;
                            return true;
                        }
                        s.matchMaps.put(selectedMapId, EnvProfile.createMatchDefault());
                        dirty();
                    }
                }
                // 刷新新选择的 profile 字段
                EnvProfile p = currentProfile();
                if (p != null && p.time != null) {
                    profileTickField.setValue(String.valueOf(EnvTimeSpec.clampTick(p.time.tick)));
                    float fe = p.fogEnd;
                    profileFogEndField.setValue((fe == (int) fe) ? String.valueOf((int) fe) : String.valueOf(fe));
                }
                EnvEditorShared.unfocusAll(profileTickField, profileFogEndField, goodTickField, otherTickField, minPlayersField);
                return true;
            }
        }

        for (EnvEditorShared.ButtonHit hit : buttonHits) {
            if (!MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            playClick();
            return handleAction(hit.action());
        }

        // 点击空白：失焦并允许拖拽滚动
        EnvEditorShared.unfocusAll(profileTickField, profileFogEndField, goodTickField, otherTickField, minPlayersField);
        return area.mouseClicked(mx, my, btn);
    }

    private boolean handleAction(String action) {
        if (!editable) {
            MenuPermissions.showDeniedMessage();
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

        if ("rain:apply_min".equals(action)) {
            try {
                int v = Integer.parseInt(minPlayersField.getValue().trim());
                settings().lowPlayerRainMinPlayers = Math.max(1, v);
                minPlayersField.setValue(String.valueOf(settings().clampedMinPlayers()));
                dirty();
            } catch (NumberFormatException ignored) {}
            return true;
        }

        // rain:enabled / good: / other: / profile: 统一走 EnvEditorShared。
        // good/other 的 apply_tick 需要把对应规则的时间框作为 profileTickField 传入。
        EditBox tickField = profileTickField;
        if (action.startsWith("good:")) tickField = goodTickField;
        else if (action.startsWith("other:")) tickField = otherTickField;
        EnvProfile profile = currentProfile();
        EnvEditorShared.applyTimeOrToggle(action, settings(), profile, buttonHits, font, 0,
                tickField, profileFogEndField, editable, this::dirty);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        return area.mouseDragged(my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        return area.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        return area.mouseScrolled(sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (profileTickField != null && profileTickField.isFocused() && profileTickField.keyPressed(key, scan, mod)) return true;
        if (profileFogEndField != null && profileFogEndField.isFocused() && profileFogEndField.keyPressed(key, scan, mod)) return true;
        if (goodTickField != null && goodTickField.isFocused() && goodTickField.keyPressed(key, scan, mod)) return true;
        if (otherTickField != null && otherTickField.isFocused() && otherTickField.keyPressed(key, scan, mod)) return true;
        if (minPlayersField != null && minPlayersField.isFocused() && minPlayersField.keyPressed(key, scan, mod)) return true;
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (profileTickField != null && profileTickField.isFocused() && profileTickField.charTyped(ch, mod)) return true;
        if (profileFogEndField != null && profileFogEndField.isFocused() && profileFogEndField.charTyped(ch, mod)) return true;
        if (goodTickField != null && goodTickField.isFocused() && goodTickField.charTyped(ch, mod)) return true;
        if (otherTickField != null && otherTickField.isFocused() && otherTickField.charTyped(ch, mod)) return true;
        if (minPlayersField != null && minPlayersField.isFocused() && minPlayersField.charTyped(ch, mod)) return true;
        return false;
    }
}
