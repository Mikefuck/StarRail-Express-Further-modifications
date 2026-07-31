# 配置中心四分类重写 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `habitrain_core` 旧配置中心（`com.habitrain.core.client.gui.config` + 相关 `client/gui` 文件）整体重写到全新包 `com.habitrain.core.client.gui.menu`：4 大分类 Tab（游戏内/游戏外/游戏模式/其他）+ 二级子 Tab + 固定底部保存栏，视觉与功能完全保真，旧文件移出项目备份到 `临时\旧配置GUI备份_2026-07-31\`。

**Architecture:** 新包以「可复用组件 + 统一 ConfigPage 接口」重建：`ConfigMenuScreen` 为瘦宿主（顶层 Tab → 子 Tab → 页面内容 + SaveBar），各功能组页面实现 `ConfigPage`，共享 `ScrollArea`/`SubTabBar`/`PillToggle`/`SliderRow`/`SaveBar` 等组件。行为与数据模型沿用旧版（即时生效 + `ConfigManager` 门面），保存按钮只做「提交未确认文本 + 写盘 + 提示」。

**Tech Stack:** Fabric Loom / MC 1.21.1 / Java 21 / Mojang mappings / 手绘 GuiGraphics（非 vanilla widget 体系）。

## 全局约束

- 项目：`D:\Backup\mc mod\哈比列车api`；构建：`./gradlew clean build`；产物：`build/libs/habitrain_core-2.0.1.jar`（唯一无 classifier 主 JAR）。
- 交付：JAR 复制到 `D:\Backup\mc mod\临时\`，比对文件名/字节长度/SHA-256；抽奖补齐 mod（改了桥接）同样构建并交付。
- 禁止访问 `D:\Backup\mc mod\backup\`；不得 reset/clean/覆盖工作树既有 staged/unstaged/deleted/untracked 改动。
- 旧配置 GUI 文件（19 个）最终**必须**整体 move 到 `D:\Backup\mc mod\临时\旧配置GUI备份_2026-07-31\`，api 内不保留。
- 每次任务结束提交 git（只 add 本任务文件）。编译完整性只在**集成任务**统一验证：根屏引用所有页面类，中途 `compileJava` 会因「找不到尚未创建的页面」失败，属预期。
- 视觉常量与行为细节以**迁移备份文件**与设计文档 `docs/superpowers/specs/2026-07-31-modmenu-4category-rewrite-design.md` 第 6 节为准。

## 文件结构总览

**新包 `com.habitrain.core.client.gui.menu`**

| 文件 | 职责 |
|---|---|
| `ConfigMenuScreen.java` | 根：4 大 Tab → 子 Tab → 页面 + SaveBar；`openVote`/`refreshRoleOverrideTab`/`saveConfigNow` |
| `ConfigPage.java` | 页面接口（render/mouse/key/save/flushPending） |
| `MenuTheme.java` | 主题常量 + 绘制工具（替代 SharedGuiKit + SharedGuiConstants） |
| `MenuPermissions.java` | 权限助手（替代 LiveConfigAccess） |
| `ui/ScrollArea.java` | 可滚动内容区（滚动/拖拽/滚动条） |
| `ui/SubTabBar.java` | 二级子 Tab 条 |
| `ui/PillToggle.java` | 绿/红开关药丸渲染 + 命中 |
| `ui/SliderRow.java` | DLC 概率滑块（拖拽/渐变/刻度） |
| `ui/SectionHeader.java` | 节标题 + 分隔线 |
| `ui/EditRow.java` | 文本框布局渲染 |
| `ui/SaveBar.java` | 固定底部保存栏 |
| `page/InGameBalancePage.java` | 游戏内·数值平衡 |
| `page/InGameMinigamesPage.java` | 游戏内·小游戏 |
| `page/InGameEnvPage.java` | 游戏内·环境（对局/局后/动态雨） |
| `page/OutGameVotePage.java` | 游戏外·投票（主设置/池轮换/模式/地图） |
| `page/OutGameLobbyEnvPage.java` | 游戏外·大厅环境 |
| `page/OutGameShaderPage.java` | 游戏外·光影白名单（内联） |
| `page/ModeTasksPage.java` | 游戏模式·任务配置 |
| `page/ModeRolesPage.java` | 游戏模式·角色覆盖 |
| `page/OtherPage.java` | 其他·空态 |
| `TaskEditScreen.java` | 任务详细编辑（重写） |
| `MinigameEditScreen.java` | 小游戏详细编辑（重写） |
| `MapPoolEditorScreen.java` | 地图池编辑（重写） |
| `ModeAllowedMapsScreen.java` | 模式可选地图（重写） |
| `ShaderWhitelistScreen.java` | 光影白名单子屏（保留，供兼容；页内已内联，此屏保留以防旧引用） |
| `TaskColorPicker.java` / `TaskMapFilterEditor.java` / `TaskSaveController.java` | 任务编辑辅助（重写） |

**同批修改（api 内）**

| 文件 | 改动 |
|---|---|
| `client/gui/ModMenuIntegration.java` | 指向 `ConfigMenuScreen` |
| `client/role/RoleOverrideRefreshDispatcher.java` | `instanceof ConfigMenuScreen` |
| `client/ClientLifecycleHandler.java` | `import menu.MenuPermissions` |

**同批修改（抽奖补齐 mod，仅 1 处）**

| 文件 | 改动 |
|---|---|
| `哈比列车抽奖补齐/src/main/java/com/habitrain/lottery/meta/HabiCoreMenuBridge.java` | 反射 FQCN → `...menu.ConfigMenuScreen` |

**迁移备份（move，保留相对包路径）** → `D:\Backup\mc mod\临时\旧配置GUI备份_2026-07-31\`
`client/gui/config/`（11）：`ConfigRootScreen` `TaskTabScreen` `MinigameTabScreen` `GlobalTabScreen` `VoteTabScreen` `EnvironmentTabScreen` `RoleOverrideTabScreen` `MapPoolEditorScreen` `MinigameEditScreen` `ModeAllowedMapsScreen` `SharedGuiKit`
`client/gui/`（8）：`ModMenuIntegration` `LiveConfigAccess` `TaskEditScreen` `TaskSaveController` `TaskColorPicker` `TaskMapFilterEditor` `ShaderWhitelistScreen` `SharedGuiConstants`

---

## Task 1: 移动旧配置 GUI 文件到临时备份

**Files:**
- Move: 上述 19 个旧文件 → `D:\Backup\mc mod\临时\旧配置GUI备份_2026-07-31\`

**Interfaces:**
- Consumes: 旧文件当前工作树状态（含未提交改动，原样保留）。
- Produces: 空目录 `哈比列车api/src/main/java/com/habitrain/core/client/gui/config/`（删除空目录）；`client/gui/` 只剩非菜单类。

- [ ] **Step 1: 创建备份根目录**

```bash
mkdir -p "D:/Backup/mc mod/临时/旧配置GUI备份_2026-07-31"
```

- [ ] **Step 2: 用 git mv 语义迁移并保留相对路径**

先在 api 仓库 `git add` 全部 19 个文件（确保未提交改动进 index），再 `git mv` 到备份目录（git mv 保留内容与历史路径）：

```bash
cd "D:/Backup/mc mod/哈比列车api"
# git add 待迁移文件（含工作树改动）
git add src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/TaskTabScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/MinigameTabScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/GlobalTabScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/VoteTabScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/EnvironmentTabScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/RoleOverrideTabScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/MapPoolEditorScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/MinigameEditScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/ModeAllowedMapsScreen.java \
        src/main/java/com/habitrain/core/client/gui/config/SharedGuiKit.java \
        src/main/java/com/habitrain/core/client/gui/ModMenuIntegration.java \
        src/main/java/com/habitrain/core/client/gui/LiveConfigAccess.java \
        src/main/java/com/habitrain/core/client/gui/TaskEditScreen.java \
        src/main/java/com/habitrain/core/client/gui/TaskSaveController.java \
        src/main/java/com/habitrain/core/client/gui/TaskColorPicker.java \
        src/main/java/com/habitrain/core/client/gui/TaskMapFilterEditor.java \
        src/main/java/com/habitrain/core/client/gui/ShaderWhitelistScreen.java \
        src/main/java/com/habitrain/core/client/gui/SharedGuiConstants.java
```

（保留旧的 `docs/superpowers/...` 已暂存删除不在此列——那是既有状态，不归本任务。）

- [ ] **Step 3: 把文件 move 到备份目录（保留包相对路径）**

用 PowerShell 逐文件 `Move-Item`（剪切）到 `旧配置GUI备份_2026-07-31\com\habitrain\core\client\gui\config\` 与 `...\gui\` 对应子路径。例：

```powershell
$src = "D:\Backup\mc mod\哈比列车api\src\main\java\com\habitrain\core\client\gui"
$dst = "D:\Backup\mc mod\临时\旧配置GUI备份_2026-07-31\com\habitrain\core\client\gui"
New-Item -ItemType Directory -Force "$dst\config"
$files = @(
  "config\ConfigRootScreen.java","config\TaskTabScreen.java","config\MinigameTabScreen.java",
  "config\GlobalTabScreen.java","config\VoteTabScreen.java","config\EnvironmentTabScreen.java",
  "config\RoleOverrideTabScreen.java","config\MapPoolEditorScreen.java","config\MinigameEditScreen.java",
  "config\ModeAllowedMapsScreen.java","config\SharedGuiKit.java",
  "ModMenuIntegration.java","LiveConfigAccess.java","TaskEditScreen.java","TaskSaveController.java",
  "TaskColorPicker.java","TaskMapFilterEditor.java","ShaderWhitelistScreen.java","SharedGuiConstants.java"
)
foreach ($f in $files) { Move-Item "$src\$f" "$dst\$f" -Force }
Remove-Item "$src\config" -Force   # 删除空 config 目录
```

- [ ] **Step 4: 在 git 里登记删除**

```bash
git add -A src/main/java/com/habitrain/core/client/gui
git status   # 19 个文件显示为 deleted（已暂存）
```

- [ ] **Step 5: 提交**

```bash
git commit -m "refactor(gui): 旧配置 GUI 迁移备份至临时/旧配置GUI备份_2026-07-31，api 内移除旧文件"
```

> 说明：此提交后 api **不再编译**（`ModMenuIntegration`/`RoleOverrideRefreshDispatcher`/`ClientLifecycleHandler` 仍引用已删类），直到 Task 5 切换外部引用、Task 4/6 建立根屏后才能恢复。这是方案 C 的预期状态。

---

## Task 2: MenuTheme + MenuPermissions

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/MenuTheme.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/MenuPermissions.java`

**Interfaces:**
- Consumes: 无（纯静态工具）。
- Produces: `MenuTheme`（常量与绘制）、`MenuPermissions`（权限）—— 所有后续组件/页面引用。

- [ ] **Step 1: 实现 MenuTheme**

```java
package com.habitrain.core.client.gui.menu;

import net.minecraft.client.gui.GuiGraphics;

/** 配置中心主题常量与绘制工具（替代旧 SharedGuiKit + SharedGuiConstants）。 */
public final class MenuTheme {
    private MenuTheme() {}

    public static final int BG_DARK = 0xFF12161D;
    public static final int BG_PANEL = 0xFF151A20;
    public static final int BG_ROW = 0xFF1B222B;
    public static final int BG_ROW_HOVER = 0xFF222B36;
    public static final int BG_ROW_SELECTED = 0xFF2A3440;
    public static final int TEXT_PRIMARY = 0xFFE8E8E8;
    public static final int TEXT_SECONDARY = 0xFF8A92A0;
    public static final int ACCENT_CYAN = 0xFF57C6D6;
    public static final int ACCENT_BROWN = 0xFF8B6B47;
    public static final int SEPARATOR = 0x30FFFFFF;
    public static final int BG_ENABLED = 0xFF1B3A2A;
    public static final int BG_DISABLED = 0xFF3A1B1B;
    public static final int BG_EDIT = 0xFF222B36;

    private static final int[] BASE_COLORS_RGB = {
        0xFF0000, 0xFF7F00, 0xFFFF00, 0x00FF00,
        0x0000FF, 0x8B00FF, 0xFF00FF, 0x00FFFF,
        0xFFC0CB, 0xFFA500, 0xC0C0C0, 0xFFFFFF,
        0xFF6B6B, 0xFFD700, 0x7CFC00, 0x00FA9A,
        0x6020F0, 0xFF1493, 0x00CED1, 0xFF8C00
    };
    public static final String[] COLOR_NAMES = {
        "红色","橙色","黄色","绿色","蓝色","紫色","品红色","青色",
        "粉色","琥珀色","银色","白色","珊瑚色","金色","草绿色","碧绿色",
        "紫罗兰","深粉色","深蓝色","亮橙色"
    };

    public static int getColor(int index, int alpha) { return (alpha << 24) | (BASE_COLORS_RGB[index] & 0xFFFFFF); }
    public static int getColorCount() { return BASE_COLORS_RGB.length; }

    public static void drawBackdrop(GuiGraphics g, int width, int height, int accent) {
        g.fill(0, 0, width, height, BG_DARK);
        g.fill(0, 0, width, 3, accent);
        g.fill(0, 3, width, 4, 0x408B6B47);
    }

    public static void drawAccentStripe(GuiGraphics g, int x, int y, int h, int color) {
        g.fill(x, y, x + 3, y + h, color);
    }

    public static void drawScrollbar(GuiGraphics g, int x, int y, int h, double scroll, double maxScroll, int trackW) {
        g.fill(x, y, x + trackW, y + h, 0x20FFFFFF);
        if (maxScroll <= 0) return;
        double ratio = scroll / maxScroll;
        int thumbH = Math.max(20, (int) (h * (h / (h + maxScroll))));
        int thumbY = y + (int) ((h - thumbH) * ratio);
        g.fill(x, thumbY, x + trackW, thumbY + thumbH, 0x60FFFFFF);
    }

    public static boolean inBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static int accentFor(String key) {
        int[] palette = {
            0xFF57C6D6, 0xFF8B6B47, 0xFFD4A55A, 0xFF6B8BD4,
            0xFFD46B6B, 0xFF6BD48B, 0xFFB06BD4, 0xFFD4B06B
        };
        int hash = key == null ? 0 : Math.abs(key.hashCode());
        return palette[hash % palette.length];
    }
}
```

- [ ] **Step 2: 实现 MenuPermissions**

```java
package com.habitrain.core.client.gui.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客户端配置编辑权限助手（替代旧 LiveConfigAccess）。 */
public final class MenuPermissions {
    private MenuPermissions() {}

    public static boolean canEditRemoteConfigs() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return true;
        if (mc.getConnection() == null) return true;
        if (mc.getSingleplayerServer() != null) return true;
        return mc.player != null && mc.player.hasPermissions(4);
    }

    public static void showDeniedMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c只有 OP 才能修改联机服务器配置"), true);
        }
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/MenuTheme.java src/main/java/com/habitrain/core/client/gui/menu/MenuPermissions.java
git commit -m "feat(gui/menu): MenuTheme 主题常量与 MenuPermissions 权限助手"
```

---

## Task 3: 共享 UI 组件

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ui/ScrollArea.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ui/SubTabBar.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ui/PillToggle.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ui/SliderRow.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ui/SectionHeader.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ui/SaveBar.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ui/EditRow.java`

**Interfaces:**
- Consumes: `MenuTheme`, `MenuPermissions`.
- Produces: `ScrollArea#(setContentHeight/getContentY/reset/isInside/render/mouseClicked/mouseDragged/mouseReleased/mouseScrolled)`；`SubTabBar#(render→hitIndex/getHeight)`；`PillToggle#render/hit`（静态）；`SliderRow#(render/mouseClicked/mouseDragged/mouseReleased/valueFromMouse)`；`SectionHeader#render`（静态）；`EditRow#render`（静态）；`SaveBar#(render/mouseClicked/HEIGHT)`。

- [ ] **Step 1: ScrollArea**

```java
package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** 可滚动内容区：负责滚动偏移、拖拽、滚轮与滚动条。 */
public class ScrollArea {
    private final int x, y, w, h;
    private double scroll;
    private boolean dragging;
    private double dragStartY, dragStartScroll;
    private int contentHeight;

    public ScrollArea(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    public void setContentHeight(int contentHeight) {
        this.contentHeight = contentHeight;
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - h));
    }
    public int getContentY() { return y - (int) scroll; }
    public void reset() { scroll = 0; }
    public boolean isInside(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    public int maxScroll() { return Math.max(0, contentHeight - h); }

    public void render(GuiGraphics g) {
        MenuTheme.drawScrollbar(g, x + w - 4, y, h, scroll, maxScroll(), 3);
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (!isInside(mx, my)) return false;
        dragging = true;
        dragStartY = my;
        dragStartScroll = scroll;
        return true;
    }
    public boolean mouseDragged(double my) {
        if (!dragging) return false;
        scroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, maxScroll());
        return true;
    }
    public boolean mouseReleased() {
        if (!dragging) return false;
        dragging = false;
        return true;
    }
    public boolean mouseScrolled(double sy) {
        scroll = Mth.clamp(scroll - sy * 18, 0, maxScroll());
        return true;
    }
}
```

- [ ] **Step 2: SubTabBar**

```java
package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 二级子 Tab 条：等宽绘制，选中用 accent 底色；render 返回悬停下标或 -1。 */
public class SubTabBar {
    private final String[] labels;
    private final int[] accents;
    public static final int H = 24;

    public SubTabBar(String[] labels, int[] accents) { this.labels = labels; this.accents = accents; }

    public int getHeight() { return H; }
    public int count() { return labels.length; }

    /** 返回悬停下标（-1 表示无），调用方据此处理点击切换。 */
    public int render(GuiGraphics g, Font font, int x, int y, int w, int selected, int mx, int my) {
        int tabW = Math.max(60, (w - (labels.length - 1) * 2) / labels.length);
        int hit = -1;
        for (int i = 0; i < labels.length; i++) {
            int tx = x + i * (tabW + 2);
            boolean sel = i == selected;
            boolean hover = MenuTheme.inBounds(mx, my, tx, y, tabW, H);
            if (hover) hit = i;
            int bg = sel ? accents[i] : (hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
            g.fill(tx, y, tx + tabW, y + H, bg);
            int textW = font.width(labels[i]);
            g.drawString(font, labels[i], tx + (tabW - textW) / 2, y + 6,
                    sel ? 0xFF101410 : MenuTheme.TEXT_PRIMARY, false);
        }
        return hit;
    }
}
```

- [ ] **Step 3: PillToggle + SectionHeader + EditRow**

```java
package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 绿/红开关药丸：静态渲染 + 命中判断（页面自行收集命中矩形）。 */
public final class PillToggle {
    private PillToggle() {}

    public static void render(GuiGraphics g, Font font, int x, int y, int w, int h, boolean on, String onText, String offText) {
        g.fill(x, y, x + w, y + h, on ? MenuTheme.BG_ENABLED : MenuTheme.BG_DISABLED);
        String text = on ? onText : offText;
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - font.lineHeight) / 2, 0xFFFFFFFF, false);
    }
    public static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return MenuTheme.inBounds(mx, my, x, y, w, h);
    }
}
```

```java
package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 节标题 + 分隔线，返回下一行 y。 */
public final class SectionHeader {
    private SectionHeader() {}

    public static int render(GuiGraphics g, Font font, int x, int y, int w, String title, int accent) {
        g.fill(x - 2, y - 2, x + w + 2, y - 1, MenuTheme.SEPARATOR);
        y += 4;
        g.drawString(font, Component.literal(title), x, y, accent, false);
        return y + 16;
    }
}
```

```java
package com.habitrain.core.client.gui.menu.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

/** 文本框定位渲染助手。 */
public final class EditRow {
    private EditRow() {}

    public static void render(GuiGraphics g, int mx, int my, float delta, EditBox box, int x, int y, int w) {
        box.setX(x); box.setY(y); box.setWidth(w);
        box.render(g, mx, my, delta);
    }
}
```

- [ ] **Step 4: SliderRow**

```java
package com.habitrain.core.client.gui.menu.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** DLC 概率滑块：拖动即改值，页面写入配置。常量与旧 GlobalTabScreen 一致。 */
public class SliderRow {
    private static final int SLIDER_H = 12;
    private final float min, max, step;
    private boolean dragging;
    private int x, y, w;

    public SliderRow(float min, float max, float step) { this.min = min; this.max = max; this.step = step; }

    /** 渲染轨道/渐变/刻度/拇指/数值；返回当前值（拖动期间可能变化）。 */
    public float render(GuiGraphics g, Font font, int x, int y, int w, float value) {
        this.x = x; this.y = y; this.w = w;
        int trackTop = y + (SLIDER_H - 6) / 2;
        int trackBot = trackTop + 6;
        g.fill(x, trackTop, x + w, trackBot, 0x44FFFFFF);
        int tx = thumbX(value);
        float pct = (value - min) / (max - min);
        if (pct > 0.001f) {
            int color = pct < 0.25f ? 0xAAFF5555 : pct < 0.5f ? 0xAAFFAA00 : pct < 0.75f ? 0xAA55FF55 : 0xAA55AAFF;
            g.fill(x, trackTop, tx, trackBot, color);
        }
        int tc = dragging ? 0xFFFFFFFF : 0xCCFFFFFF;
        g.fill(tx - 5, y, tx + 5, y + SLIDER_H, tc);
        g.fill(tx - 2, y + 4, tx + 2, y + SLIDER_H - 4, 0xFF333333);
        for (int p = 10; p <= 80; p += 10) {
            float pf = (p / 100f - min) / (max - min);
            int px = x + (int) (pf * w);
            g.fill(px, trackBot + 2, px + 1, trackBot + 2 + (p == 50 ? 8 : 4), p == 50 ? 0x88FFFF00 : 0x44FFFFFF);
        }
        g.drawString(font, String.format("§6§l%d%%", Math.round(value * 100)), x + w + 8, y + 1, 0xFFFFFFFF, false);
        return value;
    }

    public boolean mouseClicked(double mx, double my) {
        if (mx < x - 4 || mx > x + w + 4 || my < y - 4 || my > y + SLIDER_H + 4) return false;
        dragging = true;
        return true;
    }
    public boolean mouseDragged() { return dragging; }
    public boolean mouseReleased() { if (!dragging) return false; dragging = false; return true; }

    public float valueFromMouse(double mx) {
        float rel = Mth.clamp((float) ((mx - x) / w), 0f, 1f);
        float raw = min + rel * (max - min);
        return Math.round(raw / step) * step;
    }
    private int thumbX(float value) {
        float pct = (value - min) / (max - min);
        return x + (int) (pct * w);
    }
}
```

- [ ] **Step 5: SaveBar**

```java
package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 固定底部保存栏。点击由 ConfigMenuScreen 分发到当前页 save()。 */
public class SaveBar {
    public static final int HEIGHT = 40;
    private static final int BTN_W = 90;
    private static final int BTN_X = 16;
    private final boolean enabled;

    public SaveBar(boolean enabled) { this.enabled = enabled; }

    public void render(GuiGraphics g, Font font, int width, int height, int mx, int my) {
        int y = height - HEIGHT;
        g.fill(0, y, width, height, 0xFF10141A);
        g.fill(0, y, width, y + 1, 0x30FFFFFF);
        int btnY = y + (HEIGHT - 20) / 2;
        boolean hover = enabled && MenuTheme.inBounds(mx, my, BTN_X, btnY, BTN_W, 20);
        g.fill(BTN_X, btnY, BTN_X + BTN_W, btnY + 20,
                enabled ? (hover ? 0xFF2A6B4A : 0xFF1B4A32) : 0xFF2A2A2A);
        g.drawString(font, "§a保存", BTN_X + (BTN_W - font.width("保存")) / 2, btnY + 6,
                enabled ? 0xFFFFFFFF : 0xFF666666, false);
        g.drawString(font, "§7修改即时生效；点击保存写入配置文件", BTN_X + BTN_W + 12, btnY + 6,
                MenuTheme.TEXT_SECONDARY, false);
        if (!enabled) {
            String ro = "§c只读模式：联机服务器中仅 OP 可修改";
            g.drawString(font, ro, width - font.width(ro) - 12, btnY + 6, 0xFF5555, false);
        }
    }

    public boolean mouseClicked(double mx, double my, int width, int height) {
        int y = height - HEIGHT;
        return enabled && MenuTheme.inBounds(mx, my, BTN_X, y + (HEIGHT - 20) / 2, BTN_W, 20);
    }
}
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/ui
git commit -m "feat(gui/menu/ui): 共享 UI 组件（ScrollArea/SubTabBar/PillToggle/SliderRow/SectionHeader/SaveBar/EditRow）"
```

---

## Task 4: ConfigPage 接口 + ConfigMenuScreen 根屏

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ConfigPage.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ConfigMenuScreen.java`

**Interfaces:**
- Consumes: `MenuTheme`, `MenuPermissions`, `ui/ScrollArea` 等；`ConfigManager`, `GameModeRegistry`, `TaskRegistry` 等数据门面。
- Produces: `ConfigPage` 接口；`ConfigMenuScreen#(openVote(Screen)/refreshRoleOverrideTab()/saveConfigNow()/getParent()/canEdit())`。

**页面注册表（本任务先以 TODO 占位创建，Task 6 起逐个替换为真实页面）：**

```java
pages[0] = { InGameMinigamesPage, InGameBalancePage, InGameEnvPage }
pages[1] = { OutGameVotePage, OutGameLobbyEnvPage, OutGameShaderPage }
pages[2] = { ModeTasksPage, ModeRolesPage }
pages[3] = { OtherPage }
```

> 根屏必须引用全部页面类才能编译；因此**中途无法单独编译**，最终在集成任务验证。

- [ ] **Step 1: ConfigPage 接口**

```java
package com.habitrain.core.client.gui.menu;

import net.minecraft.client.gui.GuiGraphics;

/** 配置中心子页接口。x,y,w,h 为内容区（不含顶部 Tab / 子 Tab / 底部保存栏）。 */
public interface ConfigPage {
    /** 是否有可修改选项（“其他”空态页返回 false → 不显示保存栏）。 */
    boolean canSave();
    /** 提交页面级待处理状态到配置模型（即时持久化；多数页面 no-op）。 */
    void save();
    /** 把聚焦/可编辑文本框写入配置模型（保存/切页/关闭前调用）。 */
    void flushPending();

    void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h);
    boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h);
    boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h);
    boolean mouseReleased(double mx, double my, int btn);
    boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h);
    boolean keyPressed(int key, int scan, int mod);
    boolean charTyped(char ch, int mod);
}
```

- [ ] **Step 2: ConfigMenuScreen（骨架 + Tab/子Tab/保存路由）**

```java
package com.habitrain.core.client.gui.menu;

import com.habitrain.core.client.gui.menu.page.*;
import com.habitrain.core.client.gui.menu.ui.SaveBar;
import com.habitrain.core.client.gui.menu.ui.SubTabBar;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 配置中心根屏：4 大分类 Tab → 二级子 Tab → 页面内容 + 固定底部保存栏。 */
public class ConfigMenuScreen extends Screen {

    public static final int TOP_IN_GAME = 0;
    public static final int TOP_OUT_GAME = 1;
    public static final int TOP_MODE = 2;
    public static final int TOP_OTHER = 3;

    private static final String[] TOP_LABELS = {"游戏内修改", "游戏外修改", "游戏模式修改", "其他修改"};
    private static final int[] TOP_ACCENTS = {0xFF55C28A, 0xFF7C9CFF, 0xFFD4A55A, 0xFF8A92A0};
    private static final String[][] SUB_LABELS = {
        {"小游戏", "数值平衡", "环境"},
        {"投票", "大厅环境", "光影白名单"},
        {"任务配置", "角色覆盖"},
        {}
    };
    private static final int TOP_TAB_H = 28;
    private static final int PAD = 8;

    private final Screen parent;
    private final boolean remoteEditable;

    private int topTab = TOP_IN_GAME;
    private int subTab = 0;

    private ConfigPage[][] pages;          // 懒初始化缓存
    private final SubTabBar[] subBars = new SubTabBar[4];
    private SaveBar saveBar;

    public ConfigMenuScreen(Screen parent) {
        super(Component.literal("哈比列车核心 — 配置中心"));
        this.parent = parent;
        this.remoteEditable = MenuPermissions.canEditRemoteConfigs();
        for (int i = 0; i < 4; i++) {
            if (SUB_LABELS[i].length > 0) subBars[i] = new SubTabBar(SUB_LABELS[i], TOP_ACCENTS);
        }
    }

    /** 抽奖桥接：直接打开投票页。 */
    public static ConfigMenuScreen openVote(Screen parent) {
        ConfigMenuScreen s = new ConfigMenuScreen(parent);
        s.topTab = TOP_OUT_GAME;
        s.subTab = 0; // 投票
        return s;
    }

    private ConfigPage[] ensurePages() {
        if (pages != null) return pages;
        pages = new ConfigPage[4][];
        pages[TOP_IN_GAME] = new ConfigPage[]{
            new InGameMinigamesPage(this, font, remoteEditable),
            new InGameBalancePage(this, font, remoteEditable),
            new InGameEnvPage(this, font, remoteEditable)
        };
        pages[TOP_OUT_GAME] = new ConfigPage[]{
            new OutGameVotePage(this, font, remoteEditable),
            new OutGameLobbyEnvPage(this, font, remoteEditable),
            new OutGameShaderPage(this, font, remoteEditable)
        };
        pages[TOP_MODE] = new ConfigPage[]{
            new ModeTasksPage(this, font, remoteEditable),
            new ModeRolesPage(this, font, remoteEditable)
        };
        pages[TOP_OTHER] = new ConfigPage[]{ new OtherPage(this, font, remoteEditable) };
        return pages;
    }

    private ConfigPage currentPage() {
        ConfigPage[][] p = ensurePages();
        if (topTab == TOP_OTHER) return p[TOP_OTHER][0];
        return p[topTab][subTab];
    }

    public Screen getParent() { return parent; }
    public boolean canEdit() { return remoteEditable; }
    public void saveConfigNow() { ConfigManager.getInstance().save(); }

    /** 角色覆盖配置同步后刷新列表。 */
    public void refreshRoleOverrideTab() {
        ConfigPage[][] p = ensurePages();
        if (p[TOP_MODE][1] instanceof ModeRolesPage roles) roles.rebuildRows();
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        MenuTheme.drawBackdrop(g, width, height, TOP_ACCENTS[topTab]);

        drawTopTabs(g, mx, my);

        ConfigPage page = currentPage();
        int contentY = TOP_TAB_H;
        int contentH = height - TOP_TAB_H;
        boolean showSaveBar = page.canSave();

        if (topTab != TOP_OTHER) {
            int subHit = subBars[topTab].render(g, font, PAD, TOP_TAB_H + 4, width - PAD * 2, subTab, mx, my);
            subHitThisFrame = subHit; // 存字段供 mouseClicked 使用（渲染时已算好命中）
            contentY += SubTabBar.H + 4;
            contentH -= SubTabBar.H + 4;
        }
        if (showSaveBar) contentH -= SaveBar.HEIGHT;

        g.enableScissor(PAD, contentY, width - PAD, contentY + contentH);
        page.render(g, mx, my, delta, PAD, contentY, width - PAD * 2, contentH);
        g.disableScissor();

        if (showSaveBar) {
            saveBar = new SaveBar(remoteEditable);
            saveBar.render(g, font, width, height, mx, my);
        } else {
            saveBar = null;
        }

        if (!remoteEditable && !page.canSave()) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改"),
                    PAD, height - 12, 0xFF5555, false);
        }
    }

    private int subHitThisFrame = -1;

    private void drawTopTabs(GuiGraphics g, int mx, int my) {
        int totalW = width - PAD * 2;
        int perTab = totalW / TOP_LABELS.length;
        for (int i = 0; i < TOP_LABELS.length; i++) {
            int x = PAD + i * perTab;
            int w = (i == TOP_LABELS.length - 1) ? (totalW - i * perTab) : perTab;
            boolean sel = i == topTab;
            boolean hover = MenuTheme.inBounds(mx, my, x, 0, w, TOP_TAB_H);
            g.fill(x, 0, x + w, TOP_TAB_H, sel ? 0xFF1B222B : (hover ? 0xFF1A1F26 : 0xFF141820));
            g.fill(x, TOP_TAB_H - 2, x + w, TOP_TAB_H, sel ? TOP_ACCENTS[i] : 0x20FFFFFF);
            int textW = font.width(TOP_LABELS[i]);
            g.drawString(font, TOP_LABELS[i], x + (w - textW) / 2, (TOP_TAB_H - font.lineHeight) / 2,
                    sel ? 0xFFFFFFFF : 0xFF8A92A0, false);
        }
        g.fill(0, TOP_TAB_H, width, TOP_TAB_H + 1, 0x30FFFFFF);
    }

    // ---------------- 输入 ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (my < TOP_TAB_H) {
            int totalW = width - PAD * 2;
            int perTab = totalW / TOP_LABELS.length;
            for (int i = 0; i < TOP_LABELS.length; i++) {
                int x = PAD + i * perTab;
                int w = (i == TOP_LABELS.length - 1) ? (totalW - i * perTab) : perTab;
                if (MenuTheme.inBounds(mx, my, x, 0, w, TOP_TAB_H)) {
                    if (i != topTab) { currentPage().flushPending(); }
                    topTab = i;
                    subTab = 0;
                    return true;
                }
            }
            return true;
        }
        if (topTab != TOP_OTHER && subHitThisFrame >= 0) {
            int y = TOP_TAB_H + 4;
            if (my >= y && my < y + SubTabBar.H) {
                if (subHitThisFrame != subTab) { currentPage().flushPending(); }
                subTab = subHitThisFrame;
                return true;
            }
        }
        if (saveBar != null && saveBar.mouseClicked(mx, my, width, height)) {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return true; }
            ConfigPage page = currentPage();
            page.flushPending();
            page.save();
            ConfigManager.getInstance().save();
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§a已保存"), true);
            return true;
        }
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        if (my >= contentY && my < contentY + contentH) {
            return currentPage().mouseClicked(mx, my, btn, PAD, contentY, width - PAD * 2, contentH);
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        return currentPage().mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD * 2, contentH);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        return currentPage().mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        return currentPage().mouseScrolled(mx, my, sx, sy, PAD, contentY, width - PAD * 2, contentH);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (currentPage().keyPressed(key, scan, mod)) return true;
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (currentPage().charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    @Override
    public void onClose() {
        currentPage().flushPending();
        saveConfigNow();
        Minecraft.getInstance().setScreen(parent);
    }
}
```

- [ ] **Step 3: 提交（此时 9 个页面类尚未创建，compile 失败属预期）**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/ConfigPage.java src/main/java/com/habitrain/core/client/gui/menu/ConfigMenuScreen.java
git commit -m "feat(gui/menu): ConfigPage 接口与 ConfigMenuScreen 根屏（4 Tab + 子Tab + SaveBar 路由）"
```

---

## Task 5: 切换 api 内 3 处外部引用

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/ModMenuIntegration.java`
- Modify: `src/main/java/com/habitrain/core/client/role/RoleOverrideRefreshDispatcher.java`
- Modify: `src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java`

**Interfaces:**
- Consumes: `ConfigMenuScreen`, `MenuPermissions`。
- Produces: 根入口与刷新/权限路由指向新类。

- [ ] **Step 1: ModMenuIntegration**

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigMenuScreen::new;
    }
}
```

- [ ] **Step 2: RoleOverrideRefreshDispatcher（改 instanceof 与调用）**

原 `RoleOverrideRefreshDispatcher.java:35-37`：

```java
Screen screen = mc.screen;
if (screen instanceof com.habitrain.core.client.gui.menu.ConfigMenuScreen configScreen) {
    configScreen.refreshRoleOverrideTab();
}
```

- [ ] **Step 3: ClientLifecycleHandler（改 import 与调用）**

- `import com.habitrain.core.client.gui.LiveConfigAccess;` → `import com.habitrain.core.client.gui.menu.MenuPermissions;`
- `LiveConfigAccess.canEditRemoteConfigs()`（`:74`）→ `MenuPermissions.canEditRemoteConfigs()`

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/ModMenuIntegration.java \
        src/main/java/com/habitrain/core/client/role/RoleOverrideRefreshDispatcher.java \
        src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java
git commit -m "refactor(gui): 外部引用切换到 menu 包（ModMenuIntegration/RoleOverrideRefreshDispatcher/ClientLifecycleHandler）"
```

---

## Task 6: InGameBalancePage（游戏内·数值平衡）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/InGameBalancePage.java`

**Interfaces:**
- Consumes: `ConfigPage`, `MenuTheme`, `MenuPermissions`, `ui/SliderRow`, `ui/PillToggle`, `ui/EditRow`, `ui/ScrollArea`, `ConfigManager`。
- Produces: `InGameBalancePage`（旧 `GlobalTabScreen` 的 5 项数值，去掉光影白名单入口）。

**行为要点（对应 spec §6「数值平衡」）：**
- DLC 滑块：0.10–0.80、步进 0.05、拖动即时 `setDlcProbabilityTarget`。
- 警长除数/临时电源价格：数字框由保存按钮提交（即时写内存）。
- 刀耐久/小游戏总开关：即时 toggle + `applyMinigameEnforcement(singleplayerServer)`。

- [ ] **Step 1: 实现页面**

```java
package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.*;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** 游戏内·数值平衡：DLC 占比滑块 / 警长除数 / 临时电源价格 / 刀耐久 / 小游戏总开关。 */
public class InGameBalancePage implements ConfigPage {

    private static final int PAD = 16;
    private static final int ROW_H = 32;
    private static final float MIN_TARGET = 0.10f, MAX_TARGET = 0.80f, STEP = 0.05f;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private float dlcTarget;
    private boolean mgGlobal, knifeDurabilityEnabled;
    private int sheriffDivisor, tempPowerPrice;

    private final EditBox sheriffField, tempPowerField;
    private boolean widgetsInitialized = false;

    private final SliderRow slider = new SliderRow(MIN_TARGET, MAX_TARGET, STEP);
    private final ScrollArea area;

    // 本页命中矩形：开关药丸 + 数值行
    private record Hit(int x, int y, int w, int h, int action) {}
    private final java.util.List<Hit> hits = new java.util.ArrayList<>();

    public InGameBalancePage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        ConfigManager c = ConfigManager.getInstance();
        this.dlcTarget = c.getDlcProbabilityTarget();
        this.mgGlobal = c.isMinigameGlobalEnabled();
        this.knifeDurabilityEnabled = c.isKnifeDurabilityEnabled();
        this.sheriffDivisor = c.getSheriffCountDivisor();
        this.tempPowerPrice = c.getTempPowerPrice();
        this.sheriffField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        this.sheriffField.setMaxLength(3);
        this.sheriffField.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        this.sheriffField.setValue(String.valueOf(sheriffDivisor));
        this.tempPowerField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        this.tempPowerField.setMaxLength(6);
        this.tempPowerField.setFilter(s -> s.isEmpty() || s.matches("\\d*"));
        this.tempPowerField.setValue(String.valueOf(tempPowerPrice));
        this.area = new ScrollArea(0, 0, 0, 0); // 坐标在 render 里设定
    }

    @Override public boolean canSave() { return true; }
    @Override public void save() { /* 所有改动即时生效，无额外提交 */ }
    @Override public void flushPending() { /* 数值框仅在保存时提交，这里不写盘 */ }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        area = new ScrollArea(x, y, w, h);
        g.enableScissor(x, y, x + w, y + h);
        hits.clear();
        int cy = area.getContentY();
        int labelX = x + PAD;
        int sliderW = Math.min(360, w - PAD * 2);

        // ===== DLC 任务目标占比 =====
        g.drawString(font, Component.literal("§e§lDLC 任务目标占比"), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal("§7系统自动平衡 DLC 与原版任务的出现概率（10%~80%）"),
                labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        if (editable) {
            if (slider.mouseDragged()) {
                float nv = slider.valueFromMouse(mx);
                if (nv != dlcTarget) { dlcTarget = nv; ConfigManager.getInstance().setDlcProbabilityTarget(dlcTarget); }
            }
            slider.render(g, font, labelX, cy, sliderW, dlcTarget);
        } else {
            g.drawString(font, String.format("§6§l%d%%", Math.round(dlcTarget * 100)), labelX, cy + 1, 0xFFFFFFFF, false);
        }
        cy += 24;

        // ===== 警长数量除数 =====
        cy = sectionLine(g, cy, labelX, sliderW);
        g.drawString(font, Component.literal("§e§l警长数量除数"), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, "§7警长数量 = floor(玩家数 / 除数)，默认 6", labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        g.drawString(font, "除数:", labelX, cy + 2, 0xFFCCCCCC, false);
        EditRow.render(g, mx, my, delta, sheriffField, labelX + 50, cy, 60);
        g.drawString(font, "§7（点底部保存生效）", labelX + 118, cy + 2, 0xFF777777, false);
        cy += ROW_H;

        // ===== 临时电源价格 =====
        cy = sectionLine(g, cy, labelX, sliderW);
        g.drawString(font, Component.literal("§e§l临时电源价格"), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal("§7停电模式红色电话商店「临时电源」提灯价格，默认 100"),
                labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        g.drawString(font, "价格:", labelX, cy + 2, 0xFFCCCCCC, false);
        EditRow.render(g, mx, my, delta, tempPowerField, labelX + 50, cy, 60);
        g.drawString(font, "§7（点底部保存生效）", labelX + 118, cy + 2, 0xFF777777, false);
        cy += ROW_H;

        // ===== 杀手刀耐久 =====
        cy = toggleRow(g, cy, labelX, "杀手刀耐久",
                "§7关闭时恢复旧版无限耐久；开启时恢复上游耐久规则",
                knifeDurabilityEnabled, "§a刀耐久：已启用", "§c刀耐久：已禁用", "knife");

        // ===== 小游戏任务总开关 =====
        cy = toggleRow(g, cy, labelX, "小游戏任务总开关",
                "§7关闭后 SRE 将不再分配任何小游戏任务",
                mgGlobal, "§a小游戏：已启用", "§c小游戏：已停用", "mg");

        cy += 8;
        area.setContentHeight(cy - y);
        area.render(g);
        g.disableScissor();
    }

    private int sectionLine(GuiGraphics g, int cy, int labelX, int w) {
        g.fill(labelX - 2, cy - 2, labelX + w + 2, cy - 1, 0x20FFFFFF);
        return cy + 6;
    }

    private int toggleRow(GuiGraphics g, int cy, int labelX, String title, String desc,
                          boolean on, String onText, String offText, String action) {
        g.fill(labelX - 2, cy - 2, labelX + 346, cy - 1, 0x20FFFFFF);
        cy += 6;
        g.drawString(font, Component.literal("§e§l" + title), labelX, cy, MenuTheme.ACCENT_CYAN, false);
        cy += 18;
        g.drawString(font, Component.literal(desc), labelX, cy, MenuTheme.TEXT_SECONDARY, false);
        cy += 18;
        int tw = 160;
        PillToggle.render(g, font, labelX, cy, tw, 20, on, onText, offText);
        hits.add(new Hit(labelX, cy, tw, 20, action.equals("knife") ? 2 : 1));
        return cy + ROW_H;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (sheriffField.mouseClicked(mx, my, btn)) return true;
        if (tempPowerField.mouseClicked(mx, my, btn)) return true;
        for (Hit hit : hits) {
            if (PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                if (hit.action() == 1) {
                    mgGlobal = !mgGlobal;
                    ConfigManager.getInstance().setMinigameGlobalEnabled(mgGlobal);
                    ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer());
                } else {
                    knifeDurabilityEnabled = !knifeDurabilityEnabled;
                    ConfigManager.getInstance().setKnifeDurabilityEnabled(knifeDurabilityEnabled);
                }
                return true;
            }
        }
        if (slider.mouseClicked(mx, my)) return true;
        return area.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (slider.mouseDragged()) return true;
        return area.mouseDragged(my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (slider.mouseReleased()) return true;
        return area.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        return area.mouseScrolled(sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (sheriffField.isFocused() && sheriffField.keyPressed(key, scan, mod)) return true;
        if (tempPowerField.isFocused() && tempPowerField.keyPressed(key, scan, mod)) return true;
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (sheriffField.isFocused() && sheriffField.charTyped(ch, mod)) return true;
        if (tempPowerField.isFocused() && tempPowerField.charTyped(ch, mod)) return true;
        return false;
    }
}
```

> 说明：数值框的「提交」发生在保存按钮——根屏 `onClose` 与「保存」都只调 `flushPending`；本页把警长/电源价提交逻辑放在 `flushPending()`（而不是空实现）。更正本页：

**`flushPending()` 修正（替代上面空实现）：**

```java
@Override
public void flushPending() {
    if (!editable) return;
    try {
        int v = Integer.parseInt(sheriffField.getValue().trim());
        sheriffDivisor = Math.max(1, v);
        ConfigManager.getInstance().setSheriffCountDivisor(sheriffDivisor);
    } catch (NumberFormatException ignored) {}
    try {
        int v = Integer.parseInt(tempPowerField.getValue().trim());
        tempPowerPrice = Math.max(0, v);
        ConfigManager.getInstance().setTempPowerPrice(tempPowerPrice);
    } catch (NumberFormatException ignored) {}
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/InGameBalancePage.java
git commit -m "feat(gui/menu/page): 游戏内·数值平衡页（DLC滑块/警长/电源价/刀耐久/小游戏总开关）"
```

---

## Task 7: InGameMinigamesPage + MinigameEditScreen（游戏内·小游戏）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/InGameMinigamesPage.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/MinigameEditScreen.java`

**Interfaces:**
- Consumes: `ConfigPage`, 组件, `ConfigManager`, `io.wifi.starrailexpress.content.minigame.QuestMinigame(s)`, `SharedGuiConstants` 不再用（改用 `MenuTheme.getColor/COLOR_NAMES`）。
- Produces: `InGameMinigamesPage`（列表页）；`MinigameEditScreen(Screen parent, QuestMinigame mg, MinigameConfigEntry cfg)`。

**行为要点（spec §6「小游戏」）：**
- SRE 未装显示提示；2 列卡片、搜索、统计；开关后 `applyMinigameEnforcement`。
- 编辑页保持旧 `MinigameEditScreen` 全部字段与按钮（启用/颜色/轮廓±/奖励/权重/地图过滤/保存并返回/重置）。

- [ ] **Step 1: InGameMinigamesPage（列表）**

```java
package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.*;
import com.habitrain.core.client.gui.menu.ui.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import io.wifi.starrailexpress.content.minigame.QuestMinigames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 游戏内·小游戏：SRE QuestMinigames 卡片网格 + 搜索 + 编辑入口。 */
public class InGameMinigamesPage implements ConfigPage {

    private static final int CARD_H = 56;
    private static final int CARD_GAP = 6;
    private static final int HEADER_H = 28;
    private static final int COLUMNS = 2;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private final List<QuestMinigame> minigames = new ArrayList<>();
    private final EditBox searchBox;
    private String searchText = "";
    private final ScrollArea area;
    private ConfigManager snapshot;

    private final List<CardHit> cardHits = new ArrayList<>();
    private record CardHit(QuestMinigame mg, int x, int y, int w, int h, int toggleX, int toggleW, int editX, int editW) {}

    public InGameMinigamesPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        this.searchBox = new EditBox(font, 0, 0, 10, 14, Component.literal(""));
        this.searchBox.setMaxLength(64);
        this.searchBox.setHint(Component.literal("搜索小游戏..."));
        this.searchBox.setResponder(t -> { searchText = t == null ? "" : t.trim().toLowerCase(Locale.ROOT); });
        this.area = new ScrollArea(0, 0, 0, 0);
        try { minigames.addAll(QuestMinigames.getAll()); } catch (Throwable ignored) {}
    }

    private boolean sreAvailable() { return !minigames.isEmpty(); }

    @Override public boolean canSave() { return true; }
    @Override public void save() {}
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        snapshot = ConfigManager.getInstance();
        if (!sreAvailable()) {
            g.drawString(font, Component.literal("§c未检测到 SRE（星穹列车）模组，小游戏功能不可用"),
                    x + 8, y + 10, 0xFF5555, false);
            return;
        }
        searchBox.setX(x + 6); searchBox.setY(y + 6); searchBox.setWidth(w - 12);
        searchBox.render(g, mx, my, delta);

        int listY = y + HEADER_H;
        int listH = h - HEADER_H;
        area = new ScrollArea(x, listY, w, listH);
        cardHits.clear();
        g.enableScissor(x, listY, x + w, listY + listH);

        int cy = area.getContentY();
        int colW = (w - CARD_GAP) / COLUMNS;
        int filtered = 0, enabled = 0, idx = 0;
        for (QuestMinigame mg : minigames) {
            String dn = mg.displayName() != null ? mg.displayName().getString() : mg.id();
            if (!matches(dn, mg.id())) continue;
            filtered++;
            MinigameConfigEntry cfg = snapshot.getMinigameConfig(mg.id());
            boolean on = cfg == null || cfg.enabled;
            if (on) enabled++;
            int col = idx % COLUMNS, row = idx / COLUMNS;
            int cx = x + col * (colW + CARD_GAP);
            int cardY = cy + row * (CARD_H + CARD_GAP);
            drawCard(g, mg, dn, cfg, cx, cardY, colW, mx, my);
            idx++;
        }
        int totalRows = (idx + COLUMNS - 1) / COLUMNS;
        area.setContentHeight(totalRows * (CARD_H + CARD_GAP));
        area.render(g);
        g.disableScissor();

        String stats = "§7" + enabled + "/" + filtered + " 已启用 §8| §7总计 " + minigames.size();
        g.drawString(font, stats, x + w - font.width(stats) - 8, y + 10, 0xFF888888, false);
    }

    private void drawCard(GuiGraphics g, QuestMinigame mg, String dn, MinigameConfigEntry cfg,
                          int x, int y, int w, int mx, int my) {
        boolean on = cfg == null || cfg.enabled;
        int color = cfg != null ? cfg.instinctColor : MenuTheme.accentFor(mg.id());
        boolean hover = MenuTheme.inBounds(mx, my, x, y, w, CARD_H);
        g.fill(x, y, x + w, y + CARD_H, hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
        MenuTheme.drawAccentStripe(g, x, y, CARD_H, color);
        String name = dn.length() > 22 ? dn.substring(0, 20) + "…" : dn;
        g.drawString(font, name, x + 8, y + 6, MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, "§7" + mg.id(), x + 8, y + 20, MenuTheme.TEXT_SECONDARY, false);
        StringBuilder reward = new StringBuilder();
        if (cfg != null) {
            if (cfg.hasGoldReward) reward.append("§6金").append(cfg.goldReward).append(" ");
            if (cfg.hasEmotionReward) reward.append("§b情").append(String.format("%.1f", cfg.emotionReward));
        }
        if (reward.length() > 0) g.drawString(font, reward.toString(), x + 8, y + 34, 0xFFAAAAAA, false);

        int toggleX = x + w - 60, toggleW = 40;
        PillToggle.render(g, font, toggleX, y + 6, toggleW, 14, on, "§a启用", "§c停用");
        int editX = x + w - 60, editW = 40;
        g.fill(editX, y + 28, editX + editW, y + 42, MenuTheme.BG_EDIT);
        g.drawString(font, "§e编辑", editX + (editW - font.width("编辑")) / 2, y + 30, 0xFFFFFFFF, false);
        cardHits.add(new CardHit(mg, x, y, w, CARD_H, toggleX, toggleW, editX, editW));
    }

    private boolean matches(String dn, String id) {
        if (searchText.isEmpty()) return true;
        return (dn + " " + id).toLowerCase(Locale.ROOT).contains(searchText);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (!sreAvailable()) return false;
        if (searchBox.mouseClicked(mx, my, btn)) return true;
        for (CardHit hit : cardHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (PillToggle.hit(mx, my, hit.toggleX(), hit.toggleY(), hit.toggleW(), 14)) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                MinigameConfigEntry cfg = ConfigManager.getInstance().getMinigameConfig(hit.mg().id());
                if (cfg == null) cfg = MinigameConfigEntry.createDefault();
                cfg.enabled = !cfg.enabled;
                ConfigManager.getInstance().setMinigameConfig(hit.mg().id(), cfg);
                ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer());
                return true;
            }
            if (PillToggle.hit(mx, my, hit.editX(), hit.editY(), hit.editW(), 14)) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                MinigameConfigEntry cfg = ConfigManager.getInstance().getMinigameConfig(hit.mg().id());
                if (cfg == null) cfg = MinigameConfigEntry.createDefault();
                ConfigManager.getInstance().putMinigameConfig(hit.mg().id(), cfg);
                Minecraft.getInstance().setScreen(new MinigameEditScreen(root, hit.mg(), cfg));
                return true;
            }
        }
        return area.mouseClicked(mx, my, btn);
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return area.mouseDragged(my); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return area.mouseReleased(); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return area.mouseScrolled(sy); }
    @Override public boolean keyPressed(int key, int scan, int mod) { return searchBox.isFocused() && searchBox.keyPressed(key, scan, mod); }
    @Override public boolean charTyped(char ch, int mod) { return searchBox.isFocused() && searchBox.charTyped(ch, mod); }
}
```

> 注意：`CardHit` 改用 `toggleY`/`editY` 记 y 坐标；上面 record 里的 `toggleY`/`editY` 需在 record 定义中替换 `int y` 语义。修正 record：

```java
private record CardHit(QuestMinigame mg, int x, int y, int w, int h,
                       int toggleX, int toggleY, int toggleW, int toggleH,
                       int editX, int editY, int editW, int editH) {}
```

并在 `drawCard` 中填充 `toggleY=y+6, toggleH=14, editY=y+28, editH=14`。

- [ ] **Step 2: MinigameEditScreen（重写，行为对齐旧版）**

结构：`extends Screen`；字段 `parent/minigame/cfg/remoteEditable/displayName`；`init()` 一次性创建 widgets（enable/color/outline±/gold/emotion/weight/mapFilter/mapField/save/reset/topBack）；`render` 四区（基础/奖励/地图/只读信息）+ 底部「保存并返回」/「重置」；`commitFields()` 解析文本；`saveCurrent()`= `putMinigameConfig`；`onClose()` = `ConfigManager.save()` + 返回 parent。颜色用 `MenuTheme.getColor(index, 0xB4)` 与 `MenuTheme.COLOR_NAMES`。

完整实现参照迁移备份 `旧配置GUI备份_2026-07-31\...\config\MinigameEditScreen.java`，仅把 `SharedGuiKit`/`SharedGuiConstants` 引用换成 `MenuTheme`，把 `root` 类型从旧 `ConfigRootScreen` 换成 `ConfigMenuScreen`。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/InGameMinigamesPage.java \
        src/main/java/com/habitrain/core/client/gui/menu/MinigameEditScreen.java
git commit -m "feat(gui/menu): 游戏内·小游戏页与 MinigameEditScreen 重写"
```

---

## Task 8: InGameEnvPage（游戏内·环境：对局/局后/动态雨）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/InGameEnvPage.java`

**Interfaces:**
- Consumes: `ConfigPage`, 组件, `ConfigManager`, `EnvProfile`, `EnvTimeSpec`, `EnvironmentSettings`, `PostMatchTimeRule`, `SREModeStartAdapter`。
- Produces: `InGameEnvPage`。

**行为要点（spec §6「环境」，仅 3 子页，去掉大厅）：**
- 内层子 Tab：对局环境 / 局后时间 / 动态雨。
- 对局环境：左地图列表（默认+覆盖）+ 右侧编辑器（时间/天气/雪/沙尘/雾/雾距离/日夜/天气循环/删除覆盖）。
- 局后时间：好人/杀手各 toggle + 时间编辑。
- 动态雨：启用 + 最少玩家数。
- 保存按钮 flush 聚焦框；`markEnvironmentDirty()` 在改动时调用。

**结构要点（复用旧 `EnvironmentTabScreen` 逻辑）：**

```java
package com.habitrain.core.client.gui.menu.page;

// imports 同旧版 EnvironmentTabScreen（EnvProfile/EnvTimeSpec/EnvironmentSettings/PostMatchTimeRule/SREModeStartAdapter/ConfigManager）

public class InGameEnvPage implements ConfigPage {
    private static final int SUB_MATCH = 0, SUB_POST = 1, SUB_RAIN = 2;
    private static final String[] SUB_LABELS = {"对局环境", "局后时间", "动态雨"};
    private static final int ACCENT = 0xFF55C28A;
    private static final int PAD = 12, ROW_H = 22, HEADER_H = 16, SUB_TAB_H = 20;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private int subTab = SUB_MATCH;
    private String selectedMapId = null;   // null => 默认 profile

    private final EditBox profileTickField, profileFogEndField, goodTickField, otherTickField, minPlayersField;
    private final ScrollArea area;
    private boolean widgetsInitialized = false;

    private final List<ButtonHit> buttonHits = new ArrayList<>();
    private final List<MapRowHit> mapHits = new ArrayList<>();
    private record ButtonHit(String action, int x, int y, int w, int h) {}
    private record MapRowHit(String mapId, int x, int y, int w, int h) {}

    // —— 与旧 EnvironmentTabScreen 相同的全部私有方法，仅改三处：
    //   1) 字段类型 ConfigRootScreen→ConfigMenuScreen
    //   2) SUB_LABELS 去掉「大厅环境」，SUB_LOBBY 相关逻辑删除
    //   3) 共享绘制/交互改用 MenuTheme 组件
}
```

**必须移植的关键方法（完整代码见迁移备份 `EnvironmentTabScreen.java`，本页逐字保留语义）：**
- `settings()` / `dirty()` / `playClick()` / `saveNow()`
- `ensureWidgetsInitialized()`（5 个 EditBox + `syncFieldsFromSettings(true)`）
- `syncFieldsFromSettings(force)`（`isFocused()` 保护）
- `currentProfile()`（去掉 SUB_LOBBY 分支；`SUB_MATCH` 时 `matchDefaultProfile`/`matchMaps`）
- `collectMapIds()`（vote.maps + `SREModeStartAdapter.getAvailableMaps(overworld)`，`try/catch`）
- `render`（子 Tab 条用 `SubTabBar`；内容用 `ScrollArea`；`case SUB_MATCH/SUB_POST/SUB_RAIN`）
- `renderMatch`（左列表 + 右编辑器 + 「删除地图覆盖」）
- `renderPost` / `renderPostRule` / `renderTimeSpecEditor`（用 `PillToggle` + 组件）
- `renderRain`（启用 toggle + minPlayers 框 + 「应用」→ 本页统一改为由保存按钮提交，原「应用」按钮可保留为即时）
- `renderProfileEditor`（时间模式/预设循环/天气循环/雪/沙尘/雾/雾距离/日夜/天气循环）
- `drawToggle`（label 行内绿/红药丸）
- `mouseClicked`（EditBox 先、mapHits、buttonHits、`unfocusAll` + 拖拽）
- `handleAction`（`sub:` 切换、`delete_map`、`rain:*`、`good:`/`other:`、`profile:`）
- `applyTimeOrToggle`（enabled/time_mode/preset/apply_tick/weather/snow/sand/fog/apply_fog/daylight/weatherCycle）
- `tryFocusEditBox`（跳过驻留坐标 `bx<-1000`）
- `flushFocusedFields`（保存前 flush 聚焦的 tick/雾/人数框）
- `unfocusAll` / `keyPressed` / `charTyped`

**新增的接口方法：**

```java
@Override public boolean canSave() { return true; }
@Override public void save() { dirty(); }
@Override public void flushPending() { flushFocusedFields(); }
```

> `subTab` 切换时（`sub:` 动作）调用 `area.reset()` + `syncFieldsFromSettings(true)` + `unfocusAll()`，与旧版一致。

- [ ] **Step 1: 实现（逐方法移植备份 + 上述 3 处修改）**
- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/InGameEnvPage.java
git commit -m "feat(gui/menu/page): 游戏内·环境页（对局/局后/动态雨）"
```

---

## Task 9: OutGameVotePage（游戏外·投票）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/OutGameVotePage.java`

**Interfaces:**
- Consumes: `ConfigPage`, 组件, `ConfigManager`, `ModeMapVoteSettings`, `MapPoolRotationSettings`, `MapPoolEntry`, `MapVoteEntry`, `ModeVoteEntry`, `MapPoolRotationService`, `GameModeRegistry`, `PayloadSenders`。
- Produces: `OutGameVotePage`。

**行为要点（spec §6「投票」）：**
- 内层子 Tab：主设置 / 地图池轮换 / 可投票模式 / 可投票地图。
- 主设置：总开关 + 模式/地图投票时长（5–120）。
- 池轮换：开关/自动重分/直接抽图↔限制投票循环/摘要/「编辑池子」→`MapPoolEditorScreen`/「跳过当前」→`PayloadSenders.sendMapPoolSkip`。
- 可投票模式：行（开/关、显示名框、↑↓、可选地图→`ModeAllowedMapsScreen`）；上移下移后立即 `ConfigManager.save()`。
- 可投票地图：行（开/关、显示名框）。
- 名称框「仅当用户改动才创建条目」；`flushPending` 提交时长与显示名。

**内层子 Tab 状态：** `int innerTab`（0..3），`subTabSwitch(action)` 分发。页面内容按 innerTab 切换，公共的 `modeNameFields`/`mapNameFields` 在所有 innerTab 渲染时保持同一份 EditBox 实例（与旧版一致）。

**必须移植的关键方法（见迁移备份 `VoteTabScreen.java`，语义不变）：**
- `settings()` / `rebuildIdLists()` / `ensureWidgetsInitialized()` / `rebuildNameFields()` / `resolveModeDisplay`
- `modeEnabled` / `mapEnabled` / `clampDuration`
- 四个 innerTab 各自的 render（主设置/池轮换/模式列表/地图列表）
- `mouseClicked` 的 `ButtonHit`/`RowHit` 分发（`toggle_enabled`/`pool_toggle`/`pool_auto`/`pool_apply_mode`/`pool_edit`/`pool_skip`/`mode_up:`/`mode_down:`/保存→由根 SaveBar 承担）
- `commitFieldsToSettings` / `ensureModeEntry` / `persist`
- 旧页底部「保存」按钮 → 本页改为根 SaveBar：`flushPending()` = `commitFieldsToSettings()+persist()`；`save()` = no-op（persist 已在 flushPending 完成）；根屏负责 `ConfigManager.save()` + 提示。

**新增的接口方法：**

```java
@Override public boolean canSave() { return true; }
@Override public void save() {}
@Override public void flushPending() { commitFieldsToSettings(); persist(); }
```

**mapHits/modeHits/buttonHits 命中矩形按 innerTab 分别收集；点击先判 innerTab 子 Tab 条（用 `SubTabBar`），再判内容。**

- [ ] **Step 1: 实现（移植 + 4 子 Tab 拆分 + SaveBar 接管保存）**
- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/OutGameVotePage.java
git commit -m "feat(gui/menu/page): 游戏外·投票页（主设置/池轮换/模式/地图）"
```

---

## Task 10: MapPoolEditorScreen + ModeAllowedMapsScreen 重写

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/MapPoolEditorScreen.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/ModeAllowedMapsScreen.java`

**Interfaces:**
- Consumes: `MenuTheme`, `MenuPermissions`, `ConfigManager`, 投票 DTO。
- Produces: 两个独立 Screen（由 `OutGameVotePage` 打开），保持旧版全部行为。

- [ ] **Step 1: MapPoolEditorScreen**

结构同旧版 `MapPoolEditorScreen.java`（左池列表 + 右地图多选 + 底部按钮），改动：
- `SharedGuiKit.drawBackdrop` → `MenuTheme.drawBackdrop`；`SharedGuiKit.*` → `MenuTheme.*`。
- 字段 `remoteEditable` 保留；`parent` 由 `OutGameVotePage` 传入（`Screen` 类型即可）。
- 保留：`loadFromSettings`/`syncSelectedFromPool`/`writeSelectedToPool`/`rebuildNameFields`/`flushNames`/`commitSave`/`addPool`/`removePool`/`repartition`/`clear`/`onClose`/滚动（`scrollOffset`/`poolScrollOffset` 用本地实现，不用 `ScrollArea`，因有双滚动区）。

- [ ] **Step 2: ModeAllowedMapsScreen**

结构同旧版 `ModeAllowedMapsScreen.java`（多选 + 清空 + 保存），改动：
- `SharedGuiKit.*` → `MenuTheme.*`；`LiveConfigAccess` → `MenuPermissions`。
- 保留：`loadFromSettings`/`commitSave`（空选=不限制）/滚动/清空。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/MapPoolEditorScreen.java \
        src/main/java/com/habitrain/core/client/gui/menu/ModeAllowedMapsScreen.java
git commit -m "feat(gui/menu): 地图池编辑与模式可选地图重写"
```

---

## Task 11: OutGameLobbyEnvPage（游戏外·大厅环境）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/OutGameLobbyEnvPage.java`

**Interfaces:**
- Consumes: `ConfigPage`, 组件, `EnvProfile`, `EnvTimeSpec`, `EnvironmentSettings`。
- Produces: `OutGameLobbyEnvPage`。

**行为要点（spec §6「环境」大厅部分）：**
- 复用 `EnvironmentTabScreen` 的「大厅」子页逻辑：`renderProfileEditor(profile = settings().lobby)`。
- 时间模式/预设/天气/雪/沙尘/雾/雾距离/日夜/天气循环 toggle。
- `save()` = `markEnvironmentDirty()`；`flushPending()` = flush 聚焦的 tick/雾框。

**实现：** 本质是 `InGameEnvPage` 的子集（只 `renderProfileEditor` + 保存/滚动），可复用同一组逻辑；不复制整个 EnvPage，写一个精简页面：

```java
public class OutGameLobbyEnvPage implements ConfigPage {
    // 字段：root/font/editable/profileTickField/profileFogEndField/area/widgetsInitialized
    // render: 标题「大厅环境配置」+ 说明 + renderProfileEditor(lobby)
    // 事件: buttonHits（profile:enabled/time_mode/preset/apply_tick/weather/snow/sand/fog/apply_fog/daylight/weatherCycle）
    //       + tryFocusEditBox + ScrollArea
    // save() = markEnvironmentDirty(); flushPending() = flushFocusedFields()
}
```

`renderProfileEditor`/`applyTimeOrToggle`/`tryFocusEditBox`/`flushFocusedFields`/`unfocusAll` 与 `InGameEnvPage` 共用——把这两页共用的方法提取到 `menu/page/EnvEditorShared.java`（包内可见静态方法），避免复制。

**新文件（本任务一并创建）：**
- `src/main/java/com/habitrain/core/client/gui/menu/page/EnvEditorShared.java`

```java
package com.habitrain.core.client.gui.menu.page;

/** 环境编辑器共用逻辑：renderProfileEditor / applyTimeOrToggle / tryFocusEditBox / flushFocusedFields / unfocusAll。
 *  从旧 EnvironmentTabScreen 原样移植，输入/输出与旧版一致。 */
public final class EnvEditorShared {
    private EnvEditorShared() {}
    // 静态方法：renderProfileEditor(g,mx,my,delta,font,labelX,cy,innerW,profile,prefix,buttonHits,profileTickField,profileFogEndField)
    //           applyTimeOrToggle(...)
    //           tryFocusEditBox(...)
    //           flushFocusedFields(...)
    //           unfocusAll(...)
}
```

> 每个静态方法的参数与旧 `EnvironmentTabScreen` 实例方法对应（把 `this.xxx` 字段作为显式参数传入）。`InGameEnvPage` 与 `OutGameLobbyEnvPage` 均调用 `EnvEditorShared`，行为一致。

- [ ] **Step 1: 提取 EnvEditorShared**
- [ ] **Step 2: 实现 OutGameLobbyEnvPage（复用 EnvEditorShared）**
- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/EnvEditorShared.java \
        src/main/java/com/habitrain/core/client/gui/menu/page/OutGameLobbyEnvPage.java
git commit -m "feat(gui/menu/page): 游戏外·大厅环境页（复用 EnvEditorShared）"
```

> 顺序注意：`InGameEnvPage`（Task 8）先于 `EnvEditorShared`（Task 11）创建。为减少返工，**实现时先做本任务再改 InGameEnvPage 复用**，或直接在 Task 8 内就调用 EnvEditorShared（把 EnvEditorShared 提前到 Task 8 创建）。推荐后者：Task 8 创建 `EnvEditorShared`，Task 8 与 Task 11 共用。

---

## Task 12: OutGameShaderPage（游戏外·光影白名单，内联）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/OutGameShaderPage.java`

**Interfaces:**
- Consumes: `ConfigPage`, 组件, `ConfigManager`。
- Produces: `OutGameShaderPage`（内联光影白名单，替代旧「入口按钮→子屏」）。

**行为要点（spec §6「光影白名单」）：**
- 启用开关 + 光影包增删列表（忽略大小写去重）+ 白名单外被踢说明 + 即时 `setShaderWhitelistConfig`。
- 顶部有添加输入框 + 「添加」按钮；列表行悬停显示删除。

- [ ] **Step 1: 实现页面**

```java
package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.*;
import com.habitrain.core.client.gui.menu.ui.*;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 游戏外·光影白名单（内联页面）：启用 + 列表增删，即时保存。 */
public class OutGameShaderPage implements ConfigPage {

    private static final int HEADER_H = 72;
    private static final int ROW_H = 26, ROW_GAP = 2;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private boolean whitelistEnabled;
    private final List<String> whitelist = new ArrayList<>();
    private final EditBox addBox;
    private String addText = "";
    private final ScrollArea area;

    private final List<RowHit> rowHits = new ArrayList<>();
    private record RowHit(int index, int x, int y, int w, int h) {}

    public OutGameShaderPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        ConfigManager c = ConfigManager.getInstance();
        this.whitelistEnabled = c.isShaderWhitelistEnabled();
        this.whitelist.addAll(c.getShaderWhitelist());
        this.addBox = new EditBox(font, 0, 0, 130, 16, Component.literal(""));
        this.addBox.setMaxLength(128);
        this.addBox.setHint(Component.literal("输入光影包名称..."));
        this.addBox.setResponder(t -> addText = t == null ? "" : t.trim());
        this.area = new ScrollArea(0, 0, 0, 0);
    }

    @Override public boolean canSave() { return true; }
    @Override public void save() { ConfigManager.getInstance().save(); }
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        g.drawString(font, Component.literal("§lIris 光影白名单"), x + PAD(), y + 4, 0xFFFFFF, false);
        g.drawString(font, Component.literal("§7设置服务器允许使用的 Iris 光影包，仅 OP 可修改"), x + PAD(), y + 18, 0x888888, false);
        // 启用开关
        int toggleW = 160;
        PillToggle.render(g, font, x + PAD(), y + 30, toggleW, 18, whitelistEnabled,
                whitelistEnabled ? "§a✔ 光影白名单已启用" : "§c✘ 光影白名单已禁用",
                whitelistEnabled ? "§a✔ 光影白名单已启用" : "§c✘ 光影白名单已禁用");
        rowHits.add(new RowHit(-1, x + PAD(), y + 30, toggleW, 18));  // index=-1 表示总开关

        // 添加区
        addBox.setX(x + PAD()); addBox.setY(y + 52); addBox.setWidth(Math.min(260, w - PAD() * 2 - 60));
        addBox.render(g, mx, my, delta);
        int addX = addBox.getX() + addBox.getWidth() + 6;
        g.fill(addX, y + 50, addX + 50, y + 70, editable ? MenuTheme.BG_EDIT : 0xFF222222);
        g.drawString(font, "§a+ 添加", addX + 8, y + 54, editable ? 0xFFFFFFFF : 0xFF666666, false);
        rowHits.add(new RowHit(-2, addX, y + 50, 50, 20));            // index=-2 表示添加按钮

        g.fill(x + PAD(), y + HEADER_H, x + w - PAD(), y + HEADER_H + 1, 0x30FFFFFF);

        int listY = y + HEADER_H + 4;
        int listH = h - HEADER_H - 8;
        area = new ScrollArea(x, listY, w, listH);
        rowHits.removeIf(r -> r.index() >= 0);
        g.enableScissor(x, listY, x + w, listY + listH);
        int cy = area.getContentY();
        if (whitelist.isEmpty()) {
            String msg = whitelistEnabled ? "§7暂无允许的光影包 — 将阻止所有光影包" : "§7暂无添加的光影包，在上方添加";
            g.drawString(font, Component.literal(msg), x + w / 2 - font.width(msg) / 2, cy + 8, 0x555555, false);
        } else {
            for (int i = 0; i < whitelist.size(); i++) {
                boolean hover = MenuTheme.inBounds(mx, my, x, cy, w, ROW_H);
                g.fill(x, cy, x + w, cy + ROW_H, hover ? 0x18FFFFFF : 0x08FFFFFF);
                g.drawString(font, "§7" + (i + 1) + ".", x + 4, cy + 6, 0x888888, false);
                g.drawString(font, "§e📦", x + 4, cy + 6, 0, false);
                g.drawString(font, "§f" + whitelist.get(i), x + 24, cy + 6, 0xFFFFFF, false);
                int delX = x + w - 22;
                boolean delHover = hover && mx >= delX && mx < delX + 18 && my >= cy + 4 && my < cy + 22;
                if (delHover) g.fill(delX, cy + 4, delX + 18, cy + 22, 0x44FF0000);
                g.drawString(font, delHover ? "§c✕" : "§8✕", delX + 5, cy + 4, 0, false);
                rowHits.add(new RowHit(i, x, cy, w, ROW_H));
                cy += ROW_H + ROW_GAP;
            }
        }
        area.setContentHeight(whitelist.isEmpty() ? 40 : whitelist.size() * (ROW_H + ROW_GAP));
        area.render(g);
        g.disableScissor();

        int infoY = y + h - 24;
        g.drawString(font, Component.literal("§7💡 已允许 §e" + whitelist.size() + " §7个光影包  |  白名单状态: "
                        + (whitelistEnabled ? "§a启用" : "§c禁用")), x + PAD(), infoY, 0, false);
        g.drawString(font, Component.literal("§7⚡ 白名单外光影的玩家将被踢出服务器"), x + PAD(), infoY + 10, 0x555555, false);
    }

    private static int PAD() { return 12; }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (addBox.mouseClicked(mx, my, btn)) return true;
        for (RowHit hit : rowHits) {
            if (!PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
            if (hit.index() == -1) {
                whitelistEnabled = !whitelistEnabled;
                saveToServer();
                return true;
            }
            if (hit.index() == -2) { addCurrentText(); return true; }
            whitelist.remove(hit.index());
            saveToServer();
            return true;
        }
        return area.mouseClicked(mx, my, btn);
    }

    private void addCurrentText() {
        if (addText.isEmpty()) return;
        boolean exists = whitelist.stream().anyMatch(n -> n.equalsIgnoreCase(addText));
        if (exists) { root...; return; }  // 可弹「已在白名单」提示
        whitelist.add(addText);
        addBox.setValue("");
        addText = "";
        saveToServer();
    }

    private void saveToServer() {
        ConfigManager.getInstance().setShaderWhitelistConfig(whitelistEnabled, whitelist);
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return area.mouseDragged(my); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return area.mouseReleased(); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return area.mouseScrolled(sy); }
    @Override public boolean keyPressed(int key, int scan, int mod) {
        if (addBox.isFocused()) {
            if (key == 257 || key == 335) { addCurrentText(); return true; }
            if (addBox.keyPressed(key, scan, mod)) return true;
        }
        return false;
    }
    @Override public boolean charTyped(char ch, int mod) { return addBox.isFocused() && addBox.charTyped(ch, mod); }
}
```

> `root` 提示消息：`exists` 分支可用 `net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(Component.literal("§e该光影包已在白名单中"), true)`。

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/OutGameShaderPage.java
git commit -m "feat(gui/menu/page): 游戏外·光影白名单页（内联，替代子屏入口）"
```

---

## Task 13: ModeTasksPage（游戏模式·任务配置）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/ModeTasksPage.java`

**Interfaces:**
- Consumes: `ConfigPage`, 组件, `GameModeRegistry`, `TaskRegistry`, `TaskDefinition`, `TaskCategory`, `BlackoutMode`, `TaskConfigEntry`, `ConfigManager`, `TaskEditScreen`。
- Produces: `ModeTasksPage`。

**行为要点（spec §6「任务配置」）：**
- 侧栏模式分组（停电 `__good`/`__bad` 拆分）+ 右侧任务列表（按 category 分组）。
- 搜索框、启用计数、行内色条/名称/完整ID/阵营药丸（仅停电）/状态药丸/编辑。
- 开关/编辑先 `getTaskConfig` → 缺省 `createDefault()` → 写回；编辑页用 section 的模式名/色条。

**必须移植（见迁移备份 `TaskTabScreen.java`）：**
- `rebuildSections` / `sectionKeyFor` / `resolveSectionTitle` / `accentForSection` / `fullModeId` / `simpleModeName` / `taskCategoryPriority` / `categoryGroupLabel`
- `render`（搜索框 + 侧栏 + 内容）+ `drawTaskRow` + `factionLabelFor` + `matchesSearch` + `countEnabled`
- `mouseClicked`（搜索/侧栏/任务行 toggle/edit/双滚动拖拽）+ `toggleTask` + `openTaskEditor`
- 侧栏与内容各自滚动 → 用两个 `ScrollArea`（sidebar、content）或保留本地 `sidebarScroll`/`contentScroll`（本页双滚动，用本地更贴合旧逻辑）。

**新增接口方法：**

```java
@Override public boolean canSave() { return true; }
@Override public void save() {}
@Override public void flushPending() {}
```

**`openTaskEditor` 打开新 `TaskEditScreen`：**

```java
Minecraft.getInstance().setScreen(new TaskEditScreen(root, def, cfg, def.getCategory(), modeName, accent));
```

- [ ] **Step 1: 实现（移植 TaskTabScreen，双滚动本地化，SharedGuiKit→MenuTheme）**
- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/ModeTasksPage.java
git commit -m "feat(gui/menu/page): 游戏模式·任务配置页（模式侧栏 + 任务网格）"
```

---

## Task 14: TaskEditScreen + TaskColorPicker + TaskMapFilterEditor + TaskSaveController 重写

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/TaskEditScreen.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/TaskColorPicker.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/TaskMapFilterEditor.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/TaskSaveController.java`

**Interfaces:**
- Consumes: `MenuTheme`(含 `getColor`/`COLOR_NAMES`), `MenuPermissions`, `ConfigManager`, `TaskConfigEntry`, `TaskDefinition`, `TaskCategory`。
- Produces: `TaskEditScreen(Screen parent, TaskDefinition def, TaskConfigEntry cfg, TaskCategory category, String modeDisplayName, int modeAccentColor)` 及三个辅助类（签名与旧版一致，供 `ModeTasksPage`/`InGameMinigamesPage` 使用）。

**行为要点（spec §6「任务编辑」）：**
- 四区：基础（启用/颜色）/奖励（金币/情绪/权重/商店价格仅停电）/地图（过滤+列表）/只读信息。
- 底部「保存修改」「保存并返回」「重置」；顶部「← 返回列表」。
- `TaskSaveController` 签名不变（`syncFields`/`resetDefault`/`saveCurrent`/`showMessage`）。
- `TaskColorPicker` 用 `MenuTheme.getColor(index, 0xB4)` 与 `MenuTheme.COLOR_NAMES`。
- 只读模式：控件禁用 + 提示。

**实现：** 三个辅助类基本是旧版同文件把 `SharedGuiConstants`→`MenuTheme`、`LiveConfigAccess`→`MenuPermissions` 的机械替换；`TaskEditScreen` 同 `MinigameEditScreen` 方式重写（`extends Screen`，`root` 类型 → `ConfigMenuScreen` 或保留 `Screen` 泛化）。颜色/布局常量沿用旧版。

- [ ] **Step 1: TaskColorPicker + TaskMapFilterEditor + TaskSaveController（机械替换迁移）**
- [ ] **Step 2: TaskEditScreen（重写）**
- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/TaskEditScreen.java \
        src/main/java/com/habitrain/core/client/gui/menu/TaskColorPicker.java \
        src/main/java/com/habitrain/core/client/gui/menu/TaskMapFilterEditor.java \
        src/main/java/com/habitrain/core/client/gui/menu/TaskSaveController.java
git commit -m "feat(gui/menu): 任务编辑页与辅助组件重写"
```

---

## Task 15: ModeRolesPage + OtherPage（角色覆盖 + 其他空态）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/ModeRolesPage.java`
- Create: `src/main/java/com/habitrain/core/client/gui/menu/page/OtherPage.java`

**Interfaces:**
- Consumes: `ConfigPage`, 组件, `ConfigManager`, `RoleOverrideConfigSection`, `RoleOverrideApi`, `RoleOverrideRegistry`, `RoleOverrideEngine`, `TMMRoles`。
- Produces: `ModeRolesPage`（含 `rebuildRows()` 供根屏刷新）；`OtherPage`。

- [ ] **Step 1: ModeRolesPage**

移植旧 `RoleOverrideTabScreen.java`（总开关 + 条目列表 + 冲突横幅 + 同 target 互斥），改动：
- `SharedGuiKit.*` → `MenuTheme.*`；`root` 类型 → `ConfigMenuScreen`。
- 每次切换后 `root.saveConfigNow()`。
- 新增 `rebuildRows()` 为 public，供 `ConfigMenuScreen.refreshRoleOverrideTab()` 调用。
- 接口方法：`canSave()=true`；`save()` 空；`flushPending()` 空（即时保存）。

- [ ] **Step 2: OtherPage**

```java
package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 其他·空态页：无可修改选项，不显示保存栏。 */
public class OtherPage implements ConfigPage {
    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    public OtherPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root; this.font = font; this.editable = editable;
    }

    @Override public boolean canSave() { return false; }
    @Override public void save() {}
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        String msg = "§7暂无其他设置";
        g.drawString(font, Component.literal(msg), x + (w - font.width(msg)) / 2, y + 24, MenuTheme.TEXT_SECONDARY, false);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) { return false; }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return false; }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return false; }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return false; }
    @Override public boolean keyPressed(int key, int scan, int mod) { return false; }
    @Override public boolean charTyped(char ch, int mod) { return false; }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/menu/page/ModeRolesPage.java \
        src/main/java/com/habitrain/core/client/gui/menu/page/OtherPage.java
git commit -m "feat(gui/menu/page): 游戏模式·角色覆盖页与「其他」空态页"
```

---

## Task 16: 抽奖桥接 + 全量构建 + 交付 + 记忆更新

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车抽奖补齐\src\main\java\com\habitrain\lottery\meta\HabiCoreMenuBridge.java`
- Modify: `.claude/memory/mod-architecture.md`（第 11 节）
- Modify: `.claude/memory/maintenance-log.md`（追加 maintenance-entry）

**Interfaces:**
- Consumes: 全部新页面类；`ConfigMenuScreen`（新 FQCN + `openVote`）。
- Produces: 可交付 JAR + 记录。

- [ ] **Step 1: 更新抽奖桥接 FQCN**

`HabiCoreMenuBridge.java:26`：

```java
Class<?> cls = Class.forName("com.habitrain.core.client.gui.menu.ConfigMenuScreen");
```

- [ ] **Step 2: 全仓 Grep 复查（不得残留旧类名/旧包引用）**

```bash
cd "D:/Backup/mc mod/哈比列车api"
grep -rn "client.gui.config\|LiveConfigAccess\|SharedGuiKit\|SharedGuiConstants\|TaskTabScreen\|MinigameTabScreen\|GlobalTabScreen\|VoteTabScreen\|EnvironmentTabScreen\|RoleOverrideTabScreen" src/ || echo "无残留"
cd "D:/Backup/mc mod/哈比列车抽奖补齐"
grep -rn "client.gui.config" src/ || echo "抽奖无残留"
```

- [ ] **Step 3: 构建 api**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`。失败则按编译错误逐类修复（多为 import/类名错位），修复后重新 `clean build`。

- [ ] **Step 4: 确认唯一主 JAR 并复制到临时**

```powershell
$jar = Get-ChildItem "D:\Backup\mc mod\哈比列车api\build\libs\habitrain_core-2.0.1.jar"
Copy-Item $jar.FullName "D:\Backup\mc mod\临时\habitrain_core-2.0.1.jar" -Force
$srcHash = (Get-FileHash $jar.FullName).Hash
$dstHash = (Get-FileHash "D:\Backup\mc mod\临时\habitrain_core-2.0.1.jar").Hash
"len=$($jar.Length) src=$srcHash dst=$dstHash"
```

校验：`$jar.Length` 与目标一致、`$srcHash -eq $dstHash`。

- [ ] **Step 5: 构建并交付抽奖补齐**

```bash
cd "D:/Backup/mc mod/哈比列车抽奖补齐"
./gradlew clean build
```

复制其唯一主 JAR 到 `D:\Backup\mc mod\临时\`（文件名以该项目 build.gradle 为准），校验长度/SHA-256。

- [ ] **Step 6: 更新记忆文件**

- `.claude/memory/mod-architecture.md` 第 11 节：把配置页面列表改为新 `menu` 包（`ConfigMenuScreen` + 页面 + 子编辑页），注明旧文件已迁移 `临时\旧配置GUI备份_2026-07-31\`，ModMenu 根入口与刷新/权限路由指向新类。
- `.claude/memory/maintenance-log.md`：追加唯一 maintenance-entry，记录：改动范围（新建 menu 包 + 删除旧 19 文件 + 3 处 api 引用 + 抽奖桥接）、构建与 JAR 校验结果（真实命令/长度/SHA-256）、`architecture-sync: yes`、受影响章节。

- [ ] **Step 7: 提交 api 与抽奖补齐**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add -A src/main/java/com/habitrain/core/client/gui/menu \
        src/main/java/com/habitrain/core/client/gui/ModMenuIntegration.java \
        src/main/java/com/habitrain/core/client/role/RoleOverrideRefreshDispatcher.java \
        src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java \
        .claude/memory/mod-architecture.md .claude/memory/maintenance-log.md
git commit -m "feat(gui/menu): 配置中心四分类重写完成（构建/交付/记忆同步）"
# 抽奖补齐单独提交
cd "D:/Backup/mc mod/哈比列车抽奖补齐"
git add src/main/java/com/habitrain/lottery/meta/HabiCoreMenuBridge.java
git commit -m "fix: 适配 habitrain_core 配置中心新包 FQCN"
```

---

## 自检

### 1. Spec 覆盖

| Spec 要求 | 任务 |
|---|---|
| 4 大类顶层 Tab + 二级子 Tab | Task 4（根屏）、Task 6–15（页面） |
| 功能保真（spec §6 清单） | 各页面任务逐项移植 |
| 每页固定底部保存栏 | Task 3（SaveBar）+ Task 4（根屏路由）+ 各页 save/flushPending |
| 旧文件完全移出 | Task 1（move 到临时备份） |
| 新包 + 不保留旧文件 | 全程新包；Task 16 复查无残留 |
| 外部引用同步（api + 抽奖） | Task 5（api）、Task 16（抽奖） |
| 即时生效 + 保存写盘提示 | Task 4 保存路由 + 各页 flushPending |
| 构建/交付/记忆 | Task 16 |
| 只读模式 | 各页 `editable` + `MenuPermissions` + SaveBar enabled |

### 2. 占位符扫描

- 无 TBD/TODO。Task 4 提到「先以 TODO 占位创建页面」仅描述注册表结构，实际页面在 Task 6–15 逐个实现；Task 8/11 复用 `EnvEditorShared` 已给出明确归属（推荐 Task 8 内一并创建）。
- Task 7/14 的「机械替换迁移」均给出源文件路径与替换规则，非空泛描述。

### 3. 类型一致性

- 组件 API 全程一致：`ScrollArea#mouseDragged(my)`（单参，只传 y）、`PillToggle#render/hit`（静态）、`SliderRow#mouseDragged()`（无参，返回 boolean）、`SaveBar#HEIGHT=40`。
- `ConfigPage` 方法签名全页面一致；`ConfigMenuScreen` 构造页面传 `(this, font, remoteEditable)`。
- `MinigameEditScreen`/`TaskEditScreen` 构造签名与旧版一致（`Screen parent` 泛化，`ModeTasksPage` 传入 `root`）。
- `ModeRolesPage#rebuildRows()` public，`ConfigMenuScreen.refreshRoleOverrideTab()` 调用一致。
- 根屏 `openVote` 静态方法签名与抽奖桥接反射契约一致。
