package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.ScrollArea;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.EnvProfile;
import com.habitrain.core.config.EnvTimeSpec;
import com.habitrain.core.config.EnvironmentSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 游戏外·大厅环境（内联页面）：控制大厅（非对局）时段的时间、天气与雾效。 */
public class OutGameLobbyEnvPage implements ConfigPage {

    private static final int PAD = 12;
    private static final int HEADER_H = 16;
    private static final int ROW_H = 22;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private EditBox profileTickField;
    private EditBox profileFogEndField;
    private ScrollArea area;
    private final List<EnvEditorShared.ButtonHit> buttonHits = new ArrayList<>();
    private boolean widgetsInitialized = false;

    public OutGameLobbyEnvPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        this.area = new ScrollArea(0, 0, 0, 0); // 坐标在 render 里设定
    }

    private EnvironmentSettings settings() {
        return ConfigManager.getInstance().getEnvironmentSettings();
    }

    private EnvProfile lobbyProfile() {
        EnvironmentSettings s = settings();
        if (s.lobby == null) s.lobby = EnvProfile.createLobbyDefault();
        return s.lobby;
    }

    private void dirty() {
        ConfigManager.getInstance().markEnvironmentDirty();
    }

    @Override public boolean canSave() { return true; }

    @Override public void save() {
        ConfigManager.getInstance().markEnvironmentDirty();
    }

    @Override public void flushPending() {
        EnvEditorShared.flushFocusedFields(profileTickField, profileFogEndField, lobbyProfile(), editable);
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

        EnvProfile profile = lobbyProfile();
        if (profile != null) {
            if (profile.time == null) profile.time = EnvTimeSpec.createDefault();
            profileTickField.setValue(String.valueOf(EnvTimeSpec.clampTick(profile.time.tick)));
            float fe = profile.fogEnd;
            profileFogEndField.setValue((fe == (int) fe) ? String.valueOf((int) fe) : String.valueOf(fe));
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        ensureWidgetsInitialized();
        buttonHits.clear();
        area.setBounds(x, y, w, h);
        g.enableScissor(x, y, x + w, y + h);

        int startCy = area.getContentY();
        int cy = startCy;
        int labelX = x + PAD;
        int innerW = w - PAD * 2 - 6;

        // ===== 标题 + 说明（对应旧版 renderLobby） =====
        g.drawString(font, "大厅环境配置", labelX, cy, MenuTheme.TEXT_PRIMARY, false);
        cy += HEADER_H + 2;
        g.drawString(font, "§7控制大厅（非对局）时段的时间、天气与雾效", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += ROW_H;

        cy = EnvEditorShared.renderProfileEditor(g, mx, my, delta, font, labelX, cy, innerW,
                lobbyProfile(), "profile", buttonHits, profileTickField, profileFogEndField);

        cy += 8;
        area.setContentHeight(cy - startCy);
        area.render(g);
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        ensureWidgetsInitialized();

        // 文本框优先（手动边界，停靠哨兵坐标自然落空）
        EnvEditorShared.unfocusAll(profileTickField, profileFogEndField);
        if (EnvEditorShared.tryFocusEditBox(mx, my, profileTickField.getX(), profileTickField.getY(),
                profileTickField.getWidth(), profileTickField.getHeight(), profileTickField, editable)) return true;
        if (EnvEditorShared.tryFocusEditBox(mx, my, profileFogEndField.getX(), profileFogEndField.getY(),
                profileFogEndField.getWidth(), profileFogEndField.getHeight(), profileFogEndField, editable)) return true;

        for (EnvEditorShared.ButtonHit hit : buttonHits) {
            if (!MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            MenuSounds.playClick();
            if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
            EnvEditorShared.applyTimeOrToggle(hit.action(), settings(), lobbyProfile(),
                    buttonHits, font, x + PAD, profileTickField, profileFogEndField, editable, this::dirty);
            return true;
        }

        return area.mouseClicked(mx, my, btn);
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return area.mouseDragged(my); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return area.mouseReleased(); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return area.mouseScrolled(sy); }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (profileTickField != null && profileTickField.isFocused() && profileTickField.keyPressed(key, scan, mod)) return true;
        if (profileFogEndField != null && profileFogEndField.isFocused() && profileFogEndField.keyPressed(key, scan, mod)) return true;
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (profileTickField != null && profileTickField.isFocused() && profileTickField.charTyped(ch, mod)) return true;
        if (profileFogEndField != null && profileFogEndField.isFocused() && profileFogEndField.charTyped(ch, mod)) return true;
        return false;
    }
}
