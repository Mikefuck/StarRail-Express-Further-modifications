# Blackout 模式游戏循环修复与角色介绍 GUI 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Blackout 模式开局全员 CIVILIAN 导致 SRE 立即结束游戏的问题，并新增角色介绍 GUI

**Architecture:** 通过 `NormalRole` + `TMMRoles.registerRole()` 创建自定义 SRE 中立角色分配给坏人阵营，使 SRE 看到多个阵营从而避免触发胜负判定；同时调整 `initializeGame()` 中角色分配与游戏启动的顺序

**Tech Stack:** Java 21, Fabric 1.21.1, SRE API (starrailexpress), Minecraft GUI (Screen + DrawContext)

## Global Constraints

- 所有修改在 `D:\Backup\mc mod\哈比列车api\` 项目内完成
- 自定义角色必须使用 `habitrain:blackout_bad` 命名空间
- 不修改 SRE 的 `RoleIntroduceScreen` 或任何 SRE 源码
- `BlackoutRoleManager` 内部角色分配逻辑不变（2 杀手 / 8 平民，ceil(n/6) 公式）
- SRE 地图重置、房间传送、商店等机制不受影响
- 修改完成后必须执行 `./gradlew clean build` 并拷贝 JAR 到 `D:\Backup\mc mod\临时\`

---

### Task 1: 创建自定义 SRE 中立角色 + 调整初始化顺序

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java` (全文件重写)

**Interfaces:**
- Consumes: `BlackoutRoleManager.initRandomAssignment(List<ServerPlayer>)` — 现有方法
- Consumes: `TMMRoles.registerRole(SRERole)` — SRE API
- Consumes: `Faction.BAD` — BlackoutRoleManager.Faction 枚举值
- Produces: `SREBlackoutGameMode.initializeGame()` — 正确的初始化流程
- Produces: `SREBlackoutGameMode.BLACKOUT_BAD_ROLE` — 自定义 NormalRole 实例

- [ ] **Step 1: 在 `SREBlackoutGameMode` 中添加自定义角色创建和注册**

在类中添加静态字段和方法：

```java
/** 自定义 Blackout 坏人角色 — 中立阵营，无 SRE 杀手能力 */
private static final NormalRole BLACKOUT_BAD_ROLE = createBadRole();

private static NormalRole createBadRole() {
    NormalRole role = new NormalRole(
        ResourceLocation.fromNamespaceAndPath("habitrain", "blackout_bad"),
        0xAA0000,                       // 颜色：暗红
        false,                          // isInnocent — 不在平民阵营
        false,                          // canUseKiller — 无 SRE 杀手能力
        io.wifi.starrailexpress.api.SRERole.MoodType.NEUTRAL,
        100,                            // maxSprintTime
        false                           // canSeeTime
    );
    role.setNeutrals(true);
    role.setCanPickUpRevolver(false);
    role.setCanUseInstinct(false);
    role.setCanAutoAddMoney(false);
    role.setCanHavePassiveIncome(false);
    TMMRoles.registerRole(role);
    LOGGER.info("Registered Blackout BAD role: {} ({})",
            role.getIdentifier(), "NEUTRAL faction, no SRE killer abilities");
    return role;
}
```

添加需要的 import：

```java
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutRoleManager.Faction;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.Harpymodloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
```

- [ ] **Step 2: 修改 `initializeGame()` 方法 — 调整顺序 + 分配自定义角色**

```java
@Override
public void initializeGame(ServerLevel world, SREGameWorldComponent game,
                           List<ServerPlayer> players) {
    // 1. 标准 SRE 初始化
    Harpymodloader.refreshRoles();
    game.clearRoleMap();

    // 2. 将玩家加入游戏队伍
    addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

    // 3. 分配 Blackout 阵营（记录到 BlackoutRoleManager）
    BlackoutRoleManager.initRandomAssignment(players);

    // 4. 分配 SRE 角色：好人=平民阵营，坏人=中立阵营
    //    → SRE 看到两个不同阵营，不会触发"全员同阵营→结束游戏"
    for (ServerPlayer player : players) {
        boolean isBad = BlackoutRoleManager.getFaction(player.getUUID()) == Faction.BAD;
        game.addRole(player, isBad ? BLACKOUT_BAD_ROLE : TMMRoles.CIVILIAN, false);
    }
    game.syncRoles();

    // 5. 最后启动 SRE 游戏（角色已分配完毕，阵营已同步）
    executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");
}
```

- [ ] **Step 3: Verify the file compiles**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL in Xs`（或列出编译错误）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java
git commit -m "fix: create custom NEUTRAL SRE role for blackout BAD faction + reorder initializeGame to prevent SRE from ending game"
```

---

### Task 2: 创建 Blackout 角色介绍 GUI

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/BlackoutRoleIntroduceScreen.java`

**Interfaces:**
- Consumes: 无（纯客户端，独立 Screen）
- Produces: `BlackoutRoleIntroduceScreen` — 可通过 `Minecraft.getInstance().setScreen()` 打开

- [ ] **Step 1: 创建角色数据模型**

在 `BlackoutRoleIntroduceScreen` 类顶部：

```java
package com.habitrain.core.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 停电模式 — 角色介绍 GUI。
 * 纯客户端，展示 Blackout 模式的 3 种角色（平民/杀手/警长）。
 * 按 U 键打开（仅在 Blackout 模式激活时）。
 */
public class BlackoutRoleIntroduceScreen extends Screen {

    // ====== 角色数据 ======

    private record RoleInfo(
        String name,           // 角色名
        String faction,        // 阵营
        String goal,           // 目标
        String ability,        // 能力
        String description,    // 描述
        int color              // 主题色
    ) {}

    private static final List<RoleInfo> ROLES = List.of(
        new RoleInfo(
            "平民", "好人", "存活到最后，在停电中生存",
            "无特殊能力",
            "普通乘客。通过完成任务获得金币，\n注意观察周围玩家的异常行为。",
            0x55FF55  // 绿色
        ),
        new RoleInfo(
            "杀手", "坏人", "消灭所有好人",
            "可在商店购买 TACZ 沙漠之鹰击杀平民",
            "隐藏在人群中的杀手。利用停电\n掩护行动，但注意不要暴露身份。",
            0xFF5555  // 红色
        ),
        new RoleInfo(
            "警长", "好人", "找出并消灭杀手",
            "通过投票选出，可购买 TACZ 武器",
            "唯一可以击杀杀手的好人。利用\n投票环节争取支持，谨慎选择目标。",
            0xFFFF55  // 黄色
        )
    );
```

- [ ] **Step 2: 实现 Screen 构造函数和字段**

```java
    // ====== GUI 布局常量 ======
    private static final int GUI_WIDTH = 320;
    private static final int GUI_HEIGHT = 200;
    private static final int LEFT_PANEL_W = 80;
    private static final int RIGHT_PANEL_W = GUI_WIDTH - LEFT_PANEL_W - 20;
    private static final int CARD_H = 24;
    private static final int CARD_GAP = 4;
    private static final int TOP_BAR_H = 20;

    private int guiLeft, guiTop;
    private int selectedIndex = 0;

    public BlackoutRoleIntroduceScreen() {
        super(Component.literal("停电模式角色介绍"));
    }

    @Override
    protected void init() {
        super.init();
        this.guiLeft = (this.width - GUI_WIDTH) / 2;
        this.guiTop = (this.height - GUI_HEIGHT) / 2;
    }
```

- [ ] **Step 3: 实现渲染方法**

```java
    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);

        int x = guiLeft;
        int y = guiTop;

        // 绘制背景
        gui.fill(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xC0101010);    // 半透明背景
        gui.fill(x, y, x + GUI_WIDTH, y + TOP_BAR_H, 0xD0333333);     // 顶栏

        // 标题
        gui.drawString(
            Minecraft.getInstance().font,
            "⚡ 停电模式 · 角色介绍",
            x + 8, y + 5, 0xFFFFFF
        );

        y += TOP_BAR_H;

        // 左侧角色列表
        int leftY = y + 8;
        for (int i = 0; i < ROLES.size(); i++) {
            RoleInfo role = ROLES.get(i);
            int cardX = x + 8;
            int cardY = leftY + i * (CARD_H + CARD_GAP);

            boolean hovered = mouseX >= cardX && mouseX <= cardX + LEFT_PANEL_W
                           && mouseY >= cardY && mouseY <= cardY + CARD_H;
            boolean selected = i == selectedIndex;

            // 卡片背景
            int bgColor = selected ? 0xD0 + (role.color() & 0x00FFFFFF) : 0xC0444444;
            if (hovered && !selected) bgColor = 0xC0555555;
            gui.fill(cardX, cardY, cardX + LEFT_PANEL_W, cardY + CARD_H, bgColor);

            // 角色名
            gui.drawString(
                Minecraft.getInstance().font,
                role.name(),
                cardX + 4, cardY + (CARD_H - 9) / 2,
                selected ? role.color() : 0xCCCCCC
            );
        }

        // 右侧详情面板
        int rightX = x + LEFT_PANEL_W + 16;
        int rightY = y + 8;
        int rightW = RIGHT_PANEL_W;

        if (selectedIndex >= 0 && selectedIndex < ROLES.size()) {
            RoleInfo selectedRole = ROLES.get(selectedIndex);

            // 分隔线
            gui.fill(rightX, rightY, rightX + rightW, rightY + 1, selectedRole.color());

            int textY = rightY + 8;
            int lineHeight = 10;

            // 角色名
            gui.drawString(
                Minecraft.getInstance().font,
                selectedRole.name(),
                rightX, textY, selectedRole.color()
            );
            textY += lineHeight + 4;

            // 阵营
            gui.drawString(
                Minecraft.getInstance().font,
                "§7阵营: " + selectedRole.faction(),
                rightX, textY, 0xCCCCCC
            );
            textY += lineHeight + 2;

            // 目标
            gui.drawString(
                Minecraft.getInstance().font,
                "§7目标: " + selectedRole.goal(),
                rightX, textY, 0xCCCCCC
            );
            textY += lineHeight + 2;

            // 能力
            gui.drawString(
                Minecraft.getInstance().font,
                "§7能力: " + selectedRole.ability(),
                rightX, textY, 0xCCCCCC
            );
            textY += lineHeight + 4;

            // 描述
            String[] descLines = selectedRole.description().split("\n");
            for (String line : descLines) {
                gui.drawString(
                    Minecraft.getInstance().font,
                    line,
                    rightX, textY, 0xAAAAAA
                );
                textY += lineHeight;
            }
        }

        // 底部提示
        gui.drawString(
            Minecraft.getInstance().font,
            "按 [U] 键关闭",
            x + 8, y + GUI_HEIGHT - TOP_BAR_H - 8, 0x666666
        );
    }
```

- [ ] **Step 4: 实现鼠标点击处理**

```java
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int x = guiLeft;
        int y = guiTop + TOP_BAR_H + 8;

        for (int i = 0; i < ROLES.size(); i++) {
            int cardX = x + 8;
            int cardY = y + i * (CARD_H + CARD_GAP);
            if (mouseX >= cardX && mouseX <= cardX + LEFT_PANEL_W
             && mouseY >= cardY && mouseY <= cardY + CARD_H) {
                selectedIndex = i;
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // U 键关闭（同打开键）
        if (keyCode == 85) { // GLFW_KEY_U
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
```

- [ ] **Step 5: Verify the file compiles**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL in Xs`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/BlackoutRoleIntroduceScreen.java
git commit -m "feat: add BlackoutRoleIntroduceScreen with role descriptions for civilian/killer/sheriff"
```

---

### Task 3: 修改按键绑定 — U 键打开角色介绍 GUI

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java` (全文件重写)

**Interfaces:**
- Consumes: `BlackoutRoleIntroduceScreen` — 新 GUI 类
- Produces: U 键绑定，在 Blackout 模式中打开角色介绍

- [ ] **Step 1: 添加 U 键绑定并注册**

```java
package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutRoleIntroduceScreen;
import com.habitrain.core.client.gui.VoteScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 停电模式 — 快捷键注册
 * P = 打开投票 GUI
 * U = 打开角色介绍 GUI
 */
public class BlackoutKeyHandler {

    private static final KeyMapping VOTE_KEY = new KeyMapping(
            "key.habitrain.blackout.vote",
            GLFW.GLFW_KEY_P,
            "category.habitrain.blackout"
    );

    private static final KeyMapping ROLE_INTRO_KEY = new KeyMapping(
            "key.habitrain.blackout.role_intro",
            GLFW.GLFW_KEY_U,
            "category.habitrain.blackout"
    );

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        KeyBindingHelper.registerKeyBinding(VOTE_KEY);
        KeyBindingHelper.registerKeyBinding(ROLE_INTRO_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // P 键：投票 GUI
            while (VOTE_KEY.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new VoteScreen());
                }
            }

            // U 键：角色介绍 GUI（仅在 Blackout 模式中）
            while (ROLE_INTRO_KEY.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new BlackoutRoleIntroduceScreen());
                }
            }
        });
    }
}
```

- [ ] **Step 2: 添加语言文件条目**

创建/修改 `src/main/resources/assets/habitrain_core/lang/zh_cn.json`，添加按键翻译：

```json
{
  "category.habitrain.blackout": "停电模式",
  "key.habitrain.blackout.vote": "打开投票界面",
  "key.habitrain.blackout.role_intro": "角色介绍"
}
```

- [ ] **Step 3: Verify the file compiles**

Run: `./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL in Xs`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java src/main/resources/assets/habitrain_core/lang/zh_cn.json
git commit -m "feat: add U keybinding to open BlackoutRoleIntroduceScreen"
```

---

### Task 4: Build & Deploy

- [ ] **Step 1: 完整构建**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL in Xs`

- [ ] **Step 2: 复制 JAR 到临时文件夹**

```bash
cp build/libs/*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 3: 如果 companion mod 依赖核心 mod**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
cp "D:/Backup/mc mod/哈比列车api/build/libs/habitrain_core-*.jar" libs/
./gradlew clean build 2>&1 | tail -30
cp build/libs/*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 4: Final commit**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add -A
git commit -m "chore: build artifacts after blackout game loop fix and role GUI"
```
