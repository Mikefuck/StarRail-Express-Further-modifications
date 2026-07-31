package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.EnvProfile;
import com.habitrain.core.config.EnvTimeSpec;
import com.habitrain.core.config.EnvironmentSettings;
import com.habitrain.core.config.PostMatchTimeRule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * 环境页共享静态工具：旧 {@code EnvironmentTabScreen} 的 profile 编辑器与动作分发被提取到这里，
 * 供 InGameEnvPage（对局/局后/雨）与 OutGameLobbyEnvPage（大厅环境）复用。
 * 只含纯函数式静态方法：所有旧 this.xxx 字段均改为显式参数；写配置由调用方传入的 dirty Runnable 触发。
 */
public final class EnvEditorShared {
    private EnvEditorShared() {}

    private static final int ROW_H = 22;

    /** 按钮/开关命中矩形；页面收集后用于 mouseClicked 分发。 */
    record ButtonHit(String action, int x, int y, int w, int h) {}

    /**
     * 渲染 EnvProfile 编辑器（时间模式 PRESET/TICK、预设循环、天气/雪/沙尘/雾/雾距离/日夜/天气循环）。
     * 与旧 EnvironmentTabScreen.renderProfileEditor 语义一致，仅把 this 字段改为参数。
     * 调用方需先以 setEditable(editable) 配置两个 EditBox（本方法不再每帧改写 editable）。
     * 返回下一行 y 坐标。
     */
    static int renderProfileEditor(GuiGraphics g, int mx, int my, float delta,
                                   Font font, int labelX, int cy, int innerW,
                                   EnvProfile profile, String prefix,
                                   List<ButtonHit> buttonHits,
                                   EditBox profileTickField, EditBox profileFogEndField) {
        if (profile == null) {
            g.drawString(font, "§c无配置对象", labelX, cy, 0xFFFF5555, false);
            return cy + ROW_H;
        }
        if (profile.time == null) profile.time = EnvTimeSpec.createDefault();
        if (profile.weather == null) profile.weather = EnvProfile.Weather.CLEAR;

        cy = drawToggle(g, font, labelX, cy, "启用环境覆盖", profile.enabled, prefix + ":enabled", buttonHits);

        // Time mode
        g.drawString(font, "时间模式:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        int modeX = labelX + 60;
        int modeW = 70;
        boolean isPreset = profile.time.mode != EnvTimeSpec.Mode.TICK;
        g.fill(modeX, cy, modeX + modeW, cy + 16, MenuTheme.BG_EDIT);
        g.drawString(font, isPreset ? "PRESET" : "TICK", modeX + 12, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(prefix + ":time_mode", modeX, cy, modeW, 16));
        cy += ROW_H;

        if (isPreset) {
            if (profile.time.preset == null) profile.time.preset = EnvTimeSpec.Preset.DAY;
            g.drawString(font, "预设:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
            int preX = labelX + 40;
            int preW = 90;
            g.fill(preX, cy, preX + preW, cy + 16, MenuTheme.BG_EDIT);
            g.drawString(font, profile.time.preset.name(), preX + 8, cy + 4, 0xFFFFFFFF, false);
            buttonHits.add(new ButtonHit(prefix + ":preset", preX, cy, preW, 16));
            g.drawString(font, "§7tick=" + profile.time.preset.time, preX + preW + 8, cy + 4,
                    MenuTheme.TEXT_SECONDARY, false);
            cy += ROW_H;
        } else {
            g.drawString(font, "Tick(0-23999):", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
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
            g.fill(applyX, cy - 1, applyX + 40, cy + 15, MenuTheme.BG_EDIT);
            g.drawString(font, "应用", applyX + 8, cy + 3, 0xFFFFFFFF, false);
            buttonHits.add(new ButtonHit(prefix + ":apply_tick", applyX, cy - 1, 40, 16));
            cy += ROW_H;
        }

        // Weather
        g.drawString(font, "天气:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
        int wx = labelX + 40;
        int ww = 80;
        g.fill(wx, cy, wx + ww, cy + 16, MenuTheme.BG_EDIT);
        g.drawString(font, profile.weather.name(), wx + 10, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(prefix + ":weather", wx, cy, ww, 16));
        cy += ROW_H;

        cy = drawToggle(g, font, labelX, cy, "雪", profile.snow, prefix + ":snow", buttonHits);
        cy = drawToggle(g, font, labelX, cy, "沙尘", profile.sand, prefix + ":sand", buttonHits);
        cy = drawToggle(g, font, labelX, cy, "雾", profile.fog, prefix + ":fog", buttonHits);

        g.drawString(font, "雾距离 fogEnd:", labelX, cy + 2, MenuTheme.TEXT_SECONDARY, false);
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
        profileFogEndField.render(g, mx, my, delta);
        int fogApplyX = labelX + 164;
        g.fill(fogApplyX, cy - 1, fogApplyX + 40, cy + 15, MenuTheme.BG_EDIT);
        g.drawString(font, "应用", fogApplyX + 8, cy + 3, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(prefix + ":apply_fog", fogApplyX, cy - 1, 40, 16));
        cy += ROW_H;

        cy = drawToggle(g, font, labelX, cy, "日夜循环 daylightCycle", profile.daylightCycle, prefix + ":daylight", buttonHits);
        cy = drawToggle(g, font, labelX, cy, "天气循环 weatherCycle", profile.weatherCycle, prefix + ":weatherCycle", buttonHits);
        return cy;
    }

    /**
     * 动作分发：处理旧 handleAction 中的环境相关动作（profile:*/good:*/other:*/rain:enabled）。
     * 每次状态变更都调用 dirty.run()。good/other 的 apply_tick 由调用方把对应规则的时间框
     * 作为 profileTickField 传入；profile 的 tick/fog 分别经 profileTickField/profileFogEndField 写出。
     * hits/font/labelX 保留在签名中供页面沿用同一分发入口（本方法内不直接使用）。
     */
    static void applyTimeOrToggle(String action, EnvironmentSettings s, EnvProfile profile,
                                  List<ButtonHit> hits, Font font, int labelX,
                                  EditBox profileTickField, EditBox profileFogEndField,
                                  boolean editable, Runnable dirty) {
        if (action == null || s == null) return;

        if ("rain:enabled".equals(action)) {
            if (!editable) {
                MenuPermissions.showDeniedMessage();
                return;
            }
            s.lowPlayerRainEnabled = !s.lowPlayerRainEnabled;
            dirty.run();
            return;
        }

        boolean postRule;
        String op;
        if (action.startsWith("good:") || action.startsWith("other:")) {
            postRule = true;
            op = action.substring(action.indexOf(':') + 1);
        } else if (action.startsWith("profile:")) {
            postRule = false;
            op = action.substring("profile:".length());
        } else {
            return;
        }

        // All edits require edit permission
        if (!editable) {
            MenuPermissions.showDeniedMessage();
            return;
        }

        PostMatchTimeRule rule = null;
        if (postRule) {
            rule = action.startsWith("good:") ? s.goodWin : s.otherWin;
            if (rule == null) {
                rule = PostMatchTimeRule.createDefault();
                if (action.startsWith("good:")) s.goodWin = rule; else s.otherWin = rule;
            }
            if (rule.time == null) rule.time = EnvTimeSpec.createDefault();
        }
        if (!postRule && profile == null) return;
        if (!postRule && profile.time == null) profile.time = EnvTimeSpec.createDefault();

        EnvTimeSpec time = postRule ? rule.time : profile.time;

        switch (op) {
            case "enabled" -> {
                if (postRule) rule.enabled = !rule.enabled;
                else profile.enabled = !profile.enabled;
                dirty.run();
            }
            case "time_mode" -> {
                time.mode = (time.mode == EnvTimeSpec.Mode.TICK)
                        ? EnvTimeSpec.Mode.PRESET : EnvTimeSpec.Mode.TICK;
                dirty.run();
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
                dirty.run();
            }
            case "apply_tick" -> {
                if (profileTickField == null) return;
                try {
                    int v = Integer.parseInt(profileTickField.getValue().trim());
                    time.tick = EnvTimeSpec.clampTick(v);
                    time.mode = EnvTimeSpec.Mode.TICK;
                    profileTickField.setValue(String.valueOf(time.tick));
                    dirty.run();
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
                dirty.run();
            }
            case "snow" -> { if (profile != null) { profile.snow = !profile.snow; dirty.run(); } }
            case "sand" -> { if (profile != null) { profile.sand = !profile.sand; dirty.run(); } }
            case "fog" -> { if (profile != null) { profile.fog = !profile.fog; dirty.run(); } }
            case "apply_fog" -> {
                if (profile == null || profileFogEndField == null) return;
                try {
                    float v = Float.parseFloat(profileFogEndField.getValue().trim());
                    if (v < 0) v = 0;
                    profile.fogEnd = v;
                    String fogStr = (v == (int) v) ? String.valueOf((int) v) : String.valueOf(v);
                    profileFogEndField.setValue(fogStr);
                    dirty.run();
                } catch (NumberFormatException ignored) {}
            }
            case "daylight" -> { if (profile != null) { profile.daylightCycle = !profile.daylightCycle; dirty.run(); } }
            case "weatherCycle" -> { if (profile != null) { profile.weatherCycle = !profile.weatherCycle; dirty.run(); } }
            default -> {}
        }
    }

    /**
     * 尝试聚焦一个 EditBox。x/y/w/h 为手动边界（旧版用 box.getX() 等并忽略停靠哨兵坐标）；
     * 停靠在 (-10000,-10000) 的框自然落在边界外而返回 false。
     * 只读时（box 创建时 setEditable(false)）弹权限拒绝消息。调用方应在调用前自行 unfocusAll。
     */
    static boolean tryFocusEditBox(double mx, double my, int x, int y, int w, int h, EditBox box) {
        if (box == null) return false;
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        if (!box.isEditable()) {
            MenuPermissions.showDeniedMessage();
            return true;
        }
        box.setFocused(true);
        box.setEditable(true);
        box.mouseClicked(mx, my, 0);
        playClick();
        return true;
    }

    /**
     * 把聚焦的 profile tick/fog 文本框写入 profile（保存/切页/关闭前调用）。
     * 非法输入静默忽略，保留旧值；成功写入才标记脏。
     */
    static void flushFocusedFields(EditBox profileTickField, EditBox profileFogEndField,
                                   EnvProfile profile, boolean editable) {
        if (!editable || profile == null) return;
        if (profileFogEndField != null && profileFogEndField.isFocused()) {
            try {
                float v = Float.parseFloat(profileFogEndField.getValue().trim());
                if (v < 0) v = 0;
                profile.fogEnd = v;
                ConfigManager.getInstance().markEnvironmentDirty();
            } catch (NumberFormatException ignored) {}
        }
        if (profileTickField != null && profileTickField.isFocused()) {
            if (profile.time == null) profile.time = EnvTimeSpec.createDefault();
            try {
                int v = Integer.parseInt(profileTickField.getValue().trim());
                profile.time.tick = EnvTimeSpec.clampTick(v);
                profile.time.mode = EnvTimeSpec.Mode.TICK;
                ConfigManager.getInstance().markEnvironmentDirty();
            } catch (NumberFormatException ignored) {}
        }
    }

    /** 取消所有给定文本框的焦点（页面点击空白/切页/切换选择时调用）。 */
    static void unfocusAll(EditBox... boxes) {
        if (boxes == null) return;
        for (EditBox box : boxes) {
            if (box != null) box.setFocused(false);
        }
    }

    private static int drawToggle(GuiGraphics g, Font font, int labelX, int cy,
                                  String label, boolean on, String action,
                                  List<ButtonHit> buttonHits) {
        int tw = 50;
        g.fill(labelX, cy, labelX + tw, cy + 16, on ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        g.drawString(font, on ? "§a开" : "§c关", labelX + 16, cy + 4, 0xFFFFFFFF, false);
        buttonHits.add(new ButtonHit(action, labelX, cy, tw, 16));
        g.drawString(font, label, labelX + tw + 8, cy + 4, MenuTheme.TEXT_PRIMARY, false);
        return cy + ROW_H;
    }

    private static void playClick() {
        try {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        } catch (Throwable ignored) {}
    }
}
