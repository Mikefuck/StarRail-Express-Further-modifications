package com.habitrain.core.client.gui.config;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapPoolEntry;
import com.habitrain.core.config.MapPoolRotationSettings;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.vote.MapPoolRotationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Edit variable-count map pools: add/remove pools, rename, enable, membership, repartition.
 */
public class MapPoolEditorScreen extends Screen {

    private static final int PAD = 10;
    private static final int HEADER_H = 36;
    private static final int POOL_ROW_H = 22;
    private static final int ROW_H = 22;
    private static final int ACCENT = 0xFF7C9CFF;
    private static final int POOL_PANEL_W = 180;
    private static final int FOOTER_H = 52;

    private final Screen parent;
    private final boolean remoteEditable;

    private int selectedPool = 0;
    private final List<String> allMapIds = new ArrayList<>();
    private final Set<String> selectedMaps = new LinkedHashSet<>();
    private final List<EditBox> nameFields = new ArrayList<>();

    private double scrollOffset = 0;
    private double poolScrollOffset = 0;
    private boolean draggingScroll = false;
    private boolean draggingPoolScroll = false;
    private double dragStartY = 0;
    private double dragStartOff = 0;
    private int contentHeight = 0;
    private int poolListHeight = 0;

    private Button saveBtn;
    private Button clearBtn;
    private Button repartitionBtn;
    private Button addPoolBtn;
    private Button removePoolBtn;
    private Button backBtn;

    private final List<RowHit> mapHits = new ArrayList<>();
    private final List<PoolHit> poolHits = new ArrayList<>();

    private record RowHit(String id, int x, int y, int w, int h) {}
    private record PoolHit(int index, int x, int y, int w, int h, boolean toggle) {}

    public MapPoolEditorScreen(Screen parent, boolean remoteEditable) {
        super(Component.literal("地图池编辑"));
        this.parent = parent;
        this.remoteEditable = remoteEditable;
        loadFromSettings();
    }

    private ModeMapVoteSettings settings() {
        return ConfigManager.getInstance().getModeMapVoteSettings();
    }

    private MapPoolRotationSettings rot() {
        return settings().rotationOrDefault();
    }

    private void loadFromSettings() {
        ModeMapVoteSettings s = settings();
        MapPoolRotationSettings rot = s.rotationOrDefault();
        selectedPool = rot.clampIndex(rot.activePoolIndex);
        allMapIds.clear();
        allMapIds.addAll(s.maps.keySet());
        for (MapPoolEntry p : rot.pools) {
            if (p.mapIds != null) {
                for (String id : p.mapIds) {
                    if (id != null && !id.isBlank() && !allMapIds.contains(id)) {
                        allMapIds.add(id);
                    }
                }
            }
        }
        syncSelectedFromPool();
    }

    private void syncSelectedFromPool() {
        selectedMaps.clear();
        if (rot().poolCount() <= 0) return;
        selectedPool = rot().clampIndex(selectedPool);
        MapPoolEntry p = rot().poolAt(selectedPool);
        if (p.mapIds != null) selectedMaps.addAll(p.mapIds);
    }

    private void writeSelectedToPool() {
        if (rot().poolCount() <= 0) return;
        selectedPool = rot().clampIndex(selectedPool);
        MapPoolEntry p = rot().poolAt(selectedPool);
        p.mapIds = new ArrayList<>(selectedMaps);
    }

    private void rebuildNameFields() {
        // Remove old edit boxes from screen children if re-init
        for (EditBox box : nameFields) {
            removeWidget(box);
        }
        nameFields.clear();
        MapPoolRotationSettings rot = rot();
        for (int i = 0; i < rot.poolCount(); i++) {
            MapPoolEntry p = rot.poolAt(i);
            EditBox box = new EditBox(font, -10000, -10000, 90, 14, Component.literal(""));
            box.setMaxLength(32);
            box.setValue(p.displayName != null ? p.displayName : ("池" + (i + 1)));
            box.setEditable(remoteEditable);
            nameFields.add(box);
            addWidget(box);
        }
    }

    @Override
    protected void init() {
        super.init();
        rebuildNameFields();

        backBtn = Button.builder(Component.literal("§7← 返回"), b -> onClose())
                .bounds(PAD, 4, 70, 18).build();
        addRenderableWidget(backBtn);

        addPoolBtn = Button.builder(Component.literal("§a+ 添加池"), b -> {
            if (!remoteEditable) { LiveConfigAccess.showDeniedMessage(); return; }
            flushNames();
            writeSelectedToPool();
            MapPoolRotationSettings rot = rot();
            if (!rot.addPool()) {
                toast("§c已达最大池数 " + MapPoolRotationSettings.MAX_POOLS);
                return;
            }
            selectedPool = rot.poolCount() - 1;
            rebuildNameFields();
            syncSelectedFromPool();
            toast("§a已添加池 " + rot.poolCount() + "（请保存）");
        }).bounds(-10000, -10000, 70, 18).build();
        addRenderableWidget(addPoolBtn);

        removePoolBtn = Button.builder(Component.literal("§c删本池"), b -> {
            if (!remoteEditable) { LiveConfigAccess.showDeniedMessage(); return; }
            MapPoolRotationSettings rot = rot();
            if (rot.poolCount() <= MapPoolRotationSettings.MIN_POOLS) {
                toast("§c至少保留 " + MapPoolRotationSettings.MIN_POOLS + " 个池");
                return;
            }
            flushNames();
            // don't writeSelected for the pool we're deleting
            int removeIdx = rot.clampIndex(selectedPool);
            if (!rot.removePool(removeIdx)) {
                toast("§c无法删除");
                return;
            }
            selectedPool = rot.clampIndex(removeIdx);
            rebuildNameFields();
            syncSelectedFromPool();
            toast("§e已删除池（请保存）");
        }).bounds(-10000, -10000, 70, 18).build();
        addRenderableWidget(removePoolBtn);

        repartitionBtn = Button.builder(Component.literal("§e重新均摊分池"), b -> {
            if (!remoteEditable) { LiveConfigAccess.showDeniedMessage(); return; }
            flushNames();
            writeSelectedToPool();
            MapPoolRotationService.repartition(settings(), new Random());
            ConfigManager.getInstance().setModeMapVoteSettings(settings());
            rebuildNameFields();
            syncSelectedFromPool();
            toast("§a已均摊分池（每池最多4图，可跨池重复；请保存）");
        }).bounds(-10000, -10000, 90, 20).build();
        addRenderableWidget(repartitionBtn);

        clearBtn = Button.builder(Component.literal("§7清空本池"), b -> {
            if (!remoteEditable) { LiveConfigAccess.showDeniedMessage(); return; }
            selectedMaps.clear();
        }).bounds(-10000, -10000, 90, 20).build();
        addRenderableWidget(clearBtn);

        saveBtn = Button.builder(Component.literal("§a保存"), b -> {
            if (!remoteEditable) { LiveConfigAccess.showDeniedMessage(); return; }
            commitSave();
            Minecraft.getInstance().setScreen(parent);
        }).bounds(-10000, -10000, 80, 20).build();
        addRenderableWidget(saveBtn);

        if (!remoteEditable) {
            addPoolBtn.active = false;
            removePoolBtn.active = false;
            repartitionBtn.active = false;
            clearBtn.active = false;
            saveBtn.active = false;
        }
    }

    private void toast(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), true);
        }
    }

    private void flushNames() {
        MapPoolRotationSettings rot = rot();
        int n = Math.min(nameFields.size(), rot.poolCount());
        for (int i = 0; i < n; i++) {
            EditBox box = nameFields.get(i);
            if (box == null) continue;
            String v = box.getValue() != null ? box.getValue().trim() : "";
            MapPoolEntry p = rot.poolAt(i);
            p.displayName = v.isEmpty() ? ("池" + (i + 1)) : v;
        }
    }

    private void commitSave() {
        flushNames();
        writeSelectedToPool();
        ModeMapVoteSettings s = settings();
        MapPoolRotationSettings rot = s.rotationOrDefault();
        for (MapPoolEntry p : rot.pools) {
            if (p.mapIds == null) continue;
            p.mapIds.removeIf(id -> id == null || id.isBlank());
        }
        ConfigManager.getInstance().setModeMapVoteSettings(s);
        ConfigManager.getInstance().save();
        toast("§a地图池已保存（共 " + rot.poolCount() + " 池）");
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        SharedGuiKit.drawBackdrop(g, width, height, ACCENT);

        MapPoolRotationSettings rot = rot();
        g.drawString(font, Component.literal("§l地图池编辑"), PAD + 80, 8, 0xFFFFFFFF, false);
        g.drawString(font, Component.literal("§7共 " + rot.poolCount() + " 池（"
                        + MapPoolRotationSettings.MIN_POOLS + "–"
                        + MapPoolRotationSettings.MAX_POOLS + "）· 勾选加入当前池"),
                PAD + 80, 22, 0xFF888888, false);

        int contentTop = HEADER_H;
        int contentBot = height - FOOTER_H;
        int poolX = PAD;
        int poolW = POOL_PANEL_W;
        int mapsX = poolX + poolW + 8;
        int mapsW = width - mapsX - PAD;

        poolHits.clear();
        mapHits.clear();

        // Left: pool list (scrollable)
        int poolListTop = contentTop + 4;
        g.drawString(font, "§e池列表", poolX, poolListTop, ACCENT, false);
        int poolRowsTop = poolListTop + 14;
        int poolRowsBot = contentBot - 2;
        int poolViewH = Math.max(20, poolRowsBot - poolRowsTop);

        g.enableScissor(poolX, poolRowsTop, poolX + poolW, poolRowsBot);
        int py = poolRowsTop - (int) poolScrollOffset;
        for (int i = 0; i < rot.poolCount(); i++) {
            MapPoolEntry p = rot.poolAt(i);
            boolean sel = i == selectedPool;
            boolean cur = i == rot.activePoolIndex;
            boolean hover = SharedGuiKit.inBounds(mx, my, poolX, py, poolW, POOL_ROW_H)
                    && my >= poolRowsTop && my < poolRowsBot;
            g.fill(poolX, py, poolX + poolW, py + POOL_ROW_H - 2,
                    sel ? 0x553A5FBF : (hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW));
            int tX = poolX + 4;
            g.fill(tX, py + 4, tX + 14, py + 16, p.enabled ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
            if (p.enabled) g.drawString(font, "§a✓", tX + 2, py + 5, 0xFFFFFFFF, false);
            poolHits.add(new PoolHit(i, tX, py + 4, 14, 12, true));

            EditBox box = i < nameFields.size() ? nameFields.get(i) : null;
            if (box != null) {
                box.setX(poolX + 22);
                box.setY(py + 3);
                box.setWidth(poolW - 78);
                box.render(g, mx, my, delta);
            }
            String badge = cur ? "§a当前" : ("§8#" + (i + 1));
            g.drawString(font, badge, poolX + poolW - 42, py + 5, 0xFFFFFFFF, false);
            int count = p.mapIds != null ? p.mapIds.size() : 0;
            g.drawString(font, "§7" + count, poolX + poolW - 16, py + 5, SharedGuiKit.TEXT_SECONDARY, false);
            poolHits.add(new PoolHit(i, poolX, py, poolW, POOL_ROW_H - 2, false));
            py += POOL_ROW_H;
        }
        poolListHeight = py - poolRowsTop + (int) poolScrollOffset;
        g.disableScissor();
        int maxPoolScroll = Math.max(0, poolListHeight - poolViewH);
        poolScrollOffset = Mth.clamp(poolScrollOffset, 0, maxPoolScroll);
        SharedGuiKit.drawScrollbar(g, poolX + poolW - 3, poolRowsTop, poolViewH, poolScrollOffset, maxPoolScroll, 3);

        // Right: map multi-select
        g.enableScissor(mapsX, contentTop, mapsX + mapsW, contentBot);
        int y = contentTop + 4 - (int) scrollOffset;
        g.drawString(font, "§e池内地图 · 池" + (selectedPool + 1)
                        + (rot.poolCount() > 0 ? " " + rot.poolAt(selectedPool).displayName : ""),
                mapsX, y, ACCENT, false);
        y += 16;
        if (allMapIds.isEmpty()) {
            g.drawString(font, "§7暂无地图配置条目", mapsX + 4, y + 4, SharedGuiKit.TEXT_SECONDARY, false);
            y += ROW_H;
        } else {
            for (String id : allMapIds) {
                boolean on = selectedMaps.contains(id);
                boolean hover = SharedGuiKit.inBounds(mx, my, mapsX, y, mapsW - 8, ROW_H);
                g.fill(mapsX, y, mapsX + mapsW - 8, y + ROW_H - 2,
                        hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW);
                g.fill(mapsX + 4, y + 4, mapsX + 16, y + 16, on ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED);
                if (on) g.drawString(font, "§a✓", mapsX + 5, y + 5, 0xFFFFFFFF, false);
                MapVoteEntry me = settings().maps.get(id);
                boolean known = me != null;
                String label = (me != null && me.displayName != null && !me.displayName.isEmpty())
                        ? me.displayName + " §8(" + id + ")"
                        : id;
                if (!known) label = "§c" + label + " §8(未知)";
                g.drawString(font, label, mapsX + 22, y + 5, SharedGuiKit.TEXT_PRIMARY, false);
                mapHits.add(new RowHit(id, mapsX, y, mapsW - 8, ROW_H - 2));
                y += ROW_H;
            }
        }
        contentHeight = y - contentTop + (int) scrollOffset;
        g.disableScissor();

        int contentH = contentBot - contentTop;
        int maxScroll = Math.max(0, contentHeight - contentH);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        SharedGuiKit.drawScrollbar(g, mapsX + mapsW - 4, contentTop, contentH, scrollOffset, maxScroll, 3);

        // Footer buttons
        int fy = height - 46;
        addPoolBtn.setX(PAD);
        addPoolBtn.setY(fy);
        addPoolBtn.setWidth(70);
        removePoolBtn.setX(PAD + 76);
        removePoolBtn.setY(fy);
        removePoolBtn.setWidth(70);
        removePoolBtn.active = remoteEditable && rot.poolCount() > MapPoolRotationSettings.MIN_POOLS;
        addPoolBtn.active = remoteEditable && rot.poolCount() < MapPoolRotationSettings.MAX_POOLS;

        repartitionBtn.setX(width / 2 - 140);
        repartitionBtn.setY(height - 28);
        repartitionBtn.setWidth(90);
        clearBtn.setX(width / 2 - 40);
        clearBtn.setY(height - 28);
        clearBtn.setWidth(90);
        saveBtn.setX(width / 2 + 60);
        saveBtn.setY(height - 28);
        saveBtn.setWidth(80);

        backBtn.render(g, mx, my, delta);
        addPoolBtn.render(g, mx, my, delta);
        removePoolBtn.render(g, mx, my, delta);
        repartitionBtn.render(g, mx, my, delta);
        clearBtn.render(g, mx, my, delta);
        saveBtn.render(g, mx, my, delta);

        if (!remoteEditable) {
            g.drawString(font, Component.literal("§c只读：联机服务器中仅 OP 可修改"),
                    PAD, height - 12, 0xFF5555, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        for (EditBox box : nameFields) {
            if (box != null && box.mouseClicked(mx, my, btn)) {
                for (EditBox other : nameFields) {
                    if (other != null && other != box) other.setFocused(false);
                }
                box.setFocused(true);
                return true;
            }
        }
        if (super.mouseClicked(mx, my, btn)) return true;

        int contentTop = HEADER_H;
        int contentBot = height - FOOTER_H;
        int poolRowsTop = contentTop + 4 + 14;

        for (PoolHit hit : poolHits) {
            if (hit.y() + hit.h() < poolRowsTop || hit.y() >= contentBot) continue;
            if (SharedGuiKit.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())
                    && my >= poolRowsTop && my < contentBot) {
                if (hit.toggle()) {
                    if (!remoteEditable) { LiveConfigAccess.showDeniedMessage(); return true; }
                    MapPoolEntry p = rot().poolAt(hit.index());
                    p.enabled = !p.enabled;
                    return true;
                }
                writeSelectedToPool();
                selectedPool = hit.index();
                syncSelectedFromPool();
                return true;
            }
        }

        if (my >= contentTop && my < contentBot) {
            for (RowHit hit : mapHits) {
                if (SharedGuiKit.inBounds(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    if (!remoteEditable) { LiveConfigAccess.showDeniedMessage(); return true; }
                    if (selectedMaps.contains(hit.id())) selectedMaps.remove(hit.id());
                    else selectedMaps.add(hit.id());
                    return true;
                }
            }
            int mapsX = PAD + POOL_PANEL_W + 8;
            int mapsW = width - mapsX - PAD;
            int sbX = mapsX + mapsW - 4;
            if (mx >= sbX - 2 && mx <= sbX + 6) {
                draggingScroll = true;
                dragStartY = my;
                dragStartOff = scrollOffset;
                return true;
            }
            int poolSbX = PAD + POOL_PANEL_W - 3;
            if (mx >= poolSbX - 2 && mx <= poolSbX + 6 && my >= poolRowsTop) {
                draggingPoolScroll = true;
                dragStartY = my;
                dragStartOff = poolScrollOffset;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingScroll) {
            int contentTop = HEADER_H;
            int contentBot = height - FOOTER_H;
            int contentH = contentBot - contentTop;
            int maxScroll = Math.max(0, contentHeight - contentH);
            scrollOffset = Mth.clamp(dragStartOff + (dragStartY - my), 0, maxScroll);
            return true;
        }
        if (draggingPoolScroll) {
            int contentTop = HEADER_H;
            int contentBot = height - FOOTER_H;
            int poolRowsTop = contentTop + 4 + 14;
            int poolViewH = Math.max(20, contentBot - 2 - poolRowsTop);
            int maxScroll = Math.max(0, poolListHeight - poolViewH);
            poolScrollOffset = Mth.clamp(dragStartOff + (dragStartY - my), 0, maxScroll);
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingScroll = false;
        draggingPoolScroll = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int contentTop = HEADER_H;
        int contentBot = height - FOOTER_H;
        int poolW = POOL_PANEL_W;
        if (mx < PAD + poolW + 4) {
            int poolRowsTop = contentTop + 4 + 14;
            int poolViewH = Math.max(20, contentBot - 2 - poolRowsTop);
            int maxScroll = Math.max(0, poolListHeight - poolViewH);
            poolScrollOffset = Mth.clamp(poolScrollOffset - sy * 18, 0, maxScroll);
            return true;
        }
        int contentH = contentBot - contentTop;
        int maxScroll = Math.max(0, contentHeight - contentH);
        scrollOffset = Mth.clamp(scrollOffset - sy * 18, 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        for (EditBox box : nameFields) {
            if (box != null && box.isFocused() && box.keyPressed(key, scan, mod)) return true;
        }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        for (EditBox box : nameFields) {
            if (box != null && box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        return super.charTyped(ch, mod);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
