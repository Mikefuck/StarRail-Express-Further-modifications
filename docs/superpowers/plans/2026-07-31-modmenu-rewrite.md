# ModMenu 配置界面重写实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从 0 重写 `habitrain_core` 的 ModMenu 配置界面：左侧分类树 + 内容区 + 底部保存栏 + 页面栈，功能与旧版 6-Tab 界面完全等价，并保留所有 ConfigManager 数据模型与服务端同步链路不动。

**Architecture:** 单 `ConfigRootScreen`（仍实现 ModMenu 的 `ConfigRootScreen::new`）持有左侧分类树与页面栈；所有页面实现统一 `ConfigPage` 接口，通过 `ConfigContext` 接口访问根（markDirty/saveNow/pushPage/popPage/权限/提示）；通用控件抽到 `config/widget/` 子包；底部 `SaveBar` 提供「有未保存的修改」指示 + 保存 + 返回。旧 GUI 类在最后集成任务统一删除，旧文件在整个重写期间保留作为行为参考。

**Tech Stack:** Fabric + Java 21 + Minecraft 1.21.1（官方 Mojang mappings）、ModMenu、SRE、Loom。旧代码行内绘制（无 widget 体系），新代码沿用同一风格并抽公共控件。

## Global Constraints

- **项目协议（强制）**：每次任务结束必须 `./gradlew clean build`，从本轮 `build/libs/` 确认唯一无 classifier 主 JAR `habitrain_core-2.0.1.jar`，复制到 `D:\Backup\mc mod\临时\`，核对文件名/字节长度/SHA-256；失败必须如实记录，不得用旧 JAR 顶替。
- **架构记忆（强制）**：动手前读 `.claude/memory/mod-architecture.md`；完成后必须给 `.claude/memory/maintenance-log.md` 追加本会话条目，并在 `mod-architecture.md` 第 11 节更新 GUI 类名清单。
- **工作区边界**：保留本项目既有的 staged/unstaged/deleted/untracked 修改（如 `docs/` 下大量删除、`src/test/` 未跟踪、`build.gradle` 改动等），不得 reset/clean/覆盖/错误归属。
- **禁止访问** `D:\Backup\mc mod\backup\`。
- **底层复用、不改动**：`ConfigManager`、`ConfigRepository`、`ConfigStore`、`ConfigSync`、`MinigameEnforcement`、全部 `config/*Entry/*Settings` 数据模型、`ConfigQueryService`、`SharedGuiKit`、`SharedGuiConstants`、`LiveConfigAccess`。唯一例外见 Task 1（给 ConfigStore/ConfigManager 各加一个只读 `isDirty()`，不改语义）。
- **保留的对外集成点**（重写后仍必须存在，签名不变）：
  - `ModMenuIntegration.getModConfigScreenFactory()` → `ConfigRootScreen::new`
  - `ConfigRootScreen(Screen parent)` 构造
  - `ConfigRootScreen.openVote(Screen)` 静态工厂
  - `ConfigRootScreen.refreshRoleOverrideTab()`（`RoleOverrideRefreshDispatcher.java:35` 调用）
- **新代码位置**：全部在 `com.habitrain.core.client.gui.config`（页面）与 `.config.widget`（控件）包内；旧 GUI 类在 Task 13 删除。
- **旧文件是行为参考**：Task 13 之前旧 GUI 类**不得删除**；各页面的移植任务引用旧文件的具体行区间。
- **验证方式说明**：Minecraft 客户端 GUI 无法在 Gradle `test` 任务里单元测试（需客户端运行时）。因此每个任务的验证 = `./gradlew compileJava`（快反馈）+ 任务末 `./gradlew clean build` + JAR 复制（协议），最终以 Task 14 的游戏内人工清单为准。这是本项目 GUI 工作的既有现实。

---

## 文件结构

| 文件 | 职责 | 任务 |
|---|---|---|
| `config/ConfigPage.java` | 页面契约接口（新建） | 1 |
| `config/ConfigContext.java` | 页面→根的访问接口（新建） | 1 |
| `config/ConfigStore.java` + `config/ConfigManager.java` | 各加只读 `isDirty()`（最小改动） | 1 |
| `config/widget/ScrollPane.java` | 统一滚动容器（滚轮/拖拽/滚动条，真实高度夹紧） | 2 |
| `config/widget/ToggleButton.java` | 启用/停用开关按钮 | 2 |
| `config/widget/NumberField.java` | 数字输入框 + 应用按钮 | 2 |
| `config/widget/SliderRow.java` | DLC 概率滑块（渐变/刻度/拖拽） | 2 |
| `config/widget/ColorCyclePicker.java` | 20 色循环 + 描边 ± | 3 |
| `config/widget/MapFilterField.java` | 地图过滤模式 + 地图列表输入 | 3 |
| `config/widget/SaveBar.java` | 底部保存栏（指示器 + 保存/返回） | 3 |
| `config/TaskListView.java` | 任务列表页（Scope.BUILTIN/BLACKOUT/DLC 复用） | 4 |
| `config/TaskEditPage.java` | 任务详情子页面（栈压入） | 4 |
| `config/TaskGlobalPage.java` | 任务全局参数（DLC 占比 + 小游戏总开关） | 5 |
| `config/GameplayGlobalPage.java` | 全局玩法参数（警长/临时电源/刀耐久） | 5 |
| `config/MinigamesPage.java` | 小游戏列表页 | 6 |
| `config/MinigameEditPage.java` | 小游戏详情子页面 | 6 |
| `config/VoteSettingsPage.java` | 投票设置页 | 7 |
| `config/ModeAllowedMapsPage.java` | 模式可选地图子页面 | 7 |
| `config/MapPoolPage.java` | 地图池轮换页 | 8 |
| `config/EnvProfileEditor.java` | 环境档案编辑控件（共享） | 9 |
| `config/LobbyEnvPage.java` | 大厅环境页 | 9 |
| `config/RainEnvPage.java` | 动态雨页 | 9 |
| `config/MatchEnvPage.java` | 对局环境页 | 10 |
| `config/PostMatchEnvPage.java` | 局后时间页 | 10 |
| `config/RoleOverridePage.java` | 角色覆盖页 | 11 |
| `config/ShaderWhitelistPage.java` | 光影白名单页 | 12 |
| `config/ConfigRootScreen.java` | **重写**：分类树 + 页面栈 + SaveBar + 事件分发 | 13 |
| 删除：`config/ConfigRootScreen.java` 旧版、`config/TaskTabScreen.java`、`config/MinigameTabScreen.java`、`config/GlobalTabScreen.java`、`config/VoteTabScreen.java`、`config/EnvironmentTabScreen.java`、`config/RoleOverrideTabScreen.java`、`config/MapPoolEditorScreen.java`、`config/MinigameEditScreen.java`、`config/ModeAllowedMapsScreen.java`、`client/gui/TaskEditScreen.java`、`client/gui/ShaderWhitelistScreen.java`、`client/gui/TaskColorPicker.java`、`client/gui/TaskSaveController.java`、`client/gui/TaskMapFilterEditor.java` | 旧 GUI 移除 | 13 |

---

### Task 1: 页面契约接口 + 只读 dirty 访问

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/config/ConfigPage.java`
- Create: `src/main/java/com/habitrain/core/client/gui/config/ConfigContext.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigStore.java:22`（加方法）
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java:67`（加方法）

**Interfaces:**
- Produces: `ConfigPage`（所有后续页面实现）、`ConfigContext`（所有页面经它访问根）、`ConfigManager.getInstance().isDirty()` / `ConfigStore.isDirty()`。

- [ ] **Step 1: 建立编译基线**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL（验证改动前基线可编译；若失败先停下报告 Mike，不要继续）。

- [ ] **Step 2: 写 `ConfigPage.java`**

```java
package com.habitrain.core.client.gui.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 配置页统一契约。所有 ModMenu 配置页面实现此接口；
 * 由 ConfigRootScreen 负责事件分发、页面栈与底部保存栏。
 */
public interface ConfigPage {

    /** 页面标题（顶部面包屑与左侧导航使用）。 */
    Component title();

    /** 进入本页（首次选中/出栈后返回）。 */
    default void onEnter() {}

    /** 离开本页（切页/出栈/关闭面板）。用于刷未提交输入，等价旧 flushPendingFields/flushFocusedFields。 */
    default void onLeave() {}

    /** 根界面按下底部「保存」时调用。有未提交文本字段的页面在此提交，随后根调用 ConfigManager.save()。 */
    default void onSaveRequested() {}

    void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h);

    default boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) { return false; }
    default boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return false; }
    default boolean mouseReleased(double mx, double my, int btn, int x, int y, int w, int h) { return false; }
    default boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return false; }
    default boolean keyPressed(int key, int scan, int mod) { return false; }
    default boolean charTyped(char ch, int mod) { return false; }
}
```

- [ ] **Step 3: 写 `ConfigContext.java`**

```java
package com.habitrain.core.client.gui.config;

import net.minecraft.client.gui.Font;

/**
 * 页面 → ConfigRootScreen 的访问接口。页面只依赖此接口，不依赖具体根实现，
 * 从而可在根重写之前独立编译。
 */
public interface ConfigContext {

    Font font();

    /** 联机服务器中仅 OP 可修改（沿用 LiveConfigAccess.canEditRemoteConfigs）。 */
    boolean editable();

    /** 是否有未落盘修改（透传 ConfigManager.isDirty()）。 */
    boolean isDirty();

    /** 标记有修改（写入配置层 dirty 标记）。等价 ConfigManager.getInstance().markEnvironmentDirty()。 */
    void markDirty();

    /** 立即落盘 + 同步服务器 + 清 dirty（等价旧 saveConfigNow / ConfigManager.save()）。 */
    void saveNow();

    /** 压入子页面（任务详情等），根切换到栈顶并显示面包屑。 */
    void pushPage(ConfigPage page);

    /** 出栈返回上一页。 */
    void popPage();

    /** 返回：栈非空则 popPage，否则关闭面板回 ModMenu。 */
    void requestBack();

    /** 顶部 toast 提示。 */
    void toast(String message);
}
```

- [ ] **Step 4: 给 ConfigStore 加只读 isDirty()**

在 `ConfigStore.java` 的 `markDirty()` 方法（第 34 行）后插入：

```java
    public boolean isDirty() {
        return dirty;
    }
```

- [ ] **Step 5: 给 ConfigManager 加只读 isDirty()**

在 `ConfigManager.java` 的 `save()` 方法（第 66 行）后插入：

```java
    public boolean isDirty() {
        return store.isDirty();
    }
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。若成功则跑 `./gradlew clean build`、复制 `build/libs/habitrain_core-2.0.1.jar` 到 `D:\Backup\mc mod\临时\` 并核对 SHA-256（后续任务同，不再重复描述细节）。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/ConfigPage.java \
        src/main/java/com/habitrain/core/client/gui/config/ConfigContext.java \
        src/main/java/com/habitrain/core/config/ConfigStore.java \
        src/main/java/com/habitrain/core/config/ConfigManager.java
git commit -m "feat(modmenu): config page contracts and read-only isDirty accessor"
```

---

### Task 2: 核心控件（ScrollPane / ToggleButton / NumberField / SliderRow）

**Files:**
- Create: `config/widget/ScrollPane.java`、`ToggleButton.java`、`NumberField.java`、`SliderRow.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext` 无关；用 `SharedGuiKit`、`SharedGuiConstants`、`LiveConfigAccess`、Minecraft `EditBox`/`Font`/`GuiGraphics`/`Mth`。
- Produces: `ScrollPane`、`ToggleButton`、`NumberField`、`SliderRow`（后续所有页面用）。

- [ ] **Step 1: `ScrollPane.java`**

```java
package com.habitrain.core.client.gui.config.widget;

import com.habitrain.core.client.gui.config.SharedGuiKit;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 统一滚动容器：滚轮 + 拖拽 + 滚动条。按真实内容高度夹紧
 * （修复旧版拖拽/滚轮上限 10000 与滚动条实际高度不一致的 quirk）。
 */
public final class ScrollPane {
    private double scroll = 0;
    private boolean dragging = false;
    private double dragStartY = 0;
    private double dragStartScroll = 0;

    public double getScroll() { return scroll; }
    public void setScroll(double s) { scroll = Math.max(0, s); }

    public int maxScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - viewportHeight);
    }

    public void clamp(int contentHeight, int viewportHeight) {
        scroll = Mth.clamp(scroll, 0, maxScroll(contentHeight, viewportHeight));
    }

    /** 在内容区空白处按下即开始拖拽。仅在页面所有控件 miss 后调用。 */
    public boolean mouseClicked(double mx, double my, int y, int viewportHeight) {
        if (my >= y && my < y + viewportHeight) {
            dragging = true;
            dragStartY = my;
            dragStartScroll = scroll;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double my, int contentHeight, int viewportHeight) {
        if (!dragging) return false;
        scroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, maxScroll(contentHeight, viewportHeight));
        return true;
    }

    public boolean mouseReleased() {
        if (dragging) { dragging = false; return true; }
        return false;
    }

    public boolean mouseScrolled(double sy, int contentHeight, int viewportHeight) {
        scroll = Mth.clamp(scroll - sy * 18, 0, maxScroll(contentHeight, viewportHeight));
        return true;
    }

    public void drawScrollbar(GuiGraphics g, int x, int y, int viewportHeight, int contentHeight, int trackW) {
        SharedGuiKit.drawScrollbar(g, x, y, viewportHeight, scroll, maxScroll(contentHeight, viewportHeight), trackW);
    }
}
```

- [ ] **Step 2: `ToggleButton.java`**

```java
package com.habitrain.core.client.gui.config.widget;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.config.SharedGuiKit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 启用/停用开关按钮（沿用旧版 BG_ENABLED/BG_DISABLED 药丸视觉）。 */
public final class ToggleButton {
    public interface Listener { void onToggle(boolean enabled); }

    private final Font font;
    private final Component onLabel;
    private final Component offLabel;
    private boolean enabled;
    private final boolean editable;
    private final Listener listener;
    private int x, y, w, h;

    public ToggleButton(Font font, Component onLabel, Component offLabel,
                        boolean enabled, boolean editable, Listener listener) {
        this.font = font;
        this.onLabel = onLabel;
        this.offLabel = offLabel;
        this.enabled = enabled;
        this.editable = editable;
        this.listener = listener;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public void render(GuiGraphics g, int mx, int my) {
        boolean hover = SharedGuiKit.inBounds(mx, my, x, y, w, h);
        int bg = enabled ? SharedGuiKit.BG_ENABLED : SharedGuiKit.BG_DISABLED;
        g.fill(x, y, x + w, y + h, bg);
        if (hover) g.fill(x, y, x + w, y + h, 0x18FFFFFF);
        Component label = enabled ? onLabel : offLabel;
        int lw = font.width(label);
        g.drawString(font, label, x + (w - lw) / 2, y + (h - font.lineHeight) / 2, 0xFFFFFFFF, false);
    }

    /** @return true 表示命中并消费。 */
    public boolean handleClick(double mx, double my) {
        if (!SharedGuiKit.inBounds(mx, my, x, y, w, h)) return false;
        if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
        enabled = !enabled;
        listener.onToggle(enabled);
        return true;
    }
}
```

- [ ] **Step 3: `NumberField.java`**

```java
package com.habitrain.core.client.gui.config.widget;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.config.SharedGuiKit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** 数字输入框 + 右侧「应用」按钮。按按钮才提交（等价旧 GlobalTab/Environment 字段语义）。 */
public final class NumberField {
    private final EditBox box;
    private final boolean editable;
    private final Runnable onApply;
    private final Font font;
    private final Component applyLabel = Component.literal("应用");
    private int boxX, boxY, boxW, h;
    private int btnX, btnW;

    public NumberField(Font font, String initial, int maxLen, boolean editable, Runnable onApply) {
        this.font = font;
        this.editable = editable;
        this.onApply = onApply;
        this.box = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        box.setMaxLength(maxLen);
        box.setValue(initial);
        box.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        box.setEditable(editable);
    }

    public void setBounds(int boxX, int boxY, int boxW, int btnW, int h) {
        this.boxX = boxX; this.boxY = boxY; this.boxW = boxW; this.h = h;
        this.btnX = boxX + boxW + 8;
        this.btnW = btnW;
        box.setX(boxX); box.setY(boxY + 1); box.setWidth(boxW);
    }

    public String getValue() { return box.getValue(); }
    public boolean isFocused() { return box.isFocused(); }

    public void render(GuiGraphics g, int mx, int my, float delta) {
        box.render(g, mx, my, delta);
        boolean hover = SharedGuiKit.inBounds(mx, my, btnX, boxY, btnW, h);
        g.fill(btnX, boxY, btnX + btnW, boxY + h, SharedGuiKit.BG_EDIT);
        if (hover) g.fill(btnX, boxY, btnX + btnW, boxY + h, 0x18FFFFFF);
        int lw = font.width(applyLabel);
        g.drawString(font, applyLabel, btnX + (btnW - lw) / 2, boxY + (h - font.lineHeight) / 2, 0xFFFFFFFF, false);
    }

    /** @return true 表示命中（聚焦输入框或点应用）并消费。 */
    public boolean handleClick(double mx, double my) {
        int bx = box.getX(), by = box.getY(), bw = box.getWidth(), bh = box.getHeight();
        if (mx >= bx && mx < bx + bw && my >= by && my < by + bh) {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
            box.setFocused(true);
            return true;
        }
        if (SharedGuiKit.inBounds(mx, my, btnX, boxY, btnW, h)) {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
            onApply.run();
            return true;
        }
        return false;
    }

    public boolean keyPressed(int key, int scan, int mod) {
        return box.isFocused() && box.keyPressed(key, scan, mod);
    }

    public boolean charTyped(char ch, int mod) {
        return box.isFocused() && box.charTyped(ch, mod);
    }
}
```

- [ ] **Step 4: `SliderRow.java`**

```java
package com.habitrain.core.client.gui.config.widget;

import com.habitrain.core.client.gui.LiveConfigAccess;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** DLC 概率滑块：渐变轨道 + 10% 刻度 + 拖拽 + 当前值。 */
public final class SliderRow {
    public interface Listener { void onChange(float value); }

    private final float min, max, step;
    private final boolean editable;
    private final Listener listener;
    private final Font font;
    private float value;
    private int x, y, w;
    private static final int SLIDER_H = 12;
    private boolean dragging = false;

    public SliderRow(Font font, float min, float max, float step, float initial, boolean editable, Listener listener) {
        this.font = font;
        this.min = min; this.max = max; this.step = step;
        this.value = Mth.clamp(initial, min, max);
        this.editable = editable;
        this.listener = listener;
    }

    public void setBounds(int x, int y, int w) { this.x = x; this.y = y; this.w = w; }
    public float getValue() { return value; }

    public void render(GuiGraphics g) {
        int trackTop = y + (SLIDER_H - 6) / 2;
        g.fill(x, trackTop, x + w, trackTop + 6, 0x44FFFFFF);
        float pct = (value - min) / (max - min);
        int tx = x + (int) (pct * w);
        if (pct > 0.001f) {
            g.fill(x, trackTop, tx, trackTop + 6, fillColor(pct));
        }
        int tc = dragging ? 0xFFFFFFFF : 0xCCFFFFFF;
        g.fill(tx - 5, y, tx + 5, y + SLIDER_H, tc);
        g.fill(tx - 2, y + 4, tx + 2, y + SLIDER_H - 4, 0xFF333333);
        for (int p = 10; p <= 80; p += 10) {
            float pf = (p / 100f - min) / (max - min);
            int px = x + (int) (pf * w);
            g.fill(px, trackTop + 6, px + 1, trackTop + 6 + (p == 50 ? 8 : 4),
                    p == 50 ? 0x88FFFF00 : 0x44FFFFFF);
        }
        String val = String.format("§6§l%d%%", Math.round(value * 100));
        g.drawString(font, val, x + w + 8, y + 1, 0xFFFFFFFF, false);
    }

    private int fillColor(float pct) {
        if (pct < 0.25f) return 0xAAFF5555;
        if (pct < 0.5f) return 0xAAFFAA00;
        if (pct < 0.75f) return 0xAA55FF55;
        return 0xAA55AAFF;
    }

    public boolean handleMouseClicked(double mx, double my) {
        boolean onSlider = mx >= x - 4 && mx <= x + w + 4 && my >= y - 4 && my <= y + SLIDER_H + 4;
        if (!onSlider) return false;
        if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
        dragging = true;
        applyFromMouse(mx);
        return true;
    }

    public boolean handleMouseDragged(double mx) {
        if (!dragging) return false;
        applyFromMouse(mx);
        return true;
    }

    public boolean handleMouseReleased() {
        if (dragging) { dragging = false; return true; }
        return false;
    }

    private void applyFromMouse(double mx) {
        float rel = Mth.clamp((float) ((mx - x) / w), 0f, 1f);
        float raw = min + rel * (max - min);
        float nv = Mth.clamp(Math.round(raw / step) * step, min, max);
        if (nv != value) {
            value = nv;
            listener.onChange(value);
        }
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。随后 `./gradlew clean build` + JAR 复制核对。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/widget/
git commit -m "feat(modmenu): scroll pane, toggle, number field, slider widgets"
```

---

### Task 3: 剩余控件（ColorCyclePicker / MapFilterField / SaveBar）

**Files:**
- Create: `config/widget/ColorCyclePicker.java`、`config/widget/MapFilterField.java`、`config/widget/SaveBar.java`

**Interfaces:**
- Consumes: `SharedGuiConstants`（20 色）、`ToggleButton`、`ScrollPane` 无关、`ConfigContext`。
- Produces: `ColorCyclePicker`（Task 4/6）、`MapFilterField`（Task 4/6）、`SaveBar`（Task 13）。

- [ ] **Step 1: `ColorCyclePicker.java`**

移植旧 `client/gui/TaskColorPicker.java` 的逻辑（颜色循环 + 描边 ±），改为手动命中区并接受 `editable`：

```java
package com.habitrain.core.client.gui.config.widget;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.SharedGuiConstants;
import com.habitrain.core.client.gui.config.SharedGuiKit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** 任务/小游戏 instinct 颜色 20 色循环 + 描边宽度 ±（旧 TaskColorPicker）。 */
public final class ColorCyclePicker {
    private final Font font;
    private final boolean editable;
    private final Runnable onSave;
    private int color;
    private float outline;
    private int colorX, colorY, colorW, colorH;
    private int minusX, minusY, plusX, plusY, sideW, sideH;

    public ColorCyclePicker(Font font, int initialColor, float initialOutline, boolean editable, Runnable onSave) {
        this.font = font;
        this.color = initialColor;
        this.outline = initialOutline;
        this.editable = editable;
        this.onSave = onSave;
    }

    public int getColor() { return color; }
    public float getOutlineWidth() { return outline; }

    public void setBounds(int colorX, int colorY, int colorW, int colorH, int sideW, int sideH) {
        this.colorX = colorX; this.colorY = colorY; this.colorW = colorW; this.colorH = colorH;
        this.sideW = sideW; this.sideH = sideH;
        this.minusX = colorX + colorW + 8;
        this.plusX = minusX + sideW + 4;
        this.minusY = plusY = colorY;
    }

    public void render(GuiGraphics g, int mx, int my) {
        boolean hover = SharedGuiKit.inBounds(mx, my, colorX, colorY, colorW, colorH);
        g.fill(colorX, colorY, colorX + colorW, colorY + colorH, 0xFF202830);
        g.fill(colorX, colorY, colorX + colorW, colorY + colorH, 0xFF000000 | (color & 0xFFFFFF));
        if (hover) g.fill(colorX, colorY, colorX + colorW, colorY + colorH, 0x18FFFFFF);
        int idx = getColorIndex();
        String label = idx >= 0 ? "§l● " + SharedGuiConstants.COLOR_NAMES[idx] : "§l● 自定义";
        g.drawString(font, label, colorX + colorW + 4, colorY + (colorH - font.lineHeight) / 2, 0xFFFFFFFF, false);
        renderSideBtn(g, minusX, minusY, "−", mx, my);
        renderSideBtn(g, plusX, plusY, "+", mx, my);
    }

    private void renderSideBtn(GuiGraphics g, int bx, int by, String text, int mx, int my) {
        boolean hover = SharedGuiKit.inBounds(mx, my, bx, by, sideW, sideH);
        g.fill(bx, by, bx + sideW, by + sideH, SharedGuiKit.BG_EDIT);
        if (hover) g.fill(bx, by, bx + sideW, by + sideH, 0x18FFFFFF);
        g.drawString(font, text, bx + (sideW - font.width(text)) / 2, by + (sideH - font.lineHeight) / 2, 0xFFFFFFFF, false);
    }

    /** @return true 表示命中并消费。 */
    public boolean handleMouseClick(double mx, double my) {
        if (SharedGuiKit.inBounds(mx, my, minusX, minusY, sideW, sideH)) {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
            outline = Mth.clamp(outline - 0.5f, 1f, 10f);
            onSave.run();
            return true;
        }
        if (SharedGuiKit.inBounds(mx, my, plusX, plusY, sideW, sideH)) {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
            outline = Mth.clamp(outline + 0.5f, 1f, 10f);
            onSave.run();
            return true;
        }
        if (SharedGuiKit.inBounds(mx, my, colorX, colorY, colorW, colorH)) {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
            int next = (getColorIndex() + 1) % SharedGuiConstants.getColorCount();
            color = SharedGuiConstants.getColor(next, 0xB4);
            onSave.run();
            return true;
        }
        return false;
    }

    private int getColorIndex() {
        int rgb = color & 0xFFFFFF;
        for (int i = 0; i < SharedGuiConstants.getColorCount(); i++) {
            if ((SharedGuiConstants.getColor(i, 0xFF) & 0xFFFFFF) == rgb) return i;
        }
        return -1;
    }
}
```

- [ ] **Step 2: `MapFilterField.java`**

移植旧 `client/gui/TaskMapFilterEditor.java`（过滤模式循环 + 地图列表输入框 + 任务停用时的禁用遮罩）：

```java
package com.habitrain.core.client.gui.config.widget;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.config.SharedGuiKit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 地图过滤模式（全部/白名单/黑名单）+ 地图列表输入框（旧 TaskMapFilterEditor）。 */
public final class MapFilterField {
    private final Font font;
    private final boolean editable;
    private final Runnable onModeChange;
    private final BooleanSupplier activeSupplier; // 任务/小游戏启用时 true
    private int filterMode;
    private final EditBox mapField;
    private int btnX, btnY, btnW, btnH;

    public MapFilterField(Font font, int filterMode, String initialMaps,
                          boolean editable, BooleanSupplier activeSupplier, Runnable onModeChange) {
        this.font = font;
        this.editable = editable;
        this.activeSupplier = activeSupplier;
        this.filterMode = filterMode;
        this.onModeChange = onModeChange;
        this.mapField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        mapField.setMaxLength(512);
        mapField.setValue(initialMaps);
        mapField.setEditable(editable);
    }

    public void setBounds(int btnX, int btnY, int btnW, int btnH, int fieldX, int fieldY, int fieldW) {
        this.btnX = btnX; this.btnY = btnY; this.btnW = btnW; this.btnH = btnH;
        mapField.setX(fieldX); mapField.setY(fieldY); mapField.setWidth(fieldW);
    }

    public int getFilterMode() { return filterMode; }
    public String getMapText() { return mapField.getValue(); }

    public static List<String> parseMaps(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : text.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public void render(GuiGraphics g, int mx, int my, float delta) {
        boolean active = activeSupplier.getAsBoolean();
        String label = switch (filterMode) {
            case 1 -> "§e白名单";
            case 2 -> "§c黑名单";
            default -> "§7全部地图";
        };
        boolean hover = SharedGuiKit.inBounds(mx, my, btnX, btnY, btnW, btnH);
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, SharedGuiKit.BG_EDIT);
        if (hover) g.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0x18FFFFFF);
        g.drawString(font, label, btnX + 4, btnY + (btnH - font.lineHeight) / 2, 0xFFFFFFFF, false);
        mapField.render(g, mx, my, delta);
        if (!active) {
            g.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0x88000000);
            g.fill(mapField.getX(), mapField.getY(), mapField.getX() + mapField.getWidth(), mapField.getY() + mapField.getHeight(), 0x88000000);
        }
    }

    /** @return true 表示命中并消费。 */
    public boolean handleMouseClick(double mx, double my) {
        if (SharedGuiKit.inBounds(mx, my, btnX, btnY, btnW, btnH)) {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
            if (!activeSupplier.getAsBoolean()) return true; // 停用时按钮无效
            filterMode = (filterMode + 1) % 3;
            onModeChange.run();
            return true;
        }
        int bx = mapField.getX(), by = mapField.getY(), bw = mapField.getWidth(), bh = mapField.getHeight();
        if (mx >= bx && mx < bx + bw && my >= by && my < by + bh) {
            if (!editable) { LiveConfigAccess.showDeniedMessage(); return true; }
            mapField.setFocused(true);
            return true;
        }
        return false;
    }

    public boolean keyPressed(int key, int scan, int mod) {
        return mapField.isFocused() && mapField.keyPressed(key, scan, mod);
    }

    public boolean charTyped(char ch, int mod) {
        return mapField.isFocused() && mapField.charTyped(ch, mod);
    }
}
```

- [ ] **Step 3: `SaveBar.java`**

```java
package com.habitrain.core.client.gui.config.widget;

import com.habitrain.core.client.gui.config.SharedGuiKit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 底部保存栏：未保存指示 + 保存 + 返回。命中区由 render 记录，点击在 handleClick。 */
public final class SaveBar {
    private final Font font;
    private final boolean editable;
    private final Runnable onSave;
    private final Runnable onBack;
    private int barY, barH;
    private int saveX, saveW, backX, backW;
    private boolean dirty;
    private boolean backEnabled; // 栈非空时显示「返回上一页」，否则「返回 ModMenu」

    public SaveBar(Font font, boolean editable, Runnable onSave, Runnable onBack) {
        this.font = font;
        this.editable = editable;
        this.onSave = onSave;
        this.onBack = onBack;
    }

    public void setDirty(boolean d) { dirty = d; }
    public void setBackEnabled(boolean b) { backEnabled = b; }

    public void render(GuiGraphics g, int x, int width, int y, int h, int mx, int my) {
        barY = y; barH = h;
        g.fill(0, y, width, y + h, 0xFF10141A);
        g.fill(0, y, width, y + 1, 0x30FFFFFF);

        String dirtyText = dirty ? "§e● 有未保存的修改" : "§7○ 无未保存修改";
        g.drawString(font, dirtyText, x + 8, y + (h - font.lineHeight) / 2, 0xFFFFFFFF, false);

        backW = 88; backX = width - x - 8 - backW;
        saveW = 88; saveX = backX - 8 - saveW;
        boolean saveHover = SharedGuiKit.inBounds(mx, my, saveX, y + 4, saveW, h - 8);
        boolean backHover = SharedGuiKit.inBounds(mx, my, backX, y + 4, backW, h - 8);
        int saveBg = editable ? (dirty ? 0xFF2A5A3A : SharedGuiKit.BG_EDIT) : 0xFF222222;
        g.fill(saveX, y + 4, saveX + saveW, y + h - 4, saveBg);
        if (saveHover && editable) g.fill(saveX, y + 4, saveX + saveW, y + h - 4, 0x18FFFFFF);
        String saveLabel = "§a保存";
        g.drawString(font, saveLabel, saveX + (saveW - font.width(saveLabel)) / 2, y + (h - font.lineHeight) / 2, 0xFFFFFFFF, false);
        g.fill(backX, y + 4, backX + backW, y + h - 4, SharedGuiKit.BG_EDIT);
        if (backHover) g.fill(backX, y + 4, backX + backW, y + h - 4, 0x18FFFFFF);
        String backLabel = backEnabled ? "返回上一页" : "返回 ModMenu";
        g.drawString(font, backLabel, backX + (backW - font.width(backLabel)) / 2, y + (h - font.lineHeight) / 2, 0xFFFFFFFF, false);
    }

    /** @return true 表示命中并消费。 */
    public boolean handleClick(double mx, double my) {
        if (SharedGuiKit.inBounds(mx, my, saveX, barY + 4, saveW, barH - 8)) {
            if (editable) onSave.run();
            return true;
        }
        if (SharedGuiKit.inBounds(mx, my, backX, barY + 4, backW, barH - 8)) {
            onBack.run();
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/widget/
git commit -m "feat(modmenu): color picker, map filter, save bar widgets"
```

---

### Task 4: 任务列表页（TaskListView） + 任务详情子页面（TaskEditPage）

**Files:**
- Create: `config/TaskListView.java`、`config/TaskEditPage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/*`、`SharedGuiKit`/`SharedGuiConstants`、`TaskRegistry`/`TaskDefinition`/`TaskCategory`/`GameModeRegistry`、`BlackoutMode`、`ConfigManager`/`TaskConfigEntry`、`HabiTrainCore.MOD_ID`。
- Produces: `TaskListView`（被 `ConfigRootScreen` 用 3 个 Scope 实例化为 内置/停电/扩展 页）、`TaskEditPage`。

**行为定义：**
- `TaskListView.Scope`：`BUILTIN`（modId==habitrain_core 且非停电阵营）、`BLACKOUT`（BLACKOUT_GOOD/BAD 阵营，仅停电）、`DLC`（modId≠habitrain_core 且非停电阵营）。
- 结构 = 旧 `config/TaskTabScreen.java`（左侧搜索+模式分区列表，右侧任务网格），适配：
  - `root` 字段 → `ConfigContext ctx`；`font`/`editable` 从 `ctx.font()`/`ctx.editable()` 取。
  - 编辑按钮 → `ctx.pushPage(new TaskEditPage(ctx, def, cfg, modeName, accent))`（不再 `setScreen`）。
  - 启用开关 → `cfg.enabled = !cfg.enabled; ctx.config-set` 后 `ctx.markDirty()`（等价旧 `setTaskConfig` 内部已 markDirty）。
  - 滚动 → 每个区域一个 `ScrollPane`（侧栏一个、内容一个），**真实高度夹紧**；`mouseReleased` 转发由根统一处理，拖拽标记在 release 释放（修复旧 quirk）。
  - 停电 Scope 强制两个分区：`__good`（"§a停电模式 · 好人任务"，accent `0xFF3FBF6F`）、`__bad`（"§c停电模式 · 坏人任务"，accent `0xFFD84848`）；BUILTIN/DLC 按 gameModeId 分组（保留旧 `sectionKeyFor`/`resolveSectionTitle`/`accentForSection`/`taskCategoryPriority`/`categoryGroupLabel` 逻辑）。
- 只读模式：所有改动弹 `LiveConfigAccess.showDeniedMessage()`；编辑按钮/开关禁用。
- `title()`：BUILTIN→"内置任务"、BLACKOUT→"停电任务"、DLC→"扩展任务"。
- 搜索框与侧栏统计「已启用/总数」沿用旧逻辑。

**TaskEditPage 行为（移植旧 `client/gui/TaskEditScreen.java`）：**
- 构造：`TaskEditPage(ConfigContext ctx, TaskDefinition def, TaskConfigEntry cfg, TaskCategory category, String modeName, int accent)`。
- 区块：基础设置（启用 ToggleButton、instinct 颜色 ColorCyclePicker、描边 ±）、奖励设置（金/情感/权重 NumberField，空=默认→`hasX=false`）、地图设置（MapFilterField）、基本信息只读（displayName/fullId/modId/category/默认权重/blockTypeId/canDirectlyWin/scanBlocks）、停电商店售价行（**仅** BLACKOUT_GOOD/BAD 显示，NumberField min 0，空=默认）。
- 按钮行（页面内）：`✔ 保存修改`、`✔ 保存并返回`、`↺ 重置默认`；`← 返回列表` 在顶部。
  - `保存修改`：`syncFields()` 写 cfg（`enabledMaps=MapFilterField.parseMaps`、数字字段空→hasX=false 否则 hasX=true+值、shopPrice≥0）→ `ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg)` → `ctx.markDirty()` → toast "§a✔ 任务「X」已保存！"。
  - `保存并返回`：同上 → `ctx.popPage()`。
  - `重置默认`：cfg 恢复默认（enabled=true、maps 清空、filter=0、color `0xB4C8C8C8`、outline 4.0f、奖励全 off/0、shopPrice 0）→ setTaskConfig → markDirty → 重建输入框。
  - `onSaveRequested()`：`syncFields()` + setTaskConfig + markDirty（供根「保存」调用）。
  - `onLeave()`：不写盘（等价旧 goBack 不保存；dirty 由根 onClose 统一 flush）。
- `title()`：`Component.literal(modeName + " › " + def.getDisplayName())`。

- [ ] **Step 1: 编写 `TaskListView.java`**

按上面行为定义实现。建议直接从旧 `config/TaskTabScreen.java` 复制并做以下机械改动（旧文件留作参考，勿删）：
1. 类声明改为 `public final class TaskListView implements ConfigPage`，加 `private final Scope scope;` 字段与 `enum Scope { BUILTIN, BLACKOUT, DLC }`。
2. 构造 `TaskListView(ConfigContext ctx, Scope scope)`：存 scope，`font=ctx.font()`，`editable=ctx.editable()`，`rebuildSections()`。
3. `rebuildSections()`：过滤 `TaskRegistry.getAll()` 到本 scope（停电→category 为 BLACKOUT_GOOD/BAD；内置→modId==MOD_ID 且非停电阵营；DLC→modId!=MOD_ID 且非停电阵营）；BLACKOUT scope 强制加入空 `__good`/`__bad` 两节。
4. 编辑按钮回调：`ctx.pushPage(new TaskEditPage(ctx, def, cfg, def.getCategory(), modeName, accent))`。
5. 启用开关：`cfg.enabled=!cfg.enabled; ConfigManager.getInstance().setTaskConfig(fullId, cfg);`（setTaskConfig 已 markDirty，无需额外 ctx.markDirty，但为一致性可加 `ctx.markDirty()`——不改变行为）。
6. 滚动：`sidebarScroll`→`ScrollPane sidebar`，`contentScroll`→`ScrollPane content`；`mouseDragged` 调 `pane.mouseDragged(my, contentHeight, viewportHeight)`；`mouseReleased` 调 `pane.mouseReleased()`（根会转发 release）；`mouseScrolled` 按 `mx < x+SIDEBAR_W` 路由到对应 pane 并返回 true。
7. `mouseClicked` 顺序：搜索框 → 侧栏命中 → 任务行命中 → `sidebar.mouseClicked(...)` → `content.mouseClicked(...)`；`mouseDragged`/`mouseReleased`/`mouseScrolled` 见上；`keyPressed`/`charTyped` 转发搜索框。
8. 补 `title()`/`onEnter`（进入时清空搜索/回到顶，沿用旧行为即可）。

- [ ] **Step 2: 编写 `TaskEditPage.java`**

按行为定义实现。从旧 `client/gui/TaskEditScreen.java` + `TaskSaveController.java` + `TaskColorPicker.java` + `TaskMapFilterEditor.java` 移植，机械改动：
- 类声明 `public final class TaskEditPage implements ConfigPage`；`root`→`ctx`；`font`/`editable` 取自 ctx。
- 颜色/地图用新 `ColorCyclePicker`/`MapFilterField`；数字字段用 `NumberField`。
- 保存/重置按钮为手动命中区（沿用旧行内按钮风格）。
- `onSaveRequested()` 实现如上；`onLeave()` 空实现。
- 滚动用单个 `ScrollPane`，真实高度夹紧；`mouseReleased` 转发。
- 只读守卫：所有控件 handleClick 已内建拒绝；页内按钮点击前判 `editable`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/TaskListView.java \
        src/main/java/com/habitrain/core/client/gui/config/TaskEditPage.java
git commit -m "feat(modmenu): task list view (builtin/blackout/dlc) and task edit page"
```

---

### Task 5: 任务全局参数页 + 全局玩法参数页

**Files:**
- Create: `config/TaskGlobalPage.java`、`config/GameplayGlobalPage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/SliderRow`/`ToggleButton`/`NumberField`、`ConfigManager`。
- Produces: 两个 `ConfigPage`。

**行为（移植旧 `config/GlobalTabScreen.java`）：**

`TaskGlobalPage`（title "任务全局参数"）：
- DLC 概率滑块：`SliderRow(0.10f, 0.80f, 0.05f, ConfigManager.getInstance().getDlcProbabilityTarget(), editable, v -> ConfigManager.getInstance().setDlcProbabilityTarget(v))`，说明文字沿用旧第 139-142 行。
- 小游戏任务总开关：`ToggleButton("§a小游戏：已启用","§c小游戏：已停用", mgGlobal, editable, v -> { ConfigManager.getInstance().setMinigameGlobalEnabled(v); ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer()); })`，说明文字沿用旧第 224-232 行。
- 滚动：一个 `ScrollPane`；`onSaveRequested()` 空（无文本字段）。

`GameplayGlobalPage`（title "全局玩法参数"）：
- 警长数量除数：`NumberField(font, String.valueOf(divisor), 3, editable, () -> 解析 ≥1 并 setSheriffCountDivisor)`，说明沿用旧第 180-191 行。
- 临时电源价格：`NumberField(..., 6, editable, () -> 解析 ≥0 并 setTempPowerPrice)`，说明沿用旧第 194-206 行。
- 杀手刀耐久：`ToggleButton("§a刀耐久：已启用","§c刀耐久：已禁用", knife, editable, v -> ConfigManager.getInstance().setKnifeDurabilityEnabled(v))`，说明沿用旧第 209-221 行。
- 滚动：一个 `ScrollPane`；`onSaveRequested()` 空（应用按钮即时提交）。

- [ ] **Step 1: 编写两个页面类**（按上面行为；布局/说明文案照抄旧 GlobalTab 对应区块的 y 坐标推进方式）

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/TaskGlobalPage.java \
        src/main/java/com/habitrain/core/client/gui/config/GameplayGlobalPage.java
git commit -m "feat(modmenu): task-global and gameplay-global pages"
```

---

### Task 6: 小游戏列表页 + 小游戏详情子页面

**Files:**
- Create: `config/MinigamesPage.java`、`config/MinigameEditPage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/*`、`QuestMinigame`/`QuestMinigames`（SRE）、`ConfigManager`/`MinigameConfigEntry`。
- Produces: 两个 `ConfigPage`。

**行为（移植旧 `config/MinigameTabScreen.java` + `config/MinigameEditScreen.java`）：**

`MinigamesPage`（title "小游戏"）：
- 卡片网格（2 列、`CARD_H=56`、`CARD_GAP=6`），搜索框，统计「已启用/过滤/总计」。
- 启用开关：`cfg.enabled=!cfg.enabled; setMinigameConfig; applyMinigameEnforcement(singleplayerServer)`（沿用旧 224-232）。
- 编辑按钮：`ctx.pushPage(new MinigameEditPage(ctx, mg, cfg))`。
- SRE 缺失：`QuestMinigames.getAll()` 抛异常 → 整页 "§c未检测到 SRE（星穹列车）模组，小游戏功能不可用"。
- 滚动：一个 `ScrollPane`，真实夹紧；release 转发。

`MinigameEditPage`（title `Component.literal("小游戏 › " + displayName)`）：
- 基础设置（启用 ToggleButton、颜色 ColorCyclePicker、描边 ±）、奖励设置（金/情感/权重 NumberField）、地图设置（MapFilterField）、基本信息只读（id、displayName、"归属: sre:base（基础任务池）"）。
- 页内按钮：`保存并返回`、`重置`；`← 返回` 顶部。
  - `保存并返回`：`commitFields()`（数字空→hasX=false，否则 hasX=true+解析；金 int、情 float、权重≥0；maps=parseMaps）→ `setMinigameConfig(id, cfg)` → `ctx.markDirty()` → `ctx.popPage()`。
  - `重置`：cfg 恢复默认 → `putMinigameConfig(id, cfg)` → 重建输入框。
  - `onSaveRequested()`：`commitFields()` + `setMinigameConfig` + markDirty。
  - `onLeave()` 空（不写盘）。

- [ ] **Step 1: 编写两个页面类**（照上面行为；结构参考旧两个文件）

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/MinigamesPage.java \
        src/main/java/com/habitrain/core/client/gui/config/MinigameEditPage.java
git commit -m "feat(modmenu): minigames list and edit pages"
```

---

### Task 7: 投票设置页 + 模式可选地图子页面

**Files:**
- Create: `config/VoteSettingsPage.java`、`config/ModeAllowedMapsPage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/*`、`ConfigManager`/`ModeMapVoteSettings`/`ModeVoteEntry`/`MapVoteEntry`、`GameModeRegistry`、`MapPoolRotationService`、`PayloadSenders`。
- Produces: 两个 `ConfigPage`。

**行为（移植旧 `config/VoteTabScreen.java` + `config/ModeAllowedMapsScreen.java`）：**

`VoteSettingsPage`（title "投票设置"）：
- 总开关（`settings().enabled`）`ToggleButton` → 翻转 + `persist()`（persist = `setModeMapVoteSettings(settings)`，内部 markDirty）。
- 模式/地图投票时长：两个 `NumberField`（初始 `modeDurationSeconds`/`mapDurationSeconds`），**提交时才解析**（`commitFieldsToSettings()`：非空才解析，clamp 5–120 并回写字段值）。
- 地图池轮换区块（沿用旧 VoteTab 轮换小节，但「编辑」按钮打开的是导航页而非子屏——见 Task 13 导航注册）：
  - `pool_toggle`/`pool_auto`/`pool_apply_mode` 三个开关（启用时 `MapPoolRotationService.ensureSeededIfNeeded`，`persist()` + `ConfigManager.save()` 立即落盘——**保留旧行为**）。
  - 摘要行「每局轮换 · 共N池 · 当前池K · name · M图」。
  - `请求跳过当前地图池` 按钮 → `PayloadSenders.sendMapPoolSkip()` + toast "§e已请求跳过当前地图池…"。
- 可投票模式行：启用开关 + 名称 `NumberField`（可改名，提交时 `commitFieldsToSettings`）+ 「可选地图」按钮 → `ctx.pushPage(new ModeAllowedMapsPage(ctx, modeId))` + ↑↓ 顺序调整（`settings().moveMode(id,±1)` → rebuildIdLists → persist + `ConfigManager.save()` 立即落盘——**保留旧行为**）。
- 可投票地图行：启用开关 + 名称字段（提交时写入）。
- `onSaveRequested()`：`commitFieldsToSettings()` + `persist()` + `ctx.markDirty()`（根「保存」负责落盘）。
- `onLeave()`：`commitFieldsToSettings()` + `persist()`（等价旧 flushPendingFields，**不落盘**）。
- 滚动：一个 `ScrollPane`；release 转发。

`ModeAllowedMapsPage`（title "可选地图"；栈压入）：
- 从 `settings().maps` 键 + 既有 `allowedMaps` 构造候选/选中集。
- 行点击切换选中（内存内）；`清空（不限制）`；`保存` → `settings().modes.computeIfAbsent(modeId,...).allowedMaps=new ArrayList<>(selected)` → `setModeMapVoteSettings`（markDirty）→ `ConfigManager.save()` → `ctx.popPage()`（**保留立即落盘**）。`← 返回`/ESC → `ctx.popPage()`（不保存，丢弃）。
- 滚动：一个 `ScrollPane`。

- [ ] **Step 1: 编写 `VoteSettingsPage.java`**（照上面行为；`commitFieldsToSettings`/`rebuildIdLists`/`rebuildNameFields` 逻辑照抄旧 `VoteTabScreen.java` 对应方法）

- [ ] **Step 2: 编写 `ModeAllowedMapsPage.java`**（照上面行为；参考旧 `ModeAllowedMapsScreen.java`）

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/VoteSettingsPage.java \
        src/main/java/com/habitrain/core/client/gui/config/ModeAllowedMapsPage.java
git commit -m "feat(modmenu): vote settings and allowed-maps pages"
```

---

### Task 8: 地图池轮换页

**Files:**
- Create: `config/MapPoolPage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/ScrollPane`、`ConfigManager`/`ModeMapVoteSettings`/`MapPoolRotationSettings`/`MapPoolEntry`、`MapPoolRotationService`。
- Produces: `MapPoolPage`。

**行为（移植旧 `config/MapPoolEditorScreen.java`，但改为工作副本编辑）：**
- **关键改进**：不再直接改 live 对象。构造/onEnter 时深拷贝 `settings() = ModeMapVoteSettings.fromJson(settings().toJson())` 作为工作副本 `work`；所有编辑作用于 `work`；`onSaveRequested()` 才 `setModeMapVoteSettings(work)`（markDirty）+ `ctx.markDirty()`；离开不保存即丢弃（**保留旧「返回不保存即丢弃」语义，且不再泄漏到内存 live 对象**）。
- 左池列表（`POOL_PANEL_W=180`）：池名 EditBox（maxLength 32）、启用 ✓、地图数徽标、当前池标记；右侧该池地图多选（候选 = `work.maps` 键 ∪ 各池 mapIds 并集；label 用已知 map displayName，未知显示 `§c...§8(未知)`）。
- 操作按钮：`+ 添加池`（`work.rotationOrDefault().addPool()`，满 20 toast）、`删本池`（`removePool`，低于 MIN_POOLS=1 toast）、`重新均摊分池`（`MapPoolRotationService.repartition(work, new Random())`）、`清空本池`。
- `onSaveRequested()`：flushNames（读池名 EditBox 回写 `work` 各池 displayName）+ writeSelectedToPool + strip 空 mapId → `setModeMapVoteSettings(work)` → `ctx.markDirty()` → toast "地图池已保存（共 N 池）"。
- `onLeave()`：不写盘（丢弃）。
- 滚动：左右两个 `ScrollPane`（`mx < PAD+POOL_PANEL_W+4` 路由左，否则右）；release 转发。
- 只读：`!editable` 时按钮/勾选/改名全禁用 + 拒绝提示。

- [ ] **Step 1: 编写 `MapPoolPage.java`**（照上面行为；池列表/地图多选/选中集同步逻辑参考旧 `MapPoolEditorScreen.java`）

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/MapPoolPage.java
git commit -m "feat(modmenu): map pool rotation page with working-copy editing"
```

---

### Task 9: 环境档案编辑控件 + 大厅环境页 + 动态雨页

**Files:**
- Create: `config/EnvProfileEditor.java`、`config/LobbyEnvPage.java`、`config/RainEnvPage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/ToggleButton`/`NumberField`/`ScrollPane`、`ConfigManager`/`EnvironmentSettings`/`EnvProfile`/`EnvTimeSpec`/`EnvTimeSpec.Preset`/`PostMatchTimeRule`。
- Produces: `EnvProfileEditor`（Task 10 复用）、两个 `ConfigPage`。

**`EnvProfileEditor`（非页面控件）：**
```java
public final class EnvProfileEditor {
    public EnvProfileEditor(Font font, boolean editable, Runnable onDirty) {...}
    // 绑定一个 EnvProfile 引用，逐字段渲染；onDirty 在每次改动后调用（页面内 = ctx.markDirty()）
    public void bind(EnvProfile profile);
    /** 渲染区块，返回内容区总高度（供页面 ScrollPane 计算 maxScroll）。 */
    public int render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w);
    /** 命中处理（控件区域命中返回 true 并消费）。 */
    public boolean handleClick(double mx, double my);
    public boolean handleDrag(double mx, double my);   // 若有滑块（无，EnvProfileEditor 无滑块，返回 false）
    public boolean keyPressed(int key, int scan, int mod);
    public boolean charTyped(char ch, int mod);
    /** 聚焦字段 flush 回 profile（tick/fogEnd 输入框由「应用」或 onSaveRequested 提交）。 */
    public void flushFocused();
}
```
区块（沿用旧 `EnvironmentTabScreen` 的字段布局与文案）：
- 环境覆盖 `enabled` ToggleButton（切换 → onDirty）。
- 时间：`mode` 按钮（TICK↔PRESET，切换 → onDirty）、PRESET 时 preset 循环按钮（DAY/NOON/NIGHT/MIDNIGHT/SUNDOWN，切换强制 mode=PRESET → onDirty）、TICK 时 tick `NumberField`（应用 → clamp 0–23999 且 mode=TICK）。
- 天气：CLEAR/RAIN/THUNDER 循环按钮 → onDirty。
- snow/sand/fog/daylightCycle/weatherCycle 五个 ToggleButton → onDirty。
- fogEnd `NumberField`（应用 → `max(0, v)`）。

`LobbyEnvPage`（title "大厅环境"）：绑定 `settings().lobby`；`onEnter` bind；`onSaveRequested()` = `editor.flushFocused()` + `ctx.markDirty()`；`onLeave()` = `editor.flushFocused()` + markDirty；滚动一个 ScrollPane。

`RainEnvPage`（title "动态雨"）：沿用旧 EnvironmentTab 动态雨子页：
- `lowPlayerRainEnabled` ToggleButton（→ onDirty）。
- minPlayers `NumberField`（应用 → `max(1,v)` 写 `settings().lowPlayerRainMinPlayers`）。
- 状态行「当前生效阈值: N（≥1）」。
- `onSaveRequested()`/`onLeave()` = flush + markDirty。
- 注意：环境设置是 live 图（旧版直接 mutate `settings().lobby` 等），**保留**：改动即写 live 对象 + markDirty，取消仅不再落盘（旧行为一致，无内存泄漏顾虑——环境页一直就是即时写 live + markDirty 语义）。

- [ ] **Step 1: 编写 `EnvProfileEditor.java`**（照上面；布局文案参考旧 `EnvironmentTabScreen.java` 各区块）

- [ ] **Step 2: 编写 `LobbyEnvPage.java` + `RainEnvPage.java`**

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/EnvProfileEditor.java \
        src/main/java/com/habitrain/core/client/gui/config/LobbyEnvPage.java \
        src/main/java/com/habitrain/core/client/gui/config/RainEnvPage.java
git commit -m "feat(modmenu): env profile editor, lobby and rain env pages"
```

---

### Task 10: 对局环境页 + 局后时间页

**Files:**
- Create: `config/MatchEnvPage.java`、`config/PostMatchEnvPage.java`

**Interfaces:**
- Consumes: `EnvProfileEditor`、`ConfigPage`/`ConfigContext`、`ConfigManager`/`EnvironmentSettings`/`EnvProfile`/`PostMatchTimeRule`、`ToggleButton`。
- Produces: 两个 `ConfigPage`。

**`MatchEnvPage`（title "对局环境"）— 移植旧 EnvironmentTab 对局子页：**
- 左侧地图列表（"默认" 行 + `collectMapIds()` 行；有覆盖显示绿色标记；点击选中 → 无覆盖则 `settings().matchMaps.put(id, EnvProfile.createMatchDefault())` + markDirty；!editable 拒绝）。
- 右侧：`EnvProfileEditor` 绑定 默认(`settings().matchDefaultProfile`) 或 选中地图(`settings().matchMaps.get(id)`)；选中地图覆盖时显示 `删除地图覆盖` 按钮（删除 + markDirty）。
- `onEnter` 重建地图列表；`onSaveRequested()`/`onLeave()` = `editor.flushFocused()` + markDirty。
- 滚动：左列表与右编辑器各一个 ScrollPane（`mx < x+240` 路由左）。

**`PostMatchEnvPage`（title "局后时间"）— 移植旧 EnvironmentTab 局后子页：**
- 好人胜利（`settings().goodWin`）：`enabled` ToggleButton + `EnvProfileEditor` 只渲染时间部分（复用 EnvProfileEditor 绑定 `goodWin.time`？不行——时间在 `PostMatchTimeRule.time` 内嵌）。**实现**：对每个规则渲染 `enabled` 开关 + 复用 `EnvProfileEditor` 的「时间/天气」区块——但 EnvProfileEditor 绑定 EnvProfile。故：把 `PostMatchTimeRule.time` 包装：构造 `EnvProfile` 临时对象仅编辑其 `time`？不可行，因为要直接改 live 对象。
  - **简化方案**：`PostMatchEnvPage` 内联渲染时间编辑器（模式/预设/刻 + 天气可选沿用），复用逻辑抽到 `EnvProfileEditor` 的一个静态/实例方法 `renderTimeSection(g, font, editable, EnvTimeSpec spec, Runnable onDirty, int x, int y)` 返回 y。Task 9 的 EnvProfileEditor 同时提供 `renderTimeSection`（内部 hit 记录共享，用 `handleClick` 分发——需要让 EnvProfileEditor 支持"只渲染时间段并绑定 EnvTimeSpec"）。
  - **决定**：把 `EnvProfileEditor` 设计成可绑定 `EnvProfile` 或 `EnvTimeSpec`（两个重载 `bind(EnvProfile)` / `bindTime(EnvTimeSpec)`）；`bindTime` 模式下只渲染时间区块（模式/预设/刻）。Task 9 已写 EnvProfileEditor，此处补充 `bindTime` 分支（在 Task 9 中一并实现，避免 Task 10 改动）。
- 好人胜利与杀手/中立各一个区块：`enabled` ToggleButton + `bindTime(rule.time)` 时间编辑 + onDirty。
- `onSaveRequested()`/`onLeave()` = flush + markDirty。

> 修改 Task 9 的 `EnvProfileEditor`：增加 `bindTime(EnvTimeSpec spec)` 模式（只渲染时间区块）——Task 9 Step 1 即实现，此处仅使用。

- [ ] **Step 1: 编写 `MatchEnvPage.java`**（照上面；地图列表/覆盖逻辑参考旧 `EnvironmentTabScreen.java` 对局子页）

- [ ] **Step 2: 编写 `PostMatchEnvPage.java`**（照上面；时间区块用 `EnvProfileEditor.bindTime`）

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/MatchEnvPage.java \
        src/main/java/com/habitrain/core/client/gui/config/PostMatchEnvPage.java
git commit -m "feat(modmenu): match env and post-match time pages"
```

---

### Task 11: 角色覆盖页

**Files:**
- Create: `config/RoleOverridePage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/ScrollPane`、`RoleOverrideApi`/`RoleOverrideRegistry`/`RoleOverrideEngine`/`RoleOverrideEntry`/`OverrideStatus`/`RoleOverrideKind`/`ReplaceRoleDefinition`/`ModifyRoleDefinition`、`ConfigManager`/`RoleOverrideConfigSection`、`TMMRoles`（SRE）。
- Produces: `RoleOverridePage`（含 `rebuildRows()`，供根 `refreshRoleOverrideTab()` 调用）。

**行为（移植旧 `config/RoleOverrideTabScreen.java`）：**
- 总开关（header 右侧可点区域）+ 冲突横幅 + 行列表（`ROW_H=62`、`ROW_GAP=2`），行内容/状态色/kind 标签/来源/角色 ID/描述/状态详情全部沿用旧渲染。
- 点击行 → `toggleRow`：开启时自动禁用同 target 其他条目（沿用旧逻辑）→ `setRoleOverrides(cfg)`（内部 rebuild engine）→ `rebuildRows()` → **`ctx.saveNow()`（保留立即落盘语义）**。
- 总开关点击 → 同上 saveNow。
- `!editable` 时 `mouseClicked` 直接 false（沿用旧静默行为）。
- `rebuildRows()` public（根 `refreshRoleOverrideTab()` 调用）；行滚动一个 ScrollPane（wheel + 拖拽，真实夹紧）。
- `title()` "角色覆盖"。

- [ ] **Step 1: 编写 `RoleOverridePage.java`**（照上面；`rebuildRows`/`resolve*Status`/`renderRow`/`fit` 逻辑照抄旧文件）

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/RoleOverridePage.java
git commit -m "feat(modmenu): role override page"
```

---

### Task 12: 光影白名单页

**Files:**
- Create: `config/ShaderWhitelistPage.java`

**Interfaces:**
- Consumes: `ConfigPage`/`ConfigContext`、`widget/ToggleButton`/`ScrollPane`、`ConfigManager`。
- Produces: `ShaderWhitelistPage`。

**行为（移植旧 `client/gui/ShaderWhitelistScreen.java`）：**
- 总开关 ToggleButton → `setShaderWhitelistConfig(enabled, list)` + `ctx.markDirty()`。
- 添加框 + `+ 添加` 按钮（Enter 也触发）：去重大小写不敏感，重复 toast "§e该光影包已在白名单中"；添加后清空框 + markDirty。
- 行列表：编号、`§e📦` 图标、包名、悬停删除 `✕`（删除 + markDirty）。
- 页脚信息「已允许 N 个光影包 | 白名单状态: 启用/禁用」+「白名单外光影的玩家将被踢出服务器」。
- 空状态区分启用/禁用文案沿用旧。
- 滚动：一个 `ScrollPane`。
- `title()` "光影白名单"。

- [ ] **Step 1: 编写 `ShaderWhitelistPage.java`**（照上面；渲染/命中逻辑参考旧文件）

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL；`./gradlew clean build` + JAR 复制核对。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/ShaderWhitelistPage.java
git commit -m "feat(modmenu): shader whitelist page"
```

---

### Task 13: 根界面重写（ConfigRootScreen）+ 删除旧 GUI + 集成

**Files:**
- Rewrite: `config/ConfigRootScreen.java`
- Modify: 无外部改动（`ModMenuIntegration.java` 已指向 `ConfigRootScreen::new`，`RoleOverrideRefreshDispatcher.java:35` 已 instanceof 同包同类，均无需改）
- Delete: `config/TaskTabScreen.java`、`config/MinigameTabScreen.java`、`config/GlobalTabScreen.java`、`config/VoteTabScreen.java`、`config/EnvironmentTabScreen.java`、`config/RoleOverrideTabScreen.java`、`config/MapPoolEditorScreen.java`、`config/MinigameEditScreen.java`、`config/ModeAllowedMapsScreen.java`、`client/gui/TaskEditScreen.java`、`client/gui/ShaderWhitelistScreen.java`、`client/gui/TaskColorPicker.java`、`client/gui/TaskSaveController.java`、`client/gui/TaskMapFilterEditor.java`、旧 `config/ConfigRootScreen.java`（被重写替代）

**Interfaces:**
- Consumes: 全部 Task 1–12 产物。
- Produces: 新 `ConfigRootScreen`（实现 `ConfigContext`），保留 `ConfigRootScreen(Screen)`、`openVote(Screen)`、`refreshRoleOverrideTab()` 三个对外签名。

- [ ] **Step 1: 编写新 `ConfigRootScreen.java`**

```java
package com.habitrain.core.client.gui.config;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.config.widget.SaveBar;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * 配置中心根界面：左侧分类树 + 内容区 + 底部保存栏 + 页面栈。
 * 保留对外签名：ConfigRootScreen(Screen)、openVote(Screen)、refreshRoleOverrideTab()。
 */
public class ConfigRootScreen extends Screen implements ConfigContext {

    private static final int NAV_W = 188;
    private static final int TOP_H = 30;
    private static final int SAVE_BAR_H = 36;
    private static final int PAD = 8;

    private final Screen parent;
    private final boolean remoteEditable;
    private final Deque<ConfigPage> pageStack = new ArrayDeque<>();
    private final List<NavCategory> categories = new ArrayList<>();
    private final SaveBar saveBar;
    private String initialPageId = null;
    private String selectedPageId = null;
    private ConfigPage selectedPage = null;

    private record NavEntry(String id, Component title, Supplier<ConfigPage> factory) {}
    private record NavCategory(Component title, int accent, List<NavEntry> entries) {}

    public ConfigRootScreen(Screen parent) {
        super(Component.literal("哈比列车核心 — 配置中心"));
        this.parent = parent;
        this.remoteEditable = LiveConfigAccess.canEditRemoteConfigs();
        this.saveBar = new SaveBar(font, remoteEditable, this::onSavePressed, this::requestBack);
        buildNavigation();
    }

    /** 打开配置中心并定位到投票/地图池页（彩票中心桥接入口）。 */
    public static ConfigRootScreen openVote(Screen parent) {
        ConfigRootScreen screen = new ConfigRootScreen(parent);
        screen.initialPageId = "vote";
        return screen;
    }

    // ---------- ConfigContext ----------

    @Override public Font font() { return font; }
    @Override public boolean editable() { return remoteEditable; }
    @Override public boolean isDirty() { return ConfigManager.getInstance().isDirty(); }
    @Override public void markDirty() { ConfigManager.getInstance().markEnvironmentDirty(); }
    @Override public void saveNow() { ConfigManager.getInstance().save(); }
    @Override public void toast(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(message), true);
        }
    }

    @Override
    public void pushPage(ConfigPage page) {
        pageStack.push(page);
        page.onEnter();
    }

    @Override
    public void popPage() {
        if (pageStack.isEmpty()) return;
        ConfigPage top = pageStack.pop();
        top.onLeave();
        if (!pageStack.isEmpty()) pageStack.peek().onEnter();
    }

    @Override
    public void requestBack() {
        if (!pageStack.isEmpty()) popPage();
        else onClose();
    }

    /** 服务器权威同步后刷新角色覆盖页（RoleOverrideRefreshDispatcher 调用，签名保留）。 */
    public void refreshRoleOverrideTab() {
        if (selectedPage instanceof RoleOverridePage rop) rop.rebuildRows();
    }

    // ---------- 导航构建 ----------

    private void buildNavigation() {
        NavCategory tasks = new NavCategory(Component.literal("任务中心"), 0xFF57C6D6, List.of(
                new NavEntry("tasks_builtin", Component.literal("内置任务"), () -> new TaskListView(this, TaskListView.Scope.BUILTIN)),
                new NavEntry("tasks_blackout", Component.literal("停电任务"), () -> new TaskListView(this, TaskListView.Scope.BLACKOUT)),
                new NavEntry("tasks_dlc", Component.literal("扩展任务"), () -> new TaskListView(this, TaskListView.Scope.DLC)),
                new NavEntry("minigames", Component.literal("小游戏"), MinigamesPage::new),
                new NavEntry("task_global", Component.literal("任务全局参数"), TaskGlobalPage::new)));
        NavCategory flow = new NavCategory(Component.literal("对局流程"), 0xFF8B6B47, List.of(
                new NavEntry("vote", Component.literal("投票设置"), VoteSettingsPage::new),
                new NavEntry("map_pool", Component.literal("地图池轮换"), MapPoolPage::new),
                new NavEntry("gameplay_global", Component.literal("全局玩法参数"), GameplayGlobalPage::new)));
        NavCategory env = new NavCategory(Component.literal("环境"), 0xFF55C28A, List.of(
                new NavEntry("env_lobby", Component.literal("大厅环境"), LobbyEnvPage::new),
                new NavEntry("env_match", Component.literal("对局环境"), MatchEnvPage::new),
                new NavEntry("env_post", Component.literal("局后时间"), PostMatchEnvPage::new),
                new NavEntry("env_rain", Component.literal("动态雨"), RainEnvPage::new)));
        NavCategory role = new NavCategory(Component.literal("角色"), 0xFFD45A5A, List.of(
                new NavEntry("role_overrides", Component.literal("角色覆盖"), RoleOverridePage::new)));
        NavCategory system = new NavCategory(Component.literal("系统"), 0xFF7C9CFF, List.of(
                new NavEntry("shader", Component.literal("光影白名单"), ShaderWhitelistPage::new)));
        categories.add(tasks); categories.add(flow); categories.add(env); categories.add(role); categories.add(system);
    }

    @Override
    protected void init() {
        super.init();
        if (selectedPage == null) {
            if (initialPageId != null) {
                selectPageById(initialPageId);
            } else {
                selectPageById(categories.get(0).entries().get(0).id());
            }
        }
    }

    private void selectPageById(String id) {
        if (selectedPage != null) selectedPage.onLeave();
        for (NavCategory cat : categories) {
            for (NavEntry entry : cat.entries()) {
                if (entry.id().equals(id)) {
                    selectedPageId = id;
                    selectedPage = entry.factory().get();
                    selectedPage.onEnter();
                    return;
                }
            }
        }
    }

    private void onSavePressed() {
        ConfigPage page = currentPage();
        if (page != null) page.onSaveRequested();
        saveNow();
        toast("§a配置已保存");
    }

    // ---------- 渲染 ----------

    private ConfigPage currentPage() {
        return pageStack.isEmpty() ? selectedPage : pageStack.peek();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        SharedGuiKit.drawBackdrop(g, width, height, 0xFF57C6D6);

        int contentY = TOP_H + PAD;
        int contentH = height - contentY - SAVE_BAR_H - PAD;
        boolean subPage = !pageStack.isEmpty();

        if (subPage) {
            // 面包屑 + 返回
            String breadcrumb = breadcrumbText();
            int bw = font.width(breadcrumb);
            g.fill(0, 0, width, TOP_H, 0xFF141820);
            g.drawString(font, breadcrumb, PAD + 2, (TOP_H - font.lineHeight) / 2, 0xFFFFFFFF, false);
            g.fill(0, TOP_H, width, TOP_H + 1, 0x30FFFFFF);
            contentY = TOP_H + PAD;
            contentH = height - contentY - SAVE_BAR_H - PAD;
            ConfigPage page = currentPage();
            if (page != null) {
                g.enableScissor(0, contentY, width, contentY + contentH);
                page.render(g, mx, my, delta, PAD, contentY, width - PAD * 2, contentH);
                g.disableScissor();
            }
        } else {
            // 标题栏
            g.fill(0, 0, width, TOP_H, 0xFF141820);
            g.drawString(font, "§l哈比列车核心 · 配置中心", PAD + 2, (TOP_H - font.lineHeight) / 2, 0xFFFFFFFF, false);
            g.fill(0, TOP_H, width, TOP_H + 1, 0x30FFFFFF);

            // 左侧分类树
            int navY = TOP_H + 6;
            int navH = height - navY - SAVE_BAR_H - PAD;
            g.enableScissor(0, navY, NAV_W, navY + navH);
            for (NavCategory cat : categories) {
                g.drawString(font, cat.title(), 10, navY, cat.accent(), false);
                navY += 16;
                for (NavEntry entry : cat.entries()) {
                    boolean selected = entry.id().equals(selectedPageId);
                    boolean hover = SharedGuiKit.inBounds(mx, my, 0, navY, NAV_W, 24);
                    int bg = selected ? SharedGuiKit.BG_ROW_SELECTED : (hover ? SharedGuiKit.BG_ROW_HOVER : 0x00000000);
                    g.fill(0, navY, NAV_W, navY + 24, bg);
                    if (selected) g.fill(0, navY, 3, navY + 24, cat.accent());
                    g.drawString(font, entry.title(), 12, navY + (24 - font.lineHeight) / 2,
                            selected ? 0xFFFFFFFF : SharedGuiKit.TEXT_PRIMARY, false);
                    navY += 24;
                }
                navY += 6;
            }
            g.disableScissor();

            // 内容区
            int contentX = NAV_W + PAD;
            int contentW = width - contentX - PAD;
            ConfigPage page = currentPage();
            if (page != null) {
                g.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
                page.render(g, mx, my, delta, contentX, contentY, contentW, contentH);
                g.disableScissor();
            }
        }

        // 底部保存栏
        saveBar.setDirty(isDirty());
        saveBar.setBackEnabled(!pageStack.isEmpty());
        saveBar.render(g, PAD, width, height - SAVE_BAR_H, SAVE_BAR_H, mx, my);

        if (!remoteEditable) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改配置"),
                    PAD, height - 12, 0xFF5555, false);
        }
    }

    private String breadcrumbText() {
        StringBuilder sb = new StringBuilder("‹ 返回");
        if (selectedPage != null) {
            sb.append("  ·  ").append(selectedPage.title().getString());
        }
        for (ConfigPage p : pageStack) {
            sb.append(" › ").append(p.title().getString());
        }
        return sb.toString();
    }

    // ---------- 输入 ----------

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        boolean subPage = !pageStack.isEmpty();
        // 顶部返回（子页面）
        if (subPage && my < TOP_H) {
            popPage();
            return true;
        }
        // 导航树（非子页面）
        if (!subPage && mx < NAV_W && my >= TOP_H) {
            int navY = TOP_H + 6;
            for (NavCategory cat : categories) {
                navY += 16;
                for (NavEntry entry : cat.entries()) {
                    if (SharedGuiKit.inBounds(mx, my, 0, navY, NAV_W, 24)) {
                        if (entry.id().equals(selectedPageId)) return true;
                        selectPageById(entry.id());
                        return true;
                    }
                    navY += 24;
                }
                navY += 6;
            }
            return true;
        }
        // 内容区
        ConfigPage page = currentPage();
        if (page != null && !subPage) {
            int contentX = NAV_W + PAD;
            int contentY = TOP_H + PAD;
            int contentW = width - contentX - PAD;
            int contentH = height - contentY - SAVE_BAR_H - PAD;
            if (my >= contentY && my < contentY + contentH) {
                if (page.mouseClicked(mx, my, btn, contentX, contentY, contentW, contentH)) return true;
            }
        } else if (page != null) {
            int contentY = TOP_H + PAD;
            int contentH = height - contentY - SAVE_BAR_H - PAD;
            if (my >= contentY && my < contentY + contentH) {
                if (page.mouseClicked(mx, my, btn, PAD, contentY, width - PAD * 2, contentH)) return true;
            }
        }
        // 底部保存栏
        if (saveBar.handleClick(mx, my)) return true;
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        ConfigPage page = currentPage();
        if (page == null) return false;
        if (!pageStack.isEmpty()) {
            int contentY = TOP_H + PAD;
            int contentH = height - contentY - SAVE_BAR_H - PAD;
            return page.mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD * 2, contentH);
        }
        int contentX = NAV_W + PAD;
        int contentY = TOP_H + PAD;
        int contentW = width - contentX - PAD;
        int contentH = height - contentY - SAVE_BAR_H - PAD;
        return page.mouseDragged(mx, my, btn, dx, dy, contentX, contentY, contentW, contentH);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        ConfigPage page = currentPage();
        if (page == null) return false;
        if (!pageStack.isEmpty()) {
            int contentY = TOP_H + PAD;
            int contentH = height - contentY - SAVE_BAR_H - PAD;
            return page.mouseReleased(mx, my, btn, PAD, contentY, width - PAD * 2, contentH);
        }
        int contentX = NAV_W + PAD;
        int contentY = TOP_H + PAD;
        int contentW = width - contentX - PAD;
        int contentH = height - contentY - SAVE_BAR_H - PAD;
        return page.mouseReleased(mx, my, btn, contentX, contentY, contentW, contentH);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        ConfigPage page = currentPage();
        if (page == null) return false;
        if (!pageStack.isEmpty()) {
            int contentY = TOP_H + PAD;
            int contentH = height - contentY - SAVE_BAR_H - PAD;
            return page.mouseScrolled(mx, my, sx, sy, PAD, contentY, width - PAD * 2, contentH);
        }
        int contentX = NAV_W + PAD;
        int contentY = TOP_H + PAD;
        int contentW = width - contentX - PAD;
        int contentH = height - contentY - SAVE_BAR_H - PAD;
        return page.mouseScrolled(mx, my, sx, sy, contentX, contentY, contentW, contentH);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        ConfigPage page = currentPage();
        if (page != null && page.keyPressed(key, scan, mod)) return true;
        if (key == 256) { // ESC
            requestBack();
            return true;
        }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        ConfigPage page = currentPage();
        if (page != null && page.charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    @Override
    public void onClose() {
        ConfigPage page = currentPage();
        if (page != null) page.onLeave();
        saveNow();
        Minecraft.getInstance().setScreen(parent);
    }
}
```

> 说明：`ConfigContext` 接口只暴露 `font()/editable()/isDirty()/markDirty()/saveNow()/pushPage()/popPage()/requestBack()/toast()`，未含 `refreshRoleOverrideTab()`（根专用，页面不依赖）。`init()` 中 `font` 字段是 `Screen` 超类在 `init()` 前注入的——`saveBar` 在构造器里用 `font` 可能为 null。**修正**：`SaveBar` 持有 `Font` 引用，若构造时 font 为 null 则不可用。改为 `SaveBar` 构造时不接收 font，在 `render` 时由根传入（见 Task 3 Step 3 备注）。因此上面 `saveBar` 字段改为：
```java
private final SaveBar saveBar = new SaveBar(remoteEditable, this::onSavePressed, this::requestBack);
// SaveBar 签名：(boolean editable, Runnable onSave, Runnable onBack)，render(GuiGraphics g, Font font, int x, int width, int y, int h, int mx, int my)
```
`ConfigRootScreen.render` 中调用改为 `saveBar.render(g, font, PAD, width, height - SAVE_BAR_H, SAVE_BAR_H, mx, my);`。其他页面使用 `font()` 前确保在 `init()` 之后（页面由根在 init 时创建，`font` 已就绪）。

- [ ] **Step 2: 调整 Task 3 的 `SaveBar` 签名为上述（editable, onSave, onBack）+ render 传 font**

- [ ] **Step 3: 删除旧 GUI 类**（上表 15 个文件；`git rm`）。确认无其他代码引用：`ModMenuIntegration.java`（`ConfigRootScreen::new`，同包同签名，无需改）、`RoleOverrideRefreshDispatcher.java:35`（同包同类 instanceof，无需改）。

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL（若报缺失引用，是旧代码交叉引用的遗漏——按报错逐一确认：旧 Tab 类之间互相 new 属预期删除；若外部文件引用了被删旧类，停下向 Mike 报告，不要擅自改非 GUI 文件）。

- [ ] **Step 5: 全量验证**

Run: `./gradlew clean build` → BUILD SUCCESSFUL（含 api 测试）；复制 `build/libs/habitrain_core-2.0.1.jar` 到 `D:\Backup\mc mod\临时\`，核对文件名/字节长度/SHA-256。

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/habitrain/core/client/gui/config src/main/java/com/habitrain/core/client/gui/TaskEditScreen.java \
        src/main/java/com/habitrain/core/client/gui/ShaderWhitelistScreen.java src/main/java/com/habitrain/core/client/gui/TaskColorPicker.java \
        src/main/java/com/habitrain/core/client/gui/TaskSaveController.java src/main/java/com/habitrain/core/client/gui/TaskMapFilterEditor.java
git commit -m "feat(modmenu): rewrite config root with nav tree, page stack, save bar; remove legacy GUI"
```

---

### Task 14: 最终验证 + 记忆文档 + 游戏内清单

**Files:**
- Modify: `.claude/memory/mod-architecture.md`（第 11 节 GUI 类名清单）
- Modify: `.claude/memory/maintenance-log.md`（追加本会话条目）
- 无源码改动

- [ ] **Step 1: 更新 `mod-architecture.md` 第 11 节**

将「ModMenu 根：`client/gui/ModMenuIntegration.java:9-14`；配置页面：`ConfigRootScreen`、`TaskTabScreen`、`MinigameTabScreen`、`GlobalTabScreen`、`EnvironmentTabScreen`、`VoteTabScreen`、`MapPoolEditorScreen`、`RoleOverrideTabScreen`」替换为：

> ModMenu 根：`client/gui/ModMenuIntegration.java:9-14`；配置根界面 `ConfigRootScreen`（2026-07-31 重写：左侧分类树 + 页面栈 + 底部 SaveBar，实现 `ConfigContext`，保留 `openVote`/`refreshRoleOverrideTab`）。页面：`TaskListView`(Scope BUILTIN/BLACKOUT/DLC)、`TaskEditPage`、`TaskGlobalPage`、`GameplayGlobalPage`、`MinigamesPage`、`MinigameEditPage`、`VoteSettingsPage`、`ModeAllowedMapsPage`、`MapPoolPage`、`EnvProfileEditor`、`LobbyEnvPage`、`MatchEnvPage`、`PostMatchEnvPage`、`RainEnvPage`、`RoleOverridePage`、`ShaderWhitelistPage`；控件 `config/widget/*`（ScrollPane/ToggleButton/NumberField/SliderRow/ColorCyclePicker/MapFilterField/SaveBar）。保存语义：改即生效（多数 markDirty）+ 底部「保存」落盘；角色覆盖/投票顺序/地图池立即落盘（保留）；关闭仍自动保存。

- [ ] **Step 2: 追加 `maintenance-log.md` 条目**

如实记录：本轮 14 个任务、build 成功/失败、JAR 源/目标/字节数/SHA-256、删除了哪些旧 GUI 类、`ConfigStore`/`ConfigManager` 各加 `isDirty()` 只读访问器、MapPoolPage 改为工作副本编辑（行为改进）、ScrollPane 修复旧 quirk。

- [ ] **Step 3: 最终 build + JAR 交付**

Run: `./gradlew clean build`；复制 JAR 到 `D:\Backup\mc mod\临时\`，核对 SHA-256。Game 内验证需 Mike 运行客户端，下方为验证清单。

- [ ] **Step 4: 提交记忆文档**

```bash
git add .claude/memory/mod-architecture.md .claude/memory/maintenance-log.md
git commit -m "docs: update architecture memory and maintenance log for modmenu rewrite"
```

- [ ] **Step 5: 交付给 Mike 的游戏内验证清单**

逐项核对（在 ModMenu 打开配置中心）：
1. 左侧 5 分类树展开/收起，5 分类 14 页均可点击进入，右侧内容对应。
2. 每页底部有「保存」；改任意开关后「● 有未保存的修改」点亮；点「保存」后熄灭并出现 toast。
3. 只读模式（联机非 OP）：控件全禁用 + 拒绝提示 + 底部只读提示。
4. 内置/停电/扩展任务：搜索、分区、启用开关、编辑进入任务详情、详情内保存/保存并返回/重置默认；停电商店售价仅停电任务可见。
5. 小游戏：卡片网格、搜索、开关、编辑；无 SRE 时提示不可用。
6. 任务全局参数：DLC 滑块拖拽、小游戏总开关。
7. 投票设置：总开关、时长输入提交、模式/地图名称改名、顺序 ↑↓ 立即落盘、可选地图子页面保存、跳过当前池按钮。
8. 地图池轮换：添加/删除/重命名池、地图勾选、重新均摊分池、保存落盘、返回不保存即丢弃。
9. 全局玩法参数：警长除数/临时电源价格应用、刀耐久开关。
10. 环境 4 页：大厅/对局（地图覆盖增删）/局后时间/动态雨，各开关与数字字段。
11. 角色覆盖：总开关、逐行切换、冲突自动禁用同 target、即时落盘。
12. 光影白名单：总开关、添加/删除、重复 toast。
13. 子页面进出：任务详情/小游戏详情/可选地图 → 顶部「‹ 返回」/底部「返回上一页」回上一页；ESC 同样回退；最外层 ESC/返回回 ModMenu。
14. 关闭面板自动保存（无手动保存时关闭，配置落盘）。

---

## Self-Review

- **Spec 覆盖**：左侧分类树（Task 13）、14 页 + 2 子页（Task 4–12）、保存按钮（SaveBar + Task 13）、改即生效 + 手动保存（各页 markDirty + saveNow，保留立即落盘页面）、关闭自动保存（Task 13 onClose）、只读（Task 13 + 控件）、页面栈 + 面包屑（Task 13）、滚动 quirk 修复（ScrollPane）、release 全转发（根 mouseReleased 转所有页 + 各页 release 处理）。子编辑器页面压栈替代 setScreen：Task 4/6/7。`refreshRoleOverrideTab`/`openVote`/`ConfigRootScreen::new` 保留（Task 13）。
- **占位符扫描**：无 TBD/TODO；页面类给出行为定义 + 移植来源 + 机械改动规则（旧文件在仓库内为行为参考）。
- **类型一致性**：`ConfigContext` 方法签名在 Task 1 定义、Task 13 实现、各页使用一致；`SaveBar` 在 Task 3 定义（editable,onSave,onBack + render 传 font）与 Task 13 修正一致；`EnvProfileEditor.bindTime` 在 Task 9 实现、Task 10 使用；`TaskListView.Scope` Task 4 定义、Task 13 导航使用。`ConfigManager.isDirty()` Task 1 加、Task 13 `isDirty()` 用。
