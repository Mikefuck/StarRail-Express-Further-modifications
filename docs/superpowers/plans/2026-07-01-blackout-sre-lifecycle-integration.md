# Blackout-SRE Lifecycle Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fully integrate Blackout mode lifecycle with SRE (StarRailExpress) — no independent stop command, sheriff auto-assigned at start, native welcome announcement with sound, filtered role-introduction screen.

**Architecture:** Remove `BlackoutVotingEngine` / `VoteScreen` / `BlackoutVotePayload`. Add sheriff allocation logic to `SREBlackoutGameMode`. Create custom `BlackoutAnnouncePayload` + `BlackoutWelcomeRenderer` for game-start sound+text animation. Create `BlackoutRoleIntroduceScreen` as a trimmed copy of SRE's `RoleIntroduceScreen`. Client listens to `OnGameFinishedClient.EVENT` for instant HUD cleanup.

**Tech Stack:** Fabric API (Fabric events, client networking), StarRailExpress SRE mod (existing dependency)

## Global Constraints

- All new files go under `com.habitrain.core` package — same as existing code
- Network packets follow the existing `CustomPacketPayload` + `StreamCodec` pattern (see `BlackoutTimerPayload` for reference)
- Sound events reuse `TMMSounds` from SRE mod (`io.wifi.starrailexpress.index.TMMSounds`)
- Client-only code in `com.habitrain.core.client` subpackage, guarded by `@Environment(EnvType.CLIENT)`
- Run `./gradlew clean build` after completion and copy JAR to `D:\Backup\mc mod\临时\`

---

### Task 1: Create BlackoutAnnouncePayload + BlackoutWelcomeRenderer

**Files:**
- Create: `src/main/java/com/habitrain/core/network/BlackoutAnnouncePayload.java`
- Create: `src/main/java/com/habitrain/core/client/gui/BlackoutWelcomeRenderer.java`

**Interfaces:**
- Consumes: `TMMSounds.UI_RISER`, `TMMSounds.UI_PIANO`, `TMMSounds.UI_PIANO_STINGER` from SRE mod
- Produces: `BlackoutAnnouncePayload` (sent by Task 2 server-side, received client-side by Task 5)

- [ ] **Step 1: Write `BlackoutAnnouncePayload.java`**

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BlackoutAnnouncePayload(
    String roleName,        // 显示名, e.g. "黑化杀手"
    String subtitle,        // 副标题, e.g. "§7坏人阵营 — 破坏列车，消灭好人"
    String goal,            // 目标, e.g. "消灭所有好人"
    int killerCount,
    int targetCount
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutAnnouncePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_announce"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutAnnouncePayload> CODEC =
            StreamCodec.ofMember(BlackoutAnnouncePayload::write, BlackoutAnnouncePayload::new);

    private BlackoutAnnouncePayload(FriendlyByteBuf buf) {
        this(buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(roleName, 256);
        buf.writeUtf(subtitle, 256);
        buf.writeUtf(goal, 256);
        buf.writeVarInt(killerCount);
        buf.writeVarInt(targetCount);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
```

- [ ] **Step 2: Write `BlackoutWelcomeRenderer.java`** — A simplified welcome animation renderer (no end-game logic like `RoundTextRenderer`):

```java
package com.habitrain.core.client.gui;

import io.wifi.starrailexpress.index.TMMSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * 停电模式开局报幕渲染器。
 * 基于 SRE 的 RoundTextRenderer 报幕逻辑简化实现，仅包含欢迎动画 + 音效。
 * 无游戏结束渲染，不依赖 SRE 内部类。
 */
public class BlackoutWelcomeRenderer {
    private static final int WELCOME_DURATION = 200;
    private static String roleName = "";
    private static String subtitle = "";
    private static String goal = "";
    private static int welcomeTime = 0;

    /** 启动报幕动画 */
    public static void startWelcome(String name, String sub, String g, int killers, int targets) {
        roleName = "§6§l你是 " + name;
        subtitle = sub;
        goal = g;
        welcomeTime = WELCOME_DURATION;
    }

    public static boolean isActive() { return welcomeTime > 0; }

    public static void tick() {
        if (welcomeTime <= 0) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { welcomeTime = 0; return; }

        switch (welcomeTime) {
            case 200 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_RISER, SoundSource.MASTER, 10f, 1f, player.getRandom().nextLong());
            case 180 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO, SoundSource.MASTER, 10f, 1.25f, player.getRandom().nextLong());
            case 120 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO, SoundSource.MASTER, 10f, 1.5f, player.getRandom().nextLong());
            case 60 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO, SoundSource.MASTER, 10f, 1.75f, player.getRandom().nextLong());
            case 1 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO_STINGER, SoundSource.MASTER, 10f, 1f, player.getRandom().nextLong());
        }
        welcomeTime--;
    }

    public static void render(GuiGraphics g, float partialTick) {
        if (welcomeTime <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float cx = w / 2f;
        float cy = h / 2f + 3.5f;

        // 角色名 (tick 200-181)
        if (welcomeTime <= 200 && welcomeTime > 120) {
            var txt = Component.literal(roleName);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().scale(2.6f, 2.6f, 1f);
            g.drawCenteredString(font, txt, 0, -12, 0xFFFFFF);
            g.pose().popPose();
        }
        // 副标题 (tick 180-121)
        if (welcomeTime <= 180 && welcomeTime > 60) {
            var txt = Component.literal(subtitle);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().scale(1.2f, 1.2f, 1f);
            g.drawCenteredString(font, txt, 0, 0, 0xFFFFFF);
            g.pose().popPose();
        }
        // 目标 (tick 120-61)
        if (welcomeTime <= 120 && welcomeTime > 0) {
            var txt = Component.literal(goal);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.drawCenteredString(font, txt, 0, 14, 0xFFFFFF);
            g.pose().popPose();
        }
    }

    public static void reset() {
        welcomeTime = 0;
        roleName = "";
        subtitle = "";
        goal = "";
    }
}
```

- [ ] **Step 3: Register `BlackoutAnnouncePayload` in `HabiTrainCore.onInitialize()`**

Add `BlackoutAnnouncePayload.register();` after existing payload registrations.

- [ ] **Step 4: Register `BlackoutWelcomeRenderer` tick + HUD render in `HabiTrainCoreClient.onInitializeClient()`**

Add:
```java
// 报幕 tick
ClientTickEvents.END_CLIENT_TICK.register(client -> {
    BlackoutWelcomeRenderer.tick();
});
// 报幕渲染 (HUD render is already registered for BlackoutHudOverlay)
// Add call at the end of the existing HudRenderCallback lambda:
// BlackoutWelcomeRenderer.render(g, tickDelta);
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/network/BlackoutAnnouncePayload.java src/main/java/com/habitrain/core/client/gui/BlackoutWelcomeRenderer.java
git commit -m "feat(blackout): add announce payload + welcome renderer for game start"
```

---

### Task 2: Modify SREBlackoutGameMode — Add Sheriff Allocation + Send Annoucements

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java`

**Interfaces:**
- Consumes: `BlackoutAnnouncePayload` (from Task 1), `ServerPlayNetworking`
- Produces: Sheriff-assigned role map, announcement packets sent to players

- [ ] **Step 1: Add `assignSheriffs()` to `BlackoutRoleManager`**

```java
/**
 * 按杀手数量分配警长。
 * 在 initRandomAssignment 之后调用。
 * 杀手 1 人 → 警长 1 人，杀手 3 人 → 警长 3 人，以此类推。
 */
public static void assignSheriffs() {
    if (sheriffId != null) return; // 防止重复分配
    int killerCount = getRemainingBad();
    int sheriffCount = Math.max(1, killerCount);

    List<UUID> candidates = ROLES.entrySet().stream()
            .filter(e -> e.getValue() == RoleType.CIVILIAN)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    Collections.shuffle(candidates);

    int assigned = 0;
    for (UUID id : candidates) {
        if (assigned >= sheriffCount) break;
        setSheriff(id);
        assigned++;
    }
    LOGGER.info("BlackoutRoleManager: Assigned {} SHERIFF(s) ({} killers, {} candidates)",
            assigned, killerCount, candidates.size());
}
```

- [ ] **Step 2: Modify `SREBlackoutGameMode.initializeGame()`** — Call `assignSheriffs()` then send `BlackoutAnnouncePayload` to each player

```java
@Override
public void initializeGame(ServerLevel world, SREGameWorldComponent game,
                           List<ServerPlayer> players) {
    // 1. Standard SRE init
    Harpymodloader.refreshRoles();
    game.clearRoleMap();
    addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

    // 2. Assign Blackout factions (CIVILIAN/KILLER)
    BlackoutRoleManager.initRandomAssignment(players);

    // 3. ★ Allocate sheriffs based on killer count
    BlackoutRoleManager.assignSheriffs();

    // 4. Assign SRE roles (GOOD = CIVILIAN, BAD = KILLER)
    for (ServerPlayer player : players) {
        boolean isBad = BlackoutRoleManager.getFaction(player.getUUID()) == Faction.BAD;
        game.addRole(player, isBad ? TMMRoles.KILLER : TMMRoles.CIVILIAN, false);
    }
    game.syncRoles();

    // 5. ★ Send BlackoutAnnouncePayload to each player
    int killerCount = BlackoutRoleManager.getRemainingBad();
    int goodCount = players.size() - killerCount;
    for (ServerPlayer player : players) {
        UUID pid = player.getUUID();
        var role = BlackoutRoleManager.getRole(pid);
        boolean isBad = BlackoutRoleManager.getFaction(pid) == Faction.BAD;
        String roleName;
        String subtitle;
        String goal;
        if (role == RoleType.KILLER) {
            roleName = "黑化杀手";
            subtitle = "§c坏人阵营 — 破坏列车，消灭好人";
            goal = "消灭所有好人，不要让列车恢复供电！";
        } else if (role == RoleType.SHERIFF) {
            roleName = "警长";
            subtitle = "§b好人阵营 — 找出并制裁杀手";
            goal = "暗中调查可疑玩家，用枪维护秩序！";
        } else {
            roleName = "黑化平民";
            subtitle = "§a好人阵营 — 完成任务，存活到最后";
            goal = "完成好人任务，活下去！";
        }
        ServerPlayNetworking.send(player,
                new BlackoutAnnouncePayload(roleName, subtitle, goal, killerCount, goodCount));
    }

    // 6. Start SRE game
    executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java
git commit -m "feat(blackout): assign sheriffs by killer count, send announce payloads"
```

---

### Task 3: Simplify BlackoutMode Lifecycle

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`

**Changes:**
- Remove independent lifecycle states (sreGameRunning, sreStartAttempted, sreForceActivated, sreStartWaitTicks)
- Remove voting-related code (votingPhasePassed, BlackoutVotingEngine calls)
- Remove forceEndGame() method
- Keep only: timer + HUD sync + simplified SRE end detection

- [ ] **Step 1: Remove field declarations — delete these fields**

```java
// DELETE (lines 42-48):
// private boolean sreGameRunning = false;
// private boolean sreStartAttempted = false;
// private boolean sreForceActivated = false;
// private int sreStartWaitTicks = 0;
```

- [ ] **Step 2: Simplify `onPreStart()` — remove these lines**

```java
// DELETE:
// this.sreGameRunning = false;
// this.sreStartAttempted = false;
// this.sreForceActivated = false;
// this.sreStartWaitTicks = 0;
```

- [ ] **Step 3: Simplify `onStart()` — replace with just `TACZWeaponBridge` and remove SRE start logic**

```java
@Override
public void onStart(ServerLevel level) {
    TACZWeaponBridge.register();
    // SRE game is started by SREBlackoutGameMode.initializeGame()
    HabiTrainCore.LOGGER.info("BlackoutMode: SRE lifecycle managed by SREBlackoutGameMode");
}
```

- [ ] **Step 4: Simplify `onTick()` — remove SRE start detection + voting, keep timer + victory**

Replace the current `onTick()` with a simplified version:

```java
@Override
public void onTick(ServerLevel level) {
    if (level != currentLevel || gameEnded) return;

    var sreGame = SREGameWorldComponent.KEY.get(level);
    boolean sreActive = sreGame != null && sreGame.isRunning();

    // SRE game ended externally (e.g., tmm stop) → end BlackoutMode
    if (!sreActive && !gameEnded) {
        endGame("§6对局结束");
        return;
    }

    if (!sreActive) return;

    // Timer only runs while SRE is active
    tickAccumulator++;
    if (tickAccumulator % 20 == 0) {
        BlackoutTimerSystem.tickSecond();
        checkVictory();

        // Broadcast time sync
        int totalTime = BlackoutTimerSystem.getTotalTimeRemaining();
        boolean permDark = BlackoutTimerSystem.isPermanentBlackoutActive();
        int maintTime = BlackoutTimerSystem.getMaintenanceTime();
        int cd = BlackoutTimerSystem.getBlackoutCountdown();
        BlackoutTimerPayload.broadcastToAll(level.getServer(),
                totalTime,
                permDark ? 0 : (maintTime > 0 ? maintTime : cd),
                permDark || BlackoutTimerSystem.isTransientBlackoutActive(),
                BlackoutTimerSystem.getPhase().ordinal());

        // Reapply permanent blackout
        if (tickAccumulator % 40 == 0 && BlackoutTimerSystem.isPermanentBlackoutActive()) {
            reapplyPermanentBlackout();
        }
    }
}
```

- [ ] **Step 5: Simplify `onCleanup()` — remove `BlackoutVotingEngine.reset()`**

```java
@Override
public void onCleanup(ServerLevel level) {
    BlackoutRoleManager.clear();
    BlackoutTimerSystem.reset();
    currentLevel = null;
    gameEnded = false;
}
```

- [ ] **Step 6: Remove `forceEndGame()` method entirely** (lines 404-428)

- [ ] **Step 7: Remove `sendRoleTitles()` method** (lines 137-171) — no longer needed, handled by announce payload + SRE

- [ ] **Step 8: Remove `scheduleRoleTitle()` method** (lines 123-135)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "refactor(blackout): simplify lifecycle, remove voting/start detection, remove forceEndGame"
```

---

### Task 4: Modify HabiTrainCore — Remove Stop Command + Vote Registration

**Files:**
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`

**Changes:**
- Remove `/habi_api stop` command branch
- Remove `BlackoutVotePayload.register()` call
- Remove `BlackoutVotingEngine.castVote()` receiver

- [ ] **Step 1: Remove the `stop` subcommand from `/habi_api`** — delete lines 117-131 (the `.then(Commands.literal("stop") ...)` branch).

Keep `/habi_api blackout`, `/habi_api list`, `/habi_api buy_gun`, `/habi_api buy_ammo`.

- [ ] **Step 2: Remove `BlackoutVotePayload.register();` at line 76**

- [ ] **Step 3: Remove import lines** (clean up unused — `BlackoutVotingEngine`, `WinResult` if no longer needed)

- [ ] **Step 4: Remove the `BlackoutVotePayload.TYPE` C2S receiver** (lines 235-242)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "refactor: remove /habi_api stop, remove vote payload registration"
```

---

### Task 5: Client-Side Lifecycle Integration

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java`

**Changes:**
- Import `io.wifi.starrailexpress.event.client.OnGameFinishedClient`
- Import `io.wifi.starrailexpress.event.client.OnGameStartedClient`
- Register `OnGameFinishedClient.EVENT` → `BlackoutHudOverlay.reset()`
- Register `BlackoutAnnouncePayload` receiver → `BlackoutWelcomeRenderer.startWelcome()`
- Remove vote-related handling from `BlackoutStatusPayload` receiver
- Add `BlackoutWelcomeRenderer` tick + render registration

- [ ] **Step 1: Register `OnGameFinishedClient` event listener** (add after existing shutdown/render registrations):

```java
// 监听 SRE 游戏结束事件 → 立即隐藏 HUD
OnGameFinishedClient.EVENT.register(() -> {
    Minecraft.getInstance().execute(() -> {
        BlackoutHudOverlay.reset();
        BlackoutWelcomeRenderer.reset();
    });
});
```

- [ ] **Step 2: Register `OnGameStartedClient` event listener** (in same section):

```java
// 监听 SRE 游戏开始事件 → 准备 HUD
OnGameStartedClient.EVENT.register(() -> {
    Minecraft.getInstance().execute(() -> {
        // HUD will be populated by BlackoutTimerPayload
    });
});
```

- [ ] **Step 3: Register `BlackoutAnnouncePayload` receiver**:

```java
// 开局报幕
ClientPlayNetworking.registerGlobalReceiver(BlackoutAnnouncePayload.TYPE, (payload, ctx) -> {
    ctx.client().execute(() -> {
        BlackoutWelcomeRenderer.startWelcome(
            payload.roleName(), payload.subtitle(), payload.goal(),
            payload.killerCount(), payload.targetCount());
    });
});
```

- [ ] **Step 4: Add tick + render for `BlackoutWelcomeRenderer`** in the appropriate places:

```java
// In existing ClientTickEvents (or create a new registration):
ClientTickEvents.END_CLIENT_TICK.register(client -> {
    BlackoutWelcomeRenderer.tick();
});

// In existing HudRenderCallback lambda, add after BlackoutHudOverlay.render(g):
// BlackoutWelcomeRenderer.render(g, tickDelta);
```

- [ ] **Step 5: Simplify `BlackoutStatusPayload` receiver** — remove `VOTE_OPEN` and `VOTE_RESULT` cases (keep `BLACKOUT_START`, `BLACKOUT_END`, `TIME_WARNING`):

The receiver becomes:
```java
ClientPlayNetworking.registerGlobalReceiver(BlackoutStatusPayload.TYPE, (payload, ctx) -> {
    ctx.client().execute(() -> {
        String msg;
        var st = payload.statusType();
        if (st == StatusType.BLACKOUT_START) msg = "§c⚡ 停电了！";
        else if (st == StatusType.BLACKOUT_END) msg = "§a⚡ 供电恢复";
        else if (st == StatusType.TIME_WARNING) msg = "§e⚠ 仅剩 1 分钟！";
        else msg = "";
        if (ctx.client().player != null && !msg.isEmpty()) {
            ctx.client().player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
        }
    });
});
```

- [ ] **Step 6: Remove unused import `${net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback}`** — keep it, already used for BlackoutHudOverlay

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java
git commit -m "feat(blackout): client lifecycle integration - OnGameFinished, announce receiver"
```

---

### Task 6: Remove P Key + Modify BlackoutKeyHandler

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java`

**Changes:**
- Remove `VOTE_KEY` keybinding (P key)
- Keep `ROLE_INTRO_KEY` (U key) but change it to open `BlackoutRoleIntroduceScreen` (from Task 7)
- Remove `VoteScreen` import

- [ ] **Step 1: Remove `VOTE_KEY` field, its registration, and its while-loop in ClientTickEvents**

The `ROLE_INTRO_KEY` handling changes from reflection to direct constructor call (once Task 7 creates the class).

```java
package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutHudOverlay;
import com.habitrain.core.client.gui.BlackoutRoleIntroduceScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class BlackoutKeyHandler {

    private static final KeyMapping ROLE_INTRO_KEY = new KeyMapping(
            "key.habitrain.blackout.role_intro",
            GLFW.GLFW_KEY_U,
            "category.habitrain.blackout"
    );

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        KeyBindingHelper.registerKeyBinding(ROLE_INTRO_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // U 键 — 打开角色介绍（仅黑灯模式激活时）
            while (ROLE_INTRO_KEY.consumeClick()) {
                if (client.player != null && client.screen == null
                        && BlackoutHudOverlay.isBlackoutModeActive()) {
                    client.setScreen(new BlackoutRoleIntroduceScreen());
                }
            }
        });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java
git commit -m "refactor(blackout): remove P vote key, U opens BlackoutRoleIntroduceScreen"
```

---

### Task 7: Create BlackoutRoleIntroduceScreen

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/BlackoutRoleIntroduceScreen.java`

**Design:**
- Trimmed from SRE's `RoleIntroduceScreen` (~1500 lines → ~300 lines)
- Only 3 roles: 平民, 杀手, 警长
- Left panel: role list with colored cards
- Right panel: role description
- No search, no mode switching, no categories, no items/modifiers

- [ ] **Step 1: Create `BlackoutRoleIntroduceScreen.java`** — lightweight implementation:

```java
package com.habitrain.core.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 停电模式专用角色介绍界面。
 * 只显示本模式的 3 个角色：黑化平民、黑化杀手、警长。
 * 基于 SRE 的 RoleIntroduceScreen 样式简化
 */
public class BlackoutRoleIntroduceScreen extends Screen {

    private static class RoleCard {
        final String name;
        final String description;
        final int color;  // ARGB

        RoleCard(String name, String description, int color) {
            this.name = name;
            this.description = description;
            this.color = color;
        }
    }

    private static final List<RoleCard> ROLES = List.of(
        new RoleCard("黑化平民", "§7好人阵营\n你是一名普通的列车乘客。\n\n§f→ 完成好人任务\n→ 存活到最后\n→ 帮助警长找出杀手", 0xFF44BB66),
        new RoleCard("黑化杀手", "§c坏人阵营\n混入人群的破坏者。\n\n§f→ 消灭所有好人\n→ 破坏列车供电\n→ 不要暴露身份", 0xFFCC2233),
        new RoleCard("警长", "§b好人阵营\n维护正义的执法者。\n\n§f→ 暗中调查可疑玩家\n→ 使用配枪制裁杀手\n→ 保护好自己", 0xFF22BBCC)
    );

    private static final int CARD_H = 42;
    private static final int CARD_SPACING = 4;
    private static final int PANEL_PAD = 6;
    private static final int ICON_SIZE = 26;

    private int selectedIndex = 0;
    private int listScrollOffset = 0;

    public BlackoutRoleIntroduceScreen() {
        super(Component.literal("停电模式 — 角色介绍"));
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("关闭"),
                btn -> onClose())
                .bounds(width / 2 - 50, height - 30, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderBackground(g, mouseX, mouseY, partialTick);

        int usableW = Math.min(600, (int)(width * 0.9f));
        int leftW = (int)(usableW * 0.35f);
        int rightW = usableW - leftW;
        int panelX = (width - usableW) / 2;
        int panelY = 40;
        int panelH = height - 80;

        // Left panel — role list
        drawPanelBg(g, panelX, panelY, leftW, panelH);
        int areaX = panelX + PANEL_PAD;
        int areaY = panelY + PANEL_PAD;
        int areaW = leftW - PANEL_PAD * 2;
        int areaH = panelH - PANEL_PAD * 2;

        g.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);
        for (int i = 0; i < ROLES.size(); i++) {
            RoleCard role = ROLES.get(i);
            int cardY = areaY + i * (CARD_H + CARD_SPACING) - listScrollOffset;
            if (cardY + CARD_H < areaY || cardY > areaY + areaH) continue;

            boolean hovered = mouseX >= areaX && mouseX < areaX + areaW
                    && mouseY >= cardY && mouseY < cardY + CARD_H;
            boolean selected = i == selectedIndex;
            renderListCard(g, role, areaX, cardY, areaW, CARD_H, hovered, selected);
        }
        g.disableScissor();

        // Right panel — detail
        int rightX = panelX + leftW;
        drawPanelBg(g, rightX, panelY, rightW, panelH);
        if (selectedIndex >= 0 && selectedIndex < ROLES.size()) {
            RoleCard role = ROLES.get(selectedIndex);
            int textX = rightX + PANEL_PAD + 2;
            int textY = panelY + 12;
            int maxW = rightW - PANEL_PAD * 2 - 4;

            // Name
            g.drawString(font, Component.literal(role.name).copy().withStyle(s -> s.withBold(true)),
                    textX, textY, role.color, true);

            // Separator
            g.fill(textX, textY + 14, textX + 40, textY + 16, role.color & 0x88FFFFFF);

            // Description (word-wrapped)
            textY += 22;
            var lines = font.split(Component.literal(role.description), maxW);
            for (var line : lines) {
                g.drawString(font, line, textX, textY, 0xFFFFFF, false);
                textY += font.lineHeight + 2;
            }
        }

        // Title
        g.drawCenteredString(font, this.title, width / 2, 12, 0xF5E8C8);
    }

    private void renderListCard(GuiGraphics g, RoleCard role, int x, int y, int w, int h, boolean hovered, boolean selected) {
        int borderColor = selected ? 0xFFD4AF37 : (hovered ? 0xFF8B6914 : 0xFF5A4530);
        g.fill(x, y, x + w, y + h, borderColor);
        int bg = selected ? 0xFF3A2A10 : (hovered ? 0xFF2A1E0C : 0xFF1A1008);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        // Color bar
        g.fill(x + 1, y + 1, x + 4, y + h - 1, role.color);
        // Name
        g.drawString(font, Component.literal(role.name), x + 10, y + (h - font.lineHeight) / 2, role.color, false);
        // Selection indicator
        if (selected) {
            g.fill(x + w - 4, y + 3, x + w - 1, y + h - 3, 0xFFFFD700);
        }
    }

    private void drawPanelBg(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0xD81A1008, 0xD820140A);
        g.renderOutline(x, y, w, h, 0xFF8B6914);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x22FFE8C0);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int usableW = Math.min(600, (int)(width * 0.9f));
        int leftW = (int)(usableW * 0.35f);
        int panelX = (width - usableW) / 2;
        int panelY = 40;
        int panelH = height - 80;
        int areaX = panelX + PANEL_PAD;
        int areaY = panelY + PANEL_PAD;
        int areaW = leftW - PANEL_PAD * 2;

        if (button == 0 && mx >= areaX && mx < areaX + areaW && my >= areaY && my < areaY + panelH - PANEL_PAD * 2) {
            for (int i = 0; i < ROLES.size(); i++) {
                int cardY = areaY + i * (CARD_H + CARD_SPACING) - listScrollOffset;
                if (my >= cardY && my < cardY + CARD_H) {
                    selectedIndex = i;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int usableW = Math.min(600, (int)(width * 0.9f));
        int leftW = (int)(usableW * 0.35f);
        int panelX = (width - usableW) / 2;
        int panelY = 40;
        int panelH = height - 80;

        if (mx >= panelX && mx < panelX + leftW && my >= panelY && my < panelY + panelH) {
            int totalH = ROLES.size() * (CARD_H + CARD_SPACING);
            int areaH = panelH - PANEL_PAD * 2;
            int maxScroll = Math.max(0, totalH - areaH);
            listScrollOffset = Mth.clamp((int)(listScrollOffset - scrollY * (CARD_H + CARD_SPACING)), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/BlackoutRoleIntroduceScreen.java
git commit -m "feat(blackout): add BlackoutRoleIntroduceScreen for mode-specific role intro"
```

---

### Task 8: Delete Removed Files

**Files:**
- Delete: `src/main/java/com/habitrain/core/game/blackout/BlackoutVotingEngine.java`
- Delete: `src/main/java/com/habitrain/core/client/gui/VoteScreen.java`
- Delete: `src/main/java/com/habitrain/core/network/BlackoutVotePayload.java`

- [ ] **Step 1: Delete files**

```bash
git rm src/main/java/com/habitrain/core/game/blackout/BlackoutVotingEngine.java
git rm src/main/java/com/habitrain/core/client/gui/VoteScreen.java
git rm src/main/java/com/habitrain/core/network/BlackoutVotePayload.java
```

- [ ] **Step 2: Commit**

```bash
git commit -m "refactor(blackout): remove voting system files (BlackoutVotingEngine, VoteScreen, BlackoutVotePayload)"
```

---

### Task 9: Build and Verify

- [ ] **Step 1: Build the project**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, no compilation errors

- [ ] **Step 2: Copy JAR to temp directory**

```bash
cp build/libs/*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 3: Verify no leftover references** — grep for any remaining references to deleted classes

Run: `grep -r "BlackoutVotingEngine\|BlackoutVotePayload\|VoteScreen\|forceEndGame" src/` — should return no results in active code (build directory excluded)
