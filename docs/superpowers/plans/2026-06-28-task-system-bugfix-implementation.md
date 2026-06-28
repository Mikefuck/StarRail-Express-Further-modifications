# 任务系统 Bug 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 task system bugs: HUD display + sync, ModMenu input fields, layout overlap

**Architecture:** Fix `TaskInstance.toNbt()` serialization to include `type` field for SRE client sync; rewrite `TaskDetailPanel` from static rendering to widget-based panel with `EditBox` controls; adjust Y-coordinates in `MainConfigScreen` and `TaskListPanel` to eliminate overlapping.

**Tech Stack:** Fabric 1.21.1, Mixin, SRE 4.2.0 CCA sync, Minecraft GUI widgets

## Global Constraints

- Must not add new HUD elements or modify SRE source code
- Must not add new config fields or change existing API
- All GUI classes live under `client/gui/` package
- ConfigManager save path is `habitrain_core.json`
- Post-build: copy JAR to `D:\Backup\mc mod\临时\`

---

### Task 1: Fix TaskInstance sync — add `type` field to toNbt()

**Files:**
- Modify: `src/main/java/com/habitrain/core/api/TaskInstance.java:99-109`

**Interfaces:**
- Consumes: `TaskEnumHelper.getCustom()` (returns `SREPlayerTaskComponent.Task.CUSTOM` or null)
- Produces: `toNbt()` now writes `type` ordinal so SRE `readFromSyncNbt()` can deserialize CUSTOM tasks on the client

- [ ] **Step 1: Read TaskInstance.java to verify current state**

```bash
cat src/main/java/com/habitrain/core/api/TaskInstance.java | head -120
```

- [ ] **Step 2: Add `type` to toNbt()**

Modify `toNbt()` — add the `type` field using `TaskEnumHelper` before the return:

Edit `api/TaskInstance.java`, replace:

```java
    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("customId", definition.getFullId());
        nbt.putString("customName", definition.getDisplayName());
        nbt.putBoolean("fulfilled", this.fulfilled);
        nbt.putBoolean("failed", this.failed);
        nbt.putInt("progress", this.progress);
        nbt.putInt("maxProgress", this.maxProgress);
        nbt.putInt("elapsedTicks", this.elapsedTicks);
        return nbt;
    }
```

With:

```java
    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("customId", definition.getFullId());
        nbt.putString("customName", definition.getDisplayName());
        nbt.putBoolean("fulfilled", this.fulfilled);
        nbt.putBoolean("failed", this.failed);
        nbt.putInt("progress", this.progress);
        nbt.putInt("maxProgress", this.maxProgress);
        nbt.putInt("elapsedTicks", this.elapsedTicks);
        // ★ 关键修复：写入 type 字段，使 SRE 客户端反序列化能正确还原 CUSTOM 任务
        var customType = com.habitrain.core.game.sre.TaskEnumHelper.getCustom();
        if (customType != null) {
            nbt.putInt("type", customType.ordinal());
        }
        return nbt;
    }
```

- [ ] **Step 3: Verify toNbt() compiles by checking imports**

`TaskEnumHelper` is in `com.habitrain.core.game.sre` — no new import needed since we use FQN inline.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/api/TaskInstance.java
git commit -m "fix: add type field to TaskInstance.toNbt() for SRE CUSTOM task sync"
```

---

### Task 2: Fix center notification — send correct display name

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java:308-320`

**Interfaces:**
- Consumes: `TaskDefinition.getDisplayName()` returns actual display name (e.g. "摸猫猫")
- Produces: Player receives correct chat notification instead of SRE's `task_`-prefixed message

- [ ] **Step 1: Read createAndTrackDlcTask()**

Read `GenerateTaskMixin.java` to find the `createAndTrackDlcTask` method (around line 308).

- [ ] **Step 2: Add system message with correct display name**

After `ActiveTaskPayload.sendToPlayer(...)` and before `return instance`, add:

Edit `GenerateTaskMixin.java`, replace:

```java
        if (player instanceof ServerPlayer sp) {
            ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
        }

        return instance;
```

With:

```java
        if (player instanceof ServerPlayer sp) {
            ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
            // ★ 发送正确名称的中心提示，替代 SRE 自带的 task_ 前缀通知
            sp.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                    "§a✦ 新任务已派发: §f" + def.getDisplayName()
                ),
                false
            );
        }

        return instance;
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java
git commit -m "fix: send correct display name notification for DLC tasks"
```

---

### Task 3: Build verify after sync fixes

- [ ] **Step 1: Build the mod**

```bash
cd D:/Backup/mc\ mod/哈比列车api
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL` in output. The JAR is at `build/libs/habitrain_core-*.jar`.

- [ ] **Step 2: Copy JAR to temp directory**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 3: Commit build artifacts**

Not needed — don't commit build outputs.

---

### Task 4: Rewrite TaskDetailPanel with EditBox widgets

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/TaskDetailPanel.java` — full rewrite
- Modify: `src/main/java/com/habitrain/core/client/gui/MainConfigScreen.java` — adapt to non-static TaskDetailPanel

**Interfaces:**
- Consumes: `TaskDefinition`, `ConfigManager`, `TaskConfigEntry`, `MainConfigScreen` (for widget registration)
- Produces: Functional EditBox inputs for gold/emotion/weight/maps; corrected color picker; proper save/reset

- [ ] **Step 1: Rewrite TaskDetailPanel.java**

Replace the entire file. New class is non-static, holds EditBox widgets, and has render/click/key methods:

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 任务详情滑入面板 — 带 EditBox 控件的非静态面板。
 * 由 MainConfigScreen 在打开任务详情时创建并管理生命周期。
 */
public class TaskDetailPanel {

    private static final int PANEL_W = 320;
    private static final int PAD = 10;
    private static final int ROW_H = 22;
    private static final int LABEL_W = 72;
    private static final int CONTENT_W = PANEL_W - PAD * 2 - LABEL_W - 6;

    private static final int[] COLOR_PRESETS = {
        0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00,
        0xFF0000FF, 0xFF8B00FF, 0xFFFF00FF, 0xFF00FFFF,
        0xFFFFC0CB, 0xFFFFA500, 0xFFC0C0C0, 0xFFFFFFFF,
        0xFFFF6B6B, 0xFFFFD700, 0xFF7CFC00, 0xFF00FA9A,
        0xFF6020F0, 0xFFFF1493, 0xFF00CED1, 0xFFFF8C00
    };
    private static final String[] COLOR_NAMES = {
        "红色","橙色","黄色","绿色","蓝色","紫色","品红色","青色",
        "粉色","琥珀色","银色","白色","珊瑚色","金色","草绿色","碧绿色",
        "紫罗兰","深粉色","深蓝色","亮橙色"
    };
    private static final String[] MAP_MODES = {"全部地图", "仅以下地图", "排除以下地图"};

    // ---- 面板状态 ----
    private final TaskDefinition def;
    private final Runnable onClose;

    // ---- 渲染/交互用的缓存值 ----
    private Color currentColor;
    private float outlineWidth;
    private int mapFilterMode;
    private boolean enabled;

    // ---- EditBox 控件 ----
    private final EditBox goldBox;
    private final EditBox emotionBox;
    private final EditBox weightBox;
    private final EditBox mapsBox;
    private final List<EditBox> allBoxes = new ArrayList<>();

    // ---- 按钮矩形区域（用于点击检测） ----
    private int panelX, panelY;
    private int colorRectX, colorRectY;
    private int outlineDecX, outlineIncX;
    private int mapModeX;
    private int saveBtnX, resetBtnX, backBtnX;
    private int btnY;

    public TaskDetailPanel(MainConfigScreen screen, TaskDefinition def, Runnable onClose) {
        this.def = def;
        this.onClose = onClose;
        Font font = Minecraft.getInstance().font;

        // 加载当前配置
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        this.enabled = cfg == null || cfg.enabled;
        this.currentColor = cfg != null ? cfg.getColor()
            : (def.getInstinctColor() != null ? def.getInstinctColor() : new Color(200, 200, 200, 180));
        this.outlineWidth = cfg != null ? cfg.outlineWidth : 4.0f;
        this.mapFilterMode = cfg != null ? cfg.mapFilterMode : 0;
        String mapsStr = (cfg != null && cfg.enabledMaps != null) ? String.join(",", cfg.enabledMaps) : "";

        // 创建 EditBox
        int boxH = 14;
        int panelX = screen.width - PANEL_W;

        goldBox = new EditBox(font, panelX + PAD + LABEL_W, 102, CONTENT_W, boxH, Component.literal(""));
        goldBox.setMaxLength(8);
        goldBox.setValue(cfg != null && cfg.goldReward >= 0 ? String.valueOf(cfg.goldReward) : "");
        goldBox.setFilter(s -> s.isEmpty() || s.matches("-?\\d*"));
        allBoxes.add(goldBox);

        emotionBox = new EditBox(font, panelX + PAD + LABEL_W, 124, CONTENT_W, boxH, Component.literal(""));
        emotionBox.setMaxLength(8);
        emotionBox.setValue(cfg != null && cfg.emotionReward >= 0f ? String.format("%.1f", cfg.emotionReward) : "");
        emotionBox.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
        allBoxes.add(emotionBox);

        weightBox = new EditBox(font, panelX + PAD + LABEL_W, 146, CONTENT_W, boxH, Component.literal(""));
        weightBox.setMaxLength(8);
        weightBox.setValue(cfg != null && cfg.refreshWeight >= 0f ? String.format("%.1f", cfg.refreshWeight) : "");
        weightBox.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
        allBoxes.add(weightBox);

        mapsBox = new EditBox(font, panelX + PAD + LABEL_W, 190, CONTENT_W, boxH, Component.literal(""));
        mapsBox.setMaxLength(256);
        mapsBox.setValue(mapsStr);
        mapsBox.setHint(Component.literal("逗号分隔地图名"));
        allBoxes.add(mapsBox);

        // 注册到 screen（使键盘/鼠标事件能路由到 EditBox）
        screen.registerDetailWidgets(allBoxes);
    }

    /** 注销 EditBox 控件 */
    public void dispose(MainConfigScreen screen) {
        screen.unregisterDetailWidgets(allBoxes);
    }

    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaW, int areaH) {
        panelX = areaW - PANEL_W;
        panelY = areaY;

        // 半透明遮罩
        g.fill(areaX, areaY, panelX, areaH, 0x88000000);
        // 面板背景
        g.fill(panelX, panelY, areaW, areaH, 0xFF2D2D3F);
        g.fill(panelX, panelY, panelX + 1, areaH, 0xFF555577);

        // 标题
        g.drawString(font, Component.literal("§l← 返回"), panelX + PAD, panelY + 8, 0xFFFFFF, false);
        String title = def.getDisplayName() + " §7(" + def.getFullId() + ")";
        g.drawString(font, Component.literal(title),
                panelX + PAD + font.width("← 返回") + 14, panelY + 8, 0xDDDDDD, false);

        int y = panelY + 36;

        // 1. 启用/禁用
        g.drawString(font, Component.literal("§7状态:"), panelX + PAD, y, 0, false);
        String status = enabled ? "§a✔ 已启用" : "§c✖ 已禁用";
        g.drawString(font, Component.literal(status), panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 2. 颜色选择
        g.drawString(font, Component.literal("§7颜色:"), panelX + PAD, y, 0, false);
        colorRectX = panelX + PAD + LABEL_W;
        colorRectY = y + 3;
        g.fill(colorRectX, colorRectY, colorRectX + 18, colorRectY + 18, currentColor.getRGB());
        String colorName = getColorName(currentColor);
        g.drawString(font, Component.literal("§f" + colorName + " §7[点击切换]"),
                colorRectX + 22, y, 0, false);
        y += ROW_H;

        // 3. 描边粗细
        g.drawString(font, Component.literal("§7描边:"), panelX + PAD, y, 0, false);
        outlineDecX = panelX + PAD + LABEL_W;
        String owStr = String.format("%.1f", outlineWidth);
        outlineIncX = outlineDecX + font.width("[-] " + owStr + " ");
        g.drawString(font, Component.literal("§f[-] §e" + owStr + " §f[+]"),
                outlineDecX, y, 0, false);
        y += ROW_H;

        // 4. 金币奖励
        g.drawString(font, Component.literal("§7金币:"), panelX + PAD, y, 0, false);
        goldBox.setY(y);
        goldBox.render(g, 0, 0, 0);
        y += ROW_H;

        // 5. 情绪奖励
        g.drawString(font, Component.literal("§7情绪:"), panelX + PAD, y, 0, false);
        emotionBox.setY(y);
        emotionBox.render(g, 0, 0, 0);
        y += ROW_H;

        // 6. 刷新权重
        g.drawString(font, Component.literal("§7权重:"), panelX + PAD, y, 0, false);
        weightBox.setY(y);
        weightBox.render(g, 0, 0, 0);
        y += ROW_H;

        // 7. 地图过滤模式
        g.drawString(font, Component.literal("§7地图:"), panelX + PAD, y, 0, false);
        mapModeX = panelX + PAD + LABEL_W;
        g.drawString(font, Component.literal("§f[ " + MAP_MODES[mapFilterMode] + " ] §7[点击切换]"),
                mapModeX, y, 0, false);
        y += ROW_H;

        // 8. 地图列表
        g.drawString(font, Component.literal("§7地图列:"), panelX + PAD, y, 0, false);
        mapsBox.setY(y);
        mapsBox.render(g, 0, 0, 0);
        y += ROW_H + 8;

        // 9. 按钮
        btnY = Math.max(y, panelY + areaH - 50);
        saveBtnX = panelX + PAD + 10;
        resetBtnX = saveBtnX + font.width("§a[保存]  ");
        backBtnX = resetBtnX + font.width("§7[重置]  ");
        g.drawString(font, Component.literal("§a[保存]  §7[重置]  §c[返回]"),
                panelX + PAD + 10, btnY, 0, false);
    }

    /** 处理鼠标点击 */
    public boolean mouseClicked(int mx, int my, int btn) {
        Font font = Minecraft.getInstance().font;

        // 先给 EditBox 处理
        for (EditBox box : allBoxes) {
            if (box.mouseClicked(mx, my, btn)) return true;
        }

        // ← 返回
        if (mx >= panelX + PAD && mx <= panelX + PAD + font.width("← 返回") + 10
                && my >= panelY + 8 && my <= panelY + 30) {
            onClose.run();
            return true;
        }

        // 颜色切换
        if (mx >= colorRectX && mx <= colorRectX + 120
                && my >= colorRectY && my <= colorRectY + ROW_H) {
            int nextIdx = (findColorIndex(currentColor) + 1) % COLOR_PRESETS.length;
            currentColor = new Color(COLOR_PRESETS[nextIdx]);
            saveColor();
            return true;
        }

        // 描边 [-]
        if (mx >= outlineDecX && mx <= outlineDecX + 24
                && my >= panelY + 80 && my <= panelY + 102) {
            outlineWidth = Math.max(1.0f, outlineWidth - 0.5f);
            saveOutlineWidth();
            return true;
        }

        // 描边 [+]
        if (mx >= outlineIncX && mx <= outlineIncX + 24
                && my >= panelY + 80 && my <= panelY + 102) {
            outlineWidth = Math.min(10.0f, outlineWidth + 0.5f);
            saveOutlineWidth();
            return true;
        }

        // 地图模式切换
        if (mx >= mapModeX && mx <= mapModeX + font.width("[ 全部地图 ]") + 40
                && my >= panelY + 168 && my <= panelY + 190) {
            mapFilterMode = (mapFilterMode + 1) % 3;
            saveMapFilter();
            return true;
        }

        // 保存
        if (mx >= saveBtnX && mx <= saveBtnX + font.width("[保存]") + 10
                && my >= btnY && my <= btnY + 16) {
            saveAll();
            return true;
        }

        // 重置
        if (mx >= resetBtnX && mx <= resetBtnX + font.width("[重置]") + 10
                && my >= btnY && my <= btnY + 16) {
            resetAll();
            return true;
        }

        return false;
    }

    /** 处理键盘按键 */
    public boolean keyPressed(int key, int sc, int mod) {
        for (EditBox box : allBoxes) {
            if (box.isFocused() && box.keyPressed(key, sc, mod)) return true;
        }
        return false;
    }

    /** 处理字符输入 */
    public boolean charTyped(char ch, int mod) {
        for (EditBox box : allBoxes) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        return false;
    }

    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isEnabled() { return enabled; }

    // ========== 持久化 ==========

    private void saveColor() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.instinctColor = currentColor.getRGB();
        ConfigManager.getInstance().save();
    }

    private void saveOutlineWidth() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.outlineWidth = outlineWidth;
        ConfigManager.getInstance().save();
    }

    private void saveMapFilter() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.mapFilterMode = mapFilterMode;
        ConfigManager.getInstance().save();
    }

    private void saveAll() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.enabled = enabled;
        cfg.instinctColor = currentColor.getRGB();
        cfg.outlineWidth = outlineWidth;
        cfg.mapFilterMode = mapFilterMode;
        cfg.goldReward = parseOptionalInt(goldBox.getValue());
        cfg.emotionReward = parseOptionalFloat(emotionBox.getValue());
        cfg.refreshWeight = parseOptionalFloat(weightBox.getValue());
        String raw = mapsBox.getValue().trim();
        cfg.enabledMaps = raw.isEmpty() ? List.of() : List.of(raw.split("\\s*,\\s*"));
        ConfigManager.getInstance().save();
    }

    private void resetAll() {
        ConfigManager.getInstance().setTaskConfig(def.getFullId(), new TaskConfigEntry(true));
        // 重载 EditBox
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        goldBox.setValue("");
        emotionBox.setValue("");
        weightBox.setValue("");
        mapsBox.setValue("");
        currentColor = def.getInstinctColor() != null ? def.getInstinctColor() : new Color(200, 200, 200, 180);
        outlineWidth = 4.0f;
        mapFilterMode = 0;
        enabled = true;
    }

    private TaskConfigEntry getOrCreateConfig() {
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg == null) {
            cfg = new TaskConfigEntry();
            ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
        }
        return cfg;
    }

    // ========== 工具 ==========

    private static int parseOptionalInt(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static float parseOptionalFloat(String s) {
        if (s == null || s.trim().isEmpty()) return -1f;
        try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return -1f; }
    }

    private static int findColorIndex(Color c) {
        int rgb = c.getRGB() & 0x00FFFFFF;
        for (int i = 0; i < COLOR_PRESETS.length; i++) {
            if ((COLOR_PRESETS[i] & 0x00FFFFFF) == rgb) return i;
        }
        return 0;
    }

    private static String getColorName(Color c) {
        int idx = findColorIndex(c);
        return idx < COLOR_NAMES.length ? COLOR_NAMES[idx] : "自定义";
    }
}
```

- [ ] **Step 2: Read MainConfigScreen.java to plan integration points**

```bash
cat src/main/java/com/habitrain/core/client/gui/MainConfigScreen.java
```

- [ ] **Step 3: Add widget lifecycle methods to MainConfigScreen**

Add these methods to `MainConfigScreen`:

```java
    // ====== TaskDetailPanel widget lifecycle ======
    private final List<EditBox> detailBoxes = new java.util.ArrayList<>();

    public void registerDetailWidgets(List<EditBox> boxes) {
        detailBoxes.clear();
        for (EditBox box : boxes) {
            detailBoxes.add(box);
            addRenderableWidget(box);
        }
    }

    public void unregisterDetailWidgets(List<EditBox> boxes) {
        for (EditBox box : boxes) {
            children.remove(box);
            renderables.remove(box);
        }
        detailBoxes.clear();
    }
```

Add import for `EditBox` and `java.util.List` if not present.

- [ ] **Step 4: Adapt openTaskDetail/closeTaskDetail**

Replace `openTaskDetail()` and `closeTaskDetail()` in MainConfigScreen:

```java
    private TaskDetailPanel detailPanel = null;

    private void openTaskDetail(TaskDefinition def) {
        if (detailPanel != null) {
            detailPanel.dispose(this);
            detailPanel = null;
        }
        detailPanel = new TaskDetailPanel(this, def, this::closeTaskDetail);
    }

    private void closeTaskDetail() {
        if (detailPanel != null) {
            detailPanel.dispose(this);
            detailPanel = null;
        }
    }
```

Remove the old `editingTask`, `currentColor`, `currentOutlineWidth`, `currentMapFilter`, `mapsText` fields since they're now managed by `TaskDetailPanel`.

- [ ] **Step 5: Replace detail rendering in MainConfigScreen.render()**

In `render()` method (around line 126-137), replace:

```java
                if (editingTask != null) {
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset,
                            SIDEBAR_W, 0, width, height, HEADER_H, mx, my, null);
                    TaskDetailPanel.render(g, font, editingTask,
                            currentColor, currentOutlineWidth, currentMapFilter, mapsText,
                            SIDEBAR_W, 0, width, height);
                } else {
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset,
                            SIDEBAR_W, 0, width, height, HEADER_H, mx, my, this::openTaskDetail);
                }
```

With:

```java
                if (detailPanel != null) {
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset,
                            SIDEBAR_W, 0, width, height, HEADER_H, mx, my, null);
                    detailPanel.render(g, font, SIDEBAR_W, 0, width, height);
                } else {
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset,
                            SIDEBAR_W, 0, width, height, HEADER_H, mx, my, this::openTaskDetail);
                }
```

- [ ] **Step 6: Replace detail click handling in mouseClicked()**

Replace the detail panel click handling section (around line 172-179):

```java
        // 详情面板开启时，优先处理
        if (editingTask != null && mx > width - DETAIL_PANEL_W) {
            if (TaskDetailPanel.mouseClicked(this, (int) mx, (int) my, btn, editingTask,
                    currentColor, currentOutlineWidth, currentMapFilter, mapsText,
                    SIDEBAR_W, 0, width, height, this::closeTaskDetail)) {
                return true;
            }
        }
```

With:

```java
        // 详情面板开启时，优先处理
        if (detailPanel != null && mx > width - DETAIL_PANEL_W) {
            if (detailPanel.mouseClicked((int) mx, (int) my, btn)) {
                return true;
            }
        }
```

- [ ] **Step 7: Forward keyboard events to detail panel**

Add to `keyPressed()` and `charTyped()`:

In `keyPressed()`:
```java
        if (detailPanel != null && detailPanel.keyPressed(key, sc, mod)) return true;
```

In `charTyped()`:
```java
        if (detailPanel != null && detailPanel.charTyped(ch, mod)) return true;
```

- [ ] **Step 8: Update sidebar click to reset detail panel**

In `mouseClicked()`, sidebar click handler (around line 189-190):
```java
    editingTask = null;
```
Change to:
```java
    if (detailPanel != null) { detailPanel.dispose(this); detailPanel = null; }
```

- [ ] **Step 9: Fix scroll handling for detail mode**

Replace the scroll handler:

```java
        if (editingTask != null) {
            return TaskDetailPanel.mouseScrolled(vertical);
        }
```

With:

```java
        if (detailPanel != null) {
            return true; // absorb scroll in detail mode
        }
```

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/TaskDetailPanel.java src/main/java/com/habitrain/core/client/gui/MainConfigScreen.java
git commit -m "fix: rewrite TaskDetailPanel with EditBox widgets, adapt MainConfigScreen"
```

---

### Task 5: Fix layout overlap in TaskListPanel and MainConfigScreen

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/TaskListPanel.java`
- Modify: `src/main/java/com/habitrain/core/client/gui/MainConfigScreen.java`

- [ ] **Step 1: Fix task list Y offsets in TaskListPanel.render()**

In `render()`, find and change the `listY` and `endY` calculations:

```java
        int listY = areaY + headerH + 24;  // was: + 2 (跳过搜索框区域)
        int visibleH = areaH - headerH - 36;  // was: - 6 (预留底部按钮空间)
```

Also update the empty message Y calculation to account for the new offset:
```java
                    contentX + contentW / 2 - font.width(msg) / 2, listY + 30, 0, false);
```

- [ ] **Step 2: Fix visibleH in TaskListPanel.mouseClicked()**

Same adjustment in `mouseClicked()`:
```java
        int visibleH = areaH - headerH - 36;  // was: - 6
```

- [ ] **Step 3: Fix search box position in MainConfigScreen.init()**

Change the search box Y:
```java
        int searchY = HEADER_H + 2;  // was: HEADER_H + 4
```

- [ ] **Step 4: Fix close button position in MainConfigScreen.init()**

Change the close button bounds:
```java
        ).bounds(width - 70, height - 26, 60, 16).build());  // was: height - 22
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/TaskListPanel.java src/main/java/com/habitrain/core/client/gui/MainConfigScreen.java
git commit -m "fix: correct task list Y offsets to prevent overlap with search box and close button"
```

---

### Task 6: Final build and verification

- [ ] **Step 1: Build the core mod**

```bash
cd D:/Backup/mc\ mod/哈比列车api
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`. JAR at `build/libs/habitrain_core-*.jar`.

- [ ] **Step 2: Copy core JAR to temp directory**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 3: Build companion mod（哈比列车更多修改）**

```bash
cd D:/Backup/mc\ mod/哈比列车更多修改
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Copy companion JAR to temp directory**

```bash
cp build/libs/habitrain_more_tasks-*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 5: Commit all remaining changes**

```bash
cd D:/Backup/mc\ mod/哈比列车api
git add -A
git commit -m "fix: task system bugfixes - sync, GUI inputs, layout"
```
