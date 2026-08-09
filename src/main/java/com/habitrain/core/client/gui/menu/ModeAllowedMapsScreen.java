package com.habitrain.core.client.gui.menu;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.config.ModeVoteEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模式可选地图多选界面。空选 = 不限制（allowedMaps 清空）。
 */
public class ModeAllowedMapsScreen extends Screen {

    private static final int PAD = 10;
    private static final int HEADER_H = 44;
    private static final int FOOTER_H = 36;
    private static final int ROW_H = 22;
    private static final int ACCENT = MenuTheme.ACCENT_BLUE;

    private final Screen parent;
    private final String modeId;
    private final boolean remoteEditable;

    private final List<String> mapIds = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private double scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0;
    private double dragStartOff = 0;
    private int contentHeight = 0;

    private Button saveBtn;
    private Button clearBtn;
    private Button backBtn;

    private final List<RowHit> rowHits = new ArrayList<>();

    private record RowHit(String id, int x, int y, int w, int h) {}

    public ModeAllowedMapsScreen(Screen parent, String modeId) {
        super(Component.literal("可选地图 — " + modeId));
        this.parent = parent;
        this.modeId = modeId;
        this.remoteEditable = MenuPermissions.canEditRemoteConfigs();
        loadFromSettings();
    }

    private ModeMapVoteSettings settings() {
        return ConfigManager.getInstance().getModeMapVoteSettings();
    }

    private void loadFromSettings() {
        ModeMapVoteSettings s = settings();
        Set<String> ids = new LinkedHashSet<>(s.maps.keySet());
        ModeVoteEntry mode = s.modes.get(modeId);
        if (mode != null && mode.allowedMaps != null) {
            ids.addAll(mode.allowedMaps);
        }
        mapIds.clear();
        mapIds.addAll(ids);
        selected.clear();
        if (mode != null && mode.allowedMaps != null) {
            selected.addAll(mode.allowedMaps);
        }
    }

    @Override
    protected void init() {
        super.init();
        backBtn = Button.builder(Component.literal("§7← 返回"), b -> onClose())
                .bounds(PAD, 4, 70, 18).build();
        addRenderableWidget(backBtn);

        clearBtn = Button.builder(Component.literal("§7清空（不限制）"), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            selected.clear();
        }).bounds(-10000, -10000, 110, 20).build();
        addRenderableWidget(clearBtn);

        saveBtn = Button.builder(Component.literal("§a保存"), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            commitSave();
            Minecraft.getInstance().setScreen(parent);
        }).bounds(-10000, -10000, 80, 20).build();
        addRenderableWidget(saveBtn);

        if (!remoteEditable) {
            clearBtn.active = false;
            saveBtn.active = false;
        }
    }

    private void commitSave() {
        ModeMapVoteSettings s = settings();
        ModeVoteEntry mode = s.modes.computeIfAbsent(modeId, k -> ModeVoteEntry.createDefault());
        mode.allowedMaps = new ArrayList<>(selected);
        ConfigManager.getInstance().setModeMapVoteSettings(s);
        ConfigManager.getInstance().save();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        // Single background pass only. Screen.render() would call renderBackground again
        // and cover custom content with blur; widgets are drawn manually below.
        renderBackground(g, mx, my, delta);
        MenuTheme.drawBackdrop(g, width, height, ACCENT);
        MenuTheme.editorHeader(g, font, width, "可选地图",
                "模式 " + modeId + " · 空选表示不限制地图", ACCENT);
        MenuTheme.editorFooter(g, width, height, FOOTER_H);

        int contentTop = HEADER_H;
        int contentBot = height - FOOTER_H;
        int contentH = contentBot - contentTop;
        int scrollW = width - PAD * 2;

        rowHits.clear();
        g.enableScissor(PAD, contentTop, PAD + scrollW, contentBot);

        int y = contentTop + 4 - (int) scrollOffset;
        if (mapIds.isEmpty()) {
            g.drawString(font, "§7暂无地图配置条目", PAD + 4, y + 4, MenuTheme.TEXT_SECONDARY, false);
            y += ROW_H;
        } else {
            for (String id : mapIds) {
                boolean on = selected.contains(id);
                boolean hover = MenuTheme.inBounds(mx, my, PAD, y, scrollW - 8, ROW_H);
                MenuTheme.row(g, PAD, y, scrollW - 8, ROW_H - 2, hover, false);
                g.fill(PAD + 4, y + 4, PAD + 16, y + 16, on ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
                if (on) {
                    g.drawString(font, "§a✓", PAD + 5, y + 5, 0xFFFFFFFF, false);
                }
                MapVoteEntry me = settings().maps.get(id);
                String label = (me != null && me.displayName != null && !me.displayName.isEmpty())
                        ? me.displayName + " §8(" + id + ")"
                        : id;
                g.drawString(font, label, PAD + 22, y + 5, MenuTheme.TEXT_PRIMARY, false);
                rowHits.add(new RowHit(id, PAD, y, scrollW - 8, ROW_H - 2));
                y += ROW_H;
            }
        }

        contentHeight = y - contentTop + (int) scrollOffset;
        g.disableScissor();

        int maxScroll = Math.max(0, contentHeight - contentH);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        MenuTheme.drawScrollbar(g, PAD + scrollW - 4, contentTop, contentH, scrollOffset, maxScroll, 3);

        clearBtn.setX(width / 2 - 110);
        clearBtn.setY(height - 28);
        clearBtn.setWidth(110);
        saveBtn.setX(width / 2 + 20);
        saveBtn.setY(height - 28);
        saveBtn.setWidth(80);

        // Draw widgets without Screen.render() — that would call renderBackground again
        // and cover the custom list with a second blur pass (MC 1.21.x).
        backBtn.render(g, mx, my, delta);
        clearBtn.render(g, mx, my, delta);
        saveBtn.render(g, mx, my, delta);

        if (!remoteEditable) {
            g.drawString(font, Component.literal("§c只读：联机服务器中仅 OP 可修改"),
                    PAD, height - 12, MenuTheme.DANGER, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;
        int contentTop = HEADER_H;
        int contentBot = height - 34;
        if (my >= contentTop && my < contentBot) {
            for (RowHit hit : rowHits) {
                if (MenuTheme.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    MenuSounds.playClick();
                    if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return true; }
                    if (selected.contains(hit.id())) selected.remove(hit.id());
                    else selected.add(hit.id());
                    return true;
                }
            }
            int scrollW = width - PAD * 2;
            int sbX = PAD + scrollW - 4;
            if (mx >= sbX - 2 && mx <= sbX + 6) {
                draggingScroll = true;
                dragStartY = my;
                dragStartOff = scrollOffset;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingScroll) {
            int contentTop = HEADER_H;
            int contentBot = height - 34;
            int contentH = contentBot - contentTop;
            int maxScroll = Math.max(0, contentHeight - contentH);
            scrollOffset = Mth.clamp(dragStartOff + (dragStartY - my), 0, maxScroll);
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingScroll = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int contentTop = HEADER_H;
        int contentBot = height - 34;
        int contentH = contentBot - contentTop;
        int maxScroll = Math.max(0, contentHeight - contentH);
        scrollOffset = Mth.clamp(scrollOffset - sy * 18, 0, maxScroll);
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
