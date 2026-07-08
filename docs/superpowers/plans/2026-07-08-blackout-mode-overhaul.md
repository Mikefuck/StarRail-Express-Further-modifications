# 停电模式改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overhaul the blackout mode with phone-based police hiring, horn-based exile voting, rain when <8 players, and Simple Voice Chat lobby group retry.

**Architecture:** Four independent modules layered on existing BlackoutRoleManager/BlackoutMode infrastructure. Phone and exile share a new generic vote screen. Weather controller hooks into ModTickHandler. Voice chat fix refactors SREGameModeBase's pending join queue.

**Tech Stack:** Fabric 1.21.1, CustomPacketPayload (S2C/C2S), UseBlockCallback, Cardinal Components (SREPlayerShopComponent), SubtitleNotifier, SRE GameUtils

## Global Constraints

- All new payloads must follow the existing `CustomPacketPayload` + `StreamCodec.ofMember/StreamCodec.of` pattern in `com.habitrain.core.network`
- C2S payloads register with `PayloadTypeRegistry.playC2S()`, S2C with `PayloadTypeRegistry.playS2C()`
- Server-side only classes must not reference client-only classes (no `@Environment(EnvType.CLIENT)` imports in shared code)
- `BlackoutRoleManager` is already the single source of truth for all faction/sheriff/alive tracking
- All services use `ResourceKey<Level>`-keyed `ConcurrentHashMap` for dimension isolation
- After each `./gradlew clean build`, copy `build/libs/habitrain_core-2.0.0.jar` to `D:\Backup\mc mod\临时\`
- File access boundary: never touch `D:\Backup\mc mod\backup\`
- Companion mod `哈比列车更多修改` needs the updated jar in `libs/` before its own build

---

## File Structure

### New Files (13)

| File | Responsibility |
|------|---------------|
| `game/blackout/BlackoutPoliceHireService.java` | Police hire validation + execution (dimension-isolated, server-only) |
| `game/blackout/BlackoutPhoneHandler.java` | `UseBlockCallback` for `yuushya:street_phone` right-click (server-only) |
| `game/blackout/BlackoutHornVoteHandler.java` | `UseBlockCallback` for `trainmurdermystery:horn` double-pull (server-only) |
| `game/blackout/BlackoutExileVoteManager.java` | Exile vote state machine + resolution (dimension-isolated, server-only) |
| `game/blackout/BlackoutOverlayTypes.java` | Constant block type ID constants |
| `game/sre/SREWeatherController.java` | Rain-on-low-players weather control |
| `network/BlackoutPhoneOpenPayload.java` | S2C: phone GUI state snapshot |
| `network/BlackoutHirePolicePayload.java` | C2S: player requests police hire |
| `network/BlackoutVotePayload.java` | S2C: generic vote state (purpose, timer, candidates) |
| `network/BlackoutVoteCastPayload.java` | C2S: player casts a vote |
| `client/gui/BlackoutPhoneHireScreen.java` | Phone hire GUI (single-button panel) |
| `client/gui/BlackoutVoteScreen.java` | Generic vote screen (extracted from sheriff vote screen) |
| `client/gui/BlackoutVoteState.java` | Client-side vote state cache |

### Modified Files (15)

| File | What Changes |
|------|-------------|
| `BlackoutSheriffVoteManager.java` | Comment out `startVote()` call in `tickSecond()` |
| `BlackoutTickCoordinator.java` | Remove sheriff vote tick chain, add phone/exile tick stubs |
| `BlackoutMode.java` | Add reset calls for new services in `onPreStart()`/`onCleanup()` |
| `HabiTrainCore.java` | Register new payloads, C2S receivers, handlers, modify JOIN voice logic |
| `HabiTrainCoreClient.java` | Register new S2C receivers |
| `BlackoutKeyHandler.java` | V-key opens current active vote instead of hardcoded sheriff vote |
| `PayloadSenders.java` | Add `sendHirePolice()`, `sendVoteCast()` |
| `MapScannerMixin.java` | Scan `yuushya:street_phone` into cache with type=90 |
| `CustomTaskBlockRendererMixin.java` | Render type=90 blocks even without active task during blackout |
| `BlackoutRoleManager.java` | Add `getRandomGoodNonSheriff(level, random)` helper |
| `SREGameModeBase.java` | Add `queueLobbyGroupJoin()`, `tryAddPlayerToLobbyGroup()`, fix `processPendingVoiceJoins()` |
| `ModTickHandler.java` | Tick `SREWeatherController` |
| `lang/zh_cn.json` | Add `death_reason.habitrain_core.exile_vote`, GUI text |
| `lang/en_us.json` | Same translations |

---

## Task Breakdown

### Task 1: Disable Auto Sheriff Vote

**Files:**
- Modify: `BlackoutSheriffVoteManager.java` — prevent auto vote start
- Modify: `BlackoutTickCoordinator.java` — remove sheriff vote tick chain
- Modify: `BlackoutMode.java` — keep sheriff reset, no new service resets yet

**Interfaces:**
- Consumes: existing `BlackoutSheriffVoteManager`, `BlackoutTickCoordinator`, `BlackoutMode`
- Produces: sheriff vote never starts automatically; tick coordinator no longer calls sheriff vote

- [ ] **Step 1: Comment out auto-start in BlackoutSheriffVoteManager.tickSecond()**

In `BlackoutSheriffVoteManager.java`, around lines 82-87, change:

```java
state.secondsSinceStart++;
if (!state.started && state.secondsSinceStart >= VOTE_OPEN_DELAY_SECONDS) {
    startVote(level, state);
    justStarted = true;
}
```

To:

```java
state.secondsSinceStart++;
// Auto-vote disabled — police are now hired via yuushya:street_phone after 120s.
// The startVote and sheriff resolve logic are retained for potential future use
// but never triggered automatically.
```

- [ ] **Step 2: Remove sheriff vote tick from BlackoutTickCoordinator**

In `BlackoutTickCoordinator.java`, around lines 64-66, change:

```java
BlackoutSheriffVoteManager.tickSecond(level)
        .ifPresent(res -> sheriffResolver.applyVoteResult(level, res));
```

To:

```java
// Auto sheriff vote removed — police hire and exile vote ticked separately.
```

- [ ] **Step 3: Verify no compile errors**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (the sheriff vote code remains valid, just unreachable)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutSheriffVoteManager.java \
        src/main/java/com/habitrain/core/game/blackout/BlackoutTickCoordinator.java
git commit -m "refactor: disable auto sheriff vote start (replaced by phone hire)"
```

---

### Task 2: Phone Police Hire Service

**Files:**
- Create: `game/blackout/BlackoutPoliceHireService.java`
- Modify: `BlackoutRoleManager.java` — add helper for finding non-police good candidates

**Interfaces:**
- Consumes: `BlackoutRoleManager.getRandomPoliceRole()`, `BlackoutRoleManager.setSheriff()`, `BlackoutRoleManager.getRemainingBad()`, `BlackoutRoleManager.getSheriffCount()`, `BlackoutRoleManager.isAlive()`, `BlackoutRoleManager.getFaction()`, `SREPlayerShopComponent.KEY.get(player).balance / .addToBalance()`
- Produces: `BlackoutPoliceHireService.tryHire(ServerLevel, ServerPlayer) -> HireResult` (enum: SUCCESS/error reason)

- [ ] **Step 1: Add `getRandomGoodNonSheriff()` helper to BlackoutRoleManager**

Add to `BlackoutRoleManager.java` after line 180 (after `getAllAlive`):

```java
/**
 * 从当前存活、好人阵营、非警察的玩家中随机选择一个。
 * 供电话雇佣警察时选择转职目标。
 * @return 目标 UUID，如果没有合适候选人则 null
 */
@org.jetbrains.annotations.Nullable
public static UUID getRandomGoodNonSheriff(ServerLevel level, java.util.Random random) {
    RoleState state = INSTANCES.get(level.dimension());
    if (state == null) return null;
    List<UUID> candidates = new ArrayList<>();
    for (UUID id : state.roles.keySet()) {
        if (!state.sheriffs.contains(id) && state.factions.get(id) == Faction.GOOD) {
            candidates.add(id);
        }
    }
    return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
}
```

Also add the import for `java.util.Random` if not already present.

- [ ] **Step 2: Create BlackoutPoliceHireService.java**

`src/main/java/com/habitrain/core/game/blackout/BlackoutPoliceHireService.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.game.blackout.sre.SREBlackoutGameMode;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 电话雇佣警察服务。
 *
 * 每局每个 {@link ServerLevel#dimension()} 隔离运行。
 * 局未开始前电话不可用（check {@link #isPhoneUnlocked} 需先调用 reset 设置开始时间）。
 */
public final class BlackoutPoliceHireService {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutPoliceHireService");

    private static final int UNLOCK_SECONDS = 120;
    private static final int HIRE_COST = 300;

    private static final ConcurrentMap<ResourceKey<Level>, HireState> STATES = new ConcurrentHashMap<>();

    private BlackoutPoliceHireService() {}

    private static final class HireState {
        long gameStartTick = 0;
        final Set<UUID> hasHired = new HashSet<>();
    }

    private static HireState getOrCreate(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new HireState());
    }

    /** 对局开始时调用，记录开始时间 */
    public static void reset(ServerLevel level) {
        HireState state = new HireState();
        state.gameStartTick = level.getGameTime();
        STATES.put(level.dimension(), state);
        LOGGER.info("[PoliceHire] reset for {}, startTick={}", level.dimension().location(), state.gameStartTick);
    }

    /** 对局清理时调用，移除状态 */
    public static void cleanup(ServerLevel level) {
        STATES.remove(level.dimension());
        LOGGER.info("[PoliceHire] cleanup for {}", level.dimension().location());
    }

    /** 电话是否已解锁（开局后 120 秒） */
    public static boolean isPhoneUnlocked(ServerLevel level) {
        HireState state = STATES.get(level.dimension());
        if (state == null) return false;
        return level.getGameTime() - state.gameStartTick >= UNLOCK_SECONDS * 20L;
    }

    /** 返回剩余解锁秒数（负数表示已解锁） */
    public static int getRemainingLockSeconds(ServerLevel level) {
        HireState state = STATES.get(level.dimension());
        if (state == null) return UNLOCK_SECONDS;
        long elapsed = (level.getGameTime() - state.gameStartTick) / 20;
        return (int) Math.max(0, UNLOCK_SECONDS - elapsed);
    }

    /** 发起者本局是否已雇佣过 */
    public static boolean hasHired(ServerLevel level, UUID playerId) {
        HireState state = STATES.get(level.dimension());
        return state != null && state.hasHired.contains(playerId);
    }

    /**
     * 尝试雇佣警察。
     * @return null 表示成功，非 null 为错误消息
     */
    @org.jetbrains.annotations.Nullable
    public static Component tryHire(ServerLevel level, ServerPlayer initiator) {
        // 1. 停电模式对局检查
        var sreGame = SREGameWorldComponent.KEY.get(level);
        if (sreGame == null || !sreGame.isRunning()) {
            return Component.literal("§c当前不在停电对局中");
        }

        HireState state = STATES.get(level.dimension());
        if (state == null) {
            return Component.literal("§c电话系统尚未就绪");
        }

        // 2. 是否解锁
        if (!isPhoneUnlocked(level)) {
            return Component.literal("§c报警线路尚未接通（剩余 " + getRemainingLockSeconds(level) + " 秒）");
        }

        // 3. 本局已雇佣
        if (state.hasHired.contains(initiator.getUUID())) {
            return Component.literal("§c你本局已经拨打过110");
        }

        // 4. 金币余额
        var shop = SREPlayerShopComponent.KEY.get(initiator);
        if (shop == null || shop.balance < HIRE_COST) {
            return Component.literal("§c话费不足，需要 " + HIRE_COST + " 金币");
        }

        // 5. 杀手阵营人数
        int killerCount = BlackoutRoleManager.getRemainingBad(level);
        if (killerCount <= 0) {
            return Component.literal("§c当前没有杀手，无需聘请警察");
        }

        // 6. 警察不超杀手
        int sheriffCount = BlackoutRoleManager.getSheriffCount(level);
        if (sheriffCount + 1 > killerCount) {
            return Component.literal("§c当前警力已足够，无法继续聘请");
        }

        // 7. 随机警察职业
        Random random = new Random(level.getRandom().nextLong());
        var policeRole = BlackoutRoleManager.getRandomPoliceRole(random);
        if (policeRole == null) {
            return Component.literal("§c当前警察职业池为空");
        }

        // 8. 候选好人
        UUID targetId = BlackoutRoleManager.getRandomGoodNonSheriff(level, random);
        if (targetId == null) {
            return Component.literal("§c当前没有可转职的好人");
        }

        // ===== 成功流程 =====
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetId);
        if (target == null) {
            return Component.literal("§c目标玩家已离线");
        }

        // 扣金币
        shop.addToBalance(-HIRE_COST);

        // 标记已雇佣
        state.hasHired.add(initiator.getUUID());

        // 转职
        BlackoutRoleManager.setSheriff(level, targetId, policeRole, null);

        // 给目标发送职业介绍
        ServerPlayNetworking.send(target, new BlackoutAnnouncePayload(
                policeRole.getName().getString(),
                policeRole.getDescription().getString(),
                policeRole.getGoal().getString(),
                BlackoutRoleManager.getRemainingBad(level),
                BlackoutRoleManager.getRemainingGood(level)
        ));

        // 全图顶部通报
        String initName = initiator.getName().getString();
        String targetName = target.getName().getString();
        Component notify = Component.literal("§e收到 §b" + initName + " §e举报，§b" + targetName + " §e警长前来调查");
        for (ServerPlayer player : level.players()) {
            SubtitleNotifier.sendTop(player, Component.empty(), notify, 80);
        }

        LOGGER.info("[PoliceHire] {} hired police -> {} (role={})", initName, targetName,
                policeRole.getIdentifier());

        return null; // success
    }
}
```

- [ ] **Step 3: Run build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutPoliceHireService.java \
       src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java
git commit -m "feat: add BlackoutPoliceHireService + getRandomGoodNonSheriff helper"
```

---

### Task 3: Phone Payloads, Handler, and GUI

**Files:**
- Create: `network/BlackoutPhoneOpenPayload.java` (S2C)
- Create: `network/BlackoutHirePolicePayload.java` (C2S)
- Create: `game/blackout/BlackoutPhoneHandler.java` (server-only `UseBlockCallback`)
- Create: `client/gui/BlackoutPhoneHireScreen.java`
- Modify: `HabiTrainCore.java` — register payloads, handler, C2S receiver
- Modify: `HabiTrainCoreClient.java` — register S2C receiver
- Modify: `PayloadSenders.java` — add `sendHirePolice()`

**Interfaces:**
- Consumes: `BlackoutPoliceHireService` methods, existing `BlackoutMode` check
- Produces: phone right-click → GUI → hire flow fully wired

- [ ] **Step 1: Create BlackoutPhoneOpenPayload.java (S2C)**

`src/main/java/com/habitrain/core/network/BlackoutPhoneOpenPayload.java`:

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BlackoutPhoneOpenPayload(
        boolean unlocked,
        int remainingLockSeconds,
        int balance,
        boolean hasHiredThisGame,
        int sheriffCount,
        int killerCount
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutPhoneOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("phone_open"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutPhoneOpenPayload> CODEC =
            StreamCodec.ofMember(BlackoutPhoneOpenPayload::write, BlackoutPhoneOpenPayload::new);

    private BlackoutPhoneOpenPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(unlocked);
        buf.writeVarInt(remainingLockSeconds);
        buf.writeVarInt(balance);
        buf.writeBoolean(hasHiredThisGame);
        buf.writeVarInt(sheriffCount);
        buf.writeVarInt(killerCount);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
```

- [ ] **Step 2: Create BlackoutHirePolicePayload.java (C2S)**

`src/main/java/com/habitrain/core/network/BlackoutHirePolicePayload.java`:

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BlackoutHirePolicePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutHirePolicePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("hire_police"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutHirePolicePayload> CODEC =
            StreamCodec.ofMember((p, buf) -> {}, buf -> new BlackoutHirePolicePayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
```

- [ ] **Step 3: Create BlackoutPhoneHandler.java (server-only)**

`src/main/java/com/habitrain/core/game/blackout/BlackoutPhoneHandler.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 监听 yuushya:street_phone 方块右键，打开电话聘请 GUI。
 */
public final class BlackoutPhoneHandler {
    private static final ResourceLocation STREET_PHONE_ID = ResourceLocation.parse("yuushya:street_phone");
    private static Block cachedStreetPhone = null;

    private static Block getStreetPhoneBlock() {
        if (cachedStreetPhone == null || cachedStreetPhone == Blocks.AIR) {
            cachedStreetPhone = BuiltInRegistries.BLOCK.get(STREET_PHONE_ID);
        }
        return cachedStreetPhone;
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide) return InteractionResult.PASS;
            if (!hand.equals(net.minecraft.world.InteractionHand.MAIN_HAND)) return InteractionResult.PASS;

            // 必须是玩家
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            Block block = level.getBlockState(pos).getBlock();

            Block phoneBlock = getStreetPhoneBlock();
            if (phoneBlock == null || phoneBlock == Blocks.AIR) return InteractionResult.PASS;
            if (!block.equals(phoneBlock)) return InteractionResult.PASS;

            if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

            // 必须是停电模式对局中
            var gameMode = GameModeRegistry.getActiveForLevel(serverLevel);
            if (gameMode.isEmpty() || !"habitrains:blackout".equals(gameMode.get().getId())) {
                return InteractionResult.PASS;
            }

            // 构造状态包
            boolean unlocked = BlackoutPoliceHireService.isPhoneUnlocked(serverLevel);
            int remainingLock = BlackoutPoliceHireService.getRemainingLockSeconds(serverLevel);
            var shop = SREPlayerShopComponent.KEY.get(serverPlayer);
            int balance = shop != null ? shop.balance : 0;
            boolean hasHired = BlackoutPoliceHireService.hasHired(serverLevel, serverPlayer.getUUID());
            int sheriffCount = BlackoutRoleManager.getSheriffCount(serverLevel);
            int killerCount = BlackoutRoleManager.getRemainingBad(serverLevel);

            ServerPlayNetworking.send(serverPlayer, new BlackoutPhoneOpenPayload(
                    unlocked, remainingLock, balance, hasHired, sheriffCount, killerCount));

            return InteractionResult.SUCCESS;
        });

        HabiTrainCore.LOGGER.info("[PhoneHandler] registered for yuushya:street_phone");
    }

    private BlackoutPhoneHandler() {}
}
```

- [ ] **Step 4: Create BlackoutPhoneHireScreen.java (client)**

`src/main/java/com/habitrain/core/client/gui/BlackoutPhoneHireScreen.java`:

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class BlackoutPhoneHireScreen extends Screen {
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 160;

    private final Screen parent;
    private BlackoutPhoneOpenPayload state;
    private Button hireButton;
    private Component statusText = Component.empty();

    public BlackoutPhoneHireScreen(Screen parent, BlackoutPhoneOpenPayload state) {
        super(Component.literal("电话聘请警察"));
        this.parent = parent;
        this.state = state;
    }

    /** 服务端推送了最新状态时调用 */
    public void updateState(BlackoutPhoneOpenPayload newState) {
        this.state = newState;
    }

    @Override
    protected void init() {
        super.init();
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        hireButton = addRenderableWidget(Button.builder(
                Component.literal(state.unlocked() && !state.hasHiredThisGame() ? "§e拨打110" : "§7拨打110"),
                btn -> {
                    if (state.unlocked() && !state.hasHiredThisGame()) {
                        com.habitrain.core.client.network.PayloadSenders.sendHirePolice();
                        statusText = Component.literal("§a正在请求...");
                    }
                })
                .bounds(panelX + 50, panelY + 60, PANEL_W - 100, 30)
                .build());
        hireButton.active = state.unlocked() && !state.hasHiredThisGame();

        addRenderableWidget(Button.builder(Component.literal("关闭"),
                btn -> onClose())
                .bounds(width / 2 - 40, height - 32, 80, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xE01A1520, 0xE0281F2E);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, 0xFF6B8CB3);
        g.fill(panelX + 1, panelY + 1, panelX + PANEL_W - 1, panelY + 2, 0x44D8E7FF);

        Font font = this.font;
        g.drawCenteredString(font, "§6电话聘请警察", width / 2, panelY + 12, 0xF5F7FB);

        // 状态信息
        String info;
        if (state.hasHiredThisGame()) {
            info = "§7你本局已经拨打过110";
        } else if (!state.unlocked()) {
            info = "§7报警线路尚未接通（剩余 " + state.remainingLockSeconds() + " 秒）";
        } else {
            info = "§7花费 §e" + (state.balance() >= 300 ? 300 : state.balance()) + " §7话费拨打110";
            if (state.balance() < 300) {
                info = "§c话费不足（需要300）";
            }
        }
        g.drawCenteredString(font, Component.literal(info), width / 2, panelY + 40, 0xB9C7D9);

        // 警察/杀手数
        g.drawCenteredString(font, Component.literal(
                "§7当前警察: " + state.sheriffCount() + "  §7杀手: " + state.killerCount()),
                width / 2, panelY + 100, 0xB9C7D9);

        // 状态反馈
        if (!statusText.getString().isEmpty()) {
            g.drawCenteredString(font, statusText, width / 2, panelY + 120, 0xFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
```

- [ ] **Step 5: Add sendHirePolice() to PayloadSenders.java**

Add after the `sendSheriffVoteCast` method:

```java
/** 从客户端发送聘请警察请求到服务端。 */
public static void sendHirePolice() {
    if (Minecraft.getInstance().getConnection() == null) return;
    ClientPlayNetworking.send(new com.habitrain.core.network.BlackoutHirePolicePayload());
}
```

- [ ] **Step 6: Register payloads, handler, and C2S receiver in HabiTrainCore.java**

In the `HabiTrainCore.onInitialize()` method (around line 96), after `BlackoutSheriffVoteCastPayload.register();`:

```java
BlackoutPhoneOpenPayload.register();
BlackoutHirePolicePayload.register();
```

In the same method, after `BlackoutSheriffVoteCastPayload.register();` block (around line 109), add handler registration:

```java
BlackoutPhoneHandler.register();
```

After the existing C2S sheriff vote receiver (around line 285), add the hire police receiver:

```java
// C2S 电话聘请警察接收器
ServerPlayNetworking.registerGlobalReceiver(BlackoutHirePolicePayload.TYPE, (payload, context) -> {
    context.server().execute(() -> {
        ServerPlayer player = context.player();
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        if (level == null) return;
        Component error = BlackoutPoliceHireService.tryHire(level, player);
        if (error != null) {
            player.sendSystemMessage(error);
        } else {
            // 成功 — 关闭 GUI
            player.sendSystemMessage(Component.literal("§a已成功聘请警察！"));
        }
    });
});
```

- [ ] **Step 7: Register S2C receiver in HabiTrainCoreClient.java**

In `HabiTrainCoreClient.onInitializeClient()`, add after the existing sheriff vote screen handler:

```java
ClientPlayNetworking.registerGlobalReceiver(com.habitrain.core.network.BlackoutPhoneOpenPayload.TYPE, (payload, ctx) -> {
    // 如果当前已经是电话屏幕，更新状态
    if (ctx.client().screen instanceof BlackoutPhoneHireScreen phoneScreen) {
        phoneScreen.updateState(payload);
    } else {
        // 否则打开电话屏幕
        ctx.client().setScreen(new BlackoutPhoneHireScreen(ctx.client().screen, payload));
    }
});
```

- [ ] **Step 8: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/habitrain/core/network/BlackoutPhoneOpenPayload.java \
       src/main/java/com/habitrain/core/network/BlackoutHirePolicePayload.java \
       src/main/java/com/habitrain/core/game/blackout/BlackoutPhoneHandler.java \
       src/main/java/com/habitrain/core/client/gui/BlackoutPhoneHireScreen.java \
       src/main/java/com/habitrain/core/HabiTrainCore.java \
       src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java \
       src/main/java/com/habitrain/core/client/network/PayloadSenders.java
git commit -m "feat: phone hire payloads, handler, GUI + registrations"
```

---

### Task 4: Phone Constant Overlay (Block Highlight)

**Files:**
- Create: `game/blackout/BlackoutOverlayTypes.java`
- Modify: `game/sre/mixin/MapScannerMixin.java` — scan `yuushya:street_phone`
- Modify: `client/mixin/CustomTaskBlockRendererMixin.java` — render phone even without active task

**Interfaces:**
- Consumes: `CustomTaskBlockCache.put()`, `CustomTaskBlockCache.hasBlockForTypeId()`, existing `isGameRunning()` cache
- Produces: phone blocks highlighted during blackout even without active task

- [ ] **Step 1: Create BlackoutOverlayTypes.java**

`src/main/java/com/habitrain/core/game/blackout/BlackoutOverlayTypes.java`:

```java
package com.habitrain.core.game.blackout;

/**
 * 常量透视方块类型 ID。
 * blockTypeId < 12 是 SRE 原版保留，≥12 是自定义，12 本身被跳过。
 * STREET_PHONE = 90 确保不与任何注册任务冲突。
 */
public final class BlackoutOverlayTypes {
    /** yuushya:street_phone 方块在透视缓存中的 typeId */
    public static final int STREET_PHONE = 90;

    private BlackoutOverlayTypes() {}
}
```

- [ ] **Step 2: Add phone scanning to MapScannerMixin**

In `MapScannerMixin.java`, after the food platter handling loop (line 142, `continue;`), add a special detection block before the `Set<Integer> typeIds = blockToTypeIds.get(block);` section:

Add at the beginning of the scanning loop body (after `BlockState state = serverLevel.getBlockState(pos)` at line 112, before the food platter check at line 118):

```java
// === 常量透视方块（非任务）===
// yuushya:street_phone 始终在停电模式中高亮，不依赖 active task
if (block instanceof net.minecraft.world.level.block.Block &&
        block == com.habitrain.core.game.blackout.BlackoutOverlayTypes.getStreetPhoneBlock()) {
    CustomTaskBlockCache.put(pos, com.habitrain.core.game.blackout.BlackoutOverlayTypes.STREET_PHONE, block);
    totalAddedCount++;
    continue;
}
```

Wait, `BlackoutOverlayTypes` is a constants class, not a block resolver. Let me have it integrate differently. Actually, let me add a helper method in `BlackoutOverlayTypes`:

Add to `BlackoutOverlayTypes.java`:

```java
private static Block cachedStreetPhone = null;

/** 获取 yuushya:street_phone 方块实例（缓存版） */
public static Block getStreetPhoneBlock() {
    if (cachedStreetPhone == null || cachedStreetPhone == net.minecraft.world.level.block.Blocks.AIR) {
        cachedStreetPhone = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.parse("yuushya:street_phone"));
    }
    return cachedStreetPhone;
}
```

Then in MapScannerMixin, before the `blockToTypeIds` loop (after line 95 `CustomTaskBlockCache.clear()`), not inline in the scanning loop — it's cleaner to register the phone as a task-like block in the `blockToTypeIds` map. Actually no, the doc says "不要使用 blockTypeId = 12" and the phone isn't a task. Let me keep it as a special case.

Actually, the simplest approach: add a constant scan block entry AFTER the `blockToTypeIds` population and BEFORE the scanning loop:

In `MapScannerMixin.java`, after the `if (blockToTypeIds.isEmpty())` block at line 97, and before the loop that starts at line 108, resolve the phone block once:

```java
// 常量透视：yuushya:street_phone（type=90）
Block phoneBlock = com.habitrain.core.game.blackout.BlackoutOverlayTypes.getStreetPhoneBlock();
if (phoneBlock != null && phoneBlock != Blocks.AIR) {
    blockToTypeIds.computeIfAbsent(phoneBlock, k -> new HashSet<>()).add(
            com.habitrain.core.game.blackout.BlackoutOverlayTypes.STREET_PHONE);
}
```

This is elegant — the phone block gets treated exactly like a task block in the cache, but with type ID 90. It'll be stored in the cache and broadcast to clients via the existing `CustomTaskBlockPayload.broadcastToAll` path.

Wait, but there's a problem — the existing block cache clearing at line 97 (`CustomTaskBlockCache.clear()`) is called before the loop. Let me check... yes, `CustomTaskBlockCache.clear()` at line 97 clears everything, then the loop repopulates. So adding the phone block to `blockToTypeIds` before the loop is correct — it'll be processed in the loop.

But wait, I need the phone block imported. Let me use `BuiltInRegistries.BLOCK.get()` inline or through `BlackoutOverlayTypes`. Actually the `BlackoutOverlayTypes.getStreetPhoneBlock()` approach is cleaner.

Wait, there's a better way to find the phone block. Let me hardcode the ResourceLocation in the mixin. Let me add the import:

```java
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
```

And for the Blocks import, make sure it's there (`import net.minecraft.world.level.block.Blocks;` - yes it is).

Then add these lines right before the scan loop (after line 107):

```java
// 常量透视方块（非任务但在停电模式中需高亮）
Block phoneBlock = BlackoutOverlayTypes.getStreetPhoneBlock();
if (phoneBlock != null && phoneBlock != Blocks.AIR) {
    blockToTypeIds.computeIfAbsent(phoneBlock, k -> new HashSet<>()).add(BlackoutOverlayTypes.STREET_PHONE);
}
```

This way the phone block gets scanned and cached just like any task block.

- [ ] **Step 3: Render constant overlay in CustomTaskBlockRendererMixin**

In `CustomTaskBlockRendererMixin.java`, in the survival mode branch, after checking `blockTypeId < 12` and `blockTypeId == 12`, we need to also check for constant overlay IDs and render them even when no active task matches. Actually, the issue is that the survival render path ONLY renders blocks matching the active task's blockTypeId. For phone blocks (type=90), we need them rendered regardless.

The cleanest approach: in the survival rendering loop, add a second pass for constant overlays. Or better yet, at the start of the survival branch, render constant blocks first.

Actually, looking at the code more carefully:

1. Survival path (lines 243-269): Gets `blockTypeId` from active task, then iterates all blocks in cache and renders only those matching `blockTypeId`.
2. Spectator path (lines 389-428): Renders ALL typeIds in cache.

For the phone to show in survival, I need to add a "constant overlay render" that runs regardless of active task. The best place is right after the main rendering loop in the survival path, or as a separate call at the end.

Let me add a `renderConstantOverlays` method and call it at the end of the survival render path:

After the `renderedCount` check at line 324 (the debug log), but before `}` that closes the survival branch:

```java
// 常量透视方块（不在任何任务中注册）
if (isBlackoutModeActive()) {
    renderConstantOverlays(renderContext);
}
```

And add the method:

```java
private static boolean isBlackoutModeActive() {
    var instance = Minecraft.getInstance();
    if (instance == null || instance.level == null) return false;
    return com.habitrain.core.api.GameModeRegistry.getActiveForLevel(instance.level)
            .map(m -> "habitrains:blackout".equals(m.getId()))
            .orElse(false);
}

private static void renderConstantOverlays(WorldRenderContext context) {
    var level = context.world();
    if (level == null) return;
    int rendered = 0;
    for (BlockPos pos : CustomTaskBlockCache.keySet()) {
        Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
        if (typeIds == null || !typeIds.contains(com.habitrain.core.game.blackout.BlackoutOverlayTypes.STREET_PHONE)) continue;
        renderCustomOverlay(context, pos, new java.awt.Color(0xFFD700, true), 5.0f);
        rendered++;
    }
    if (rendered > 0) {
        HabiTrainCore.LOGGER.debug("[CustomTaskBlockRendererMixin] rendered {} constant overlay blocks", rendered);
    }
}
```

Wait, but the `isBlackoutModeActive()` check using `GameModeRegistry.getActiveForLevel()` — does this exist? Let me check...

`GameModeRegistry` is in `com.habitrain.core.api.GameModeRegistry` and has `getActiveForLevel(ServerLevel)` but does it work on the client side? The client doesn't have a `ServerLevel`. I need to use the client's level. Let me check what patterns exist for client-side game mode detection.

Looking at the codebase, the `isGameRunning()` method uses `SREGameWorldComponent.KEY.get(instance.level)` which works on the client. But detecting specifically the blackout mode from the client side... let me check if there's a client-side equivalent.

Actually, looking at `BlackoutHudOverlay` — it's a client-side HUD that shows blackout timer info. It receives updates via `BlackoutTimerPayload`. So the client knows if blackout mode is active via the timer payload state. But for our purposes, if we're just checking `isGameRunning()` (which already filters out lobby), and the phone blocks are only in blackout maps, this is sufficient. The constant overlay rendering just needs to check `isGameRunning()` which is already in the spectate path.

Actually, looking more carefully at the code flow in the survival path:

1. It checks `isGameRunning()` at line 227 (return if false)
2. Gets active task + its blockTypeId
3. Renders only blocks matching that typeId

For the constant overlay, I should:
- After the survival main loop, add a separate render for type=90 blocks
- This should only happen during blackout games, which I can check by looking at the active game mode

On the client, game modes are registered in `GameModeRegistry`. But `getActiveForLevel` takes a `ServerLevel`. On the client, we can't check that. Instead, let me just check if the game is running AND the current level has any blocks with type=90 in the cache. If blocks of type=90 are in the cache, render them. The cache is only populated from `CustomTaskBlockPayload` which only comes from blackout mode's `MapScannerMixin`, so this is sufficient.

Wait, but the phone blocks get scanned via MapScannerMixin which is server-side. The `CustomTaskBlockCache` on the client gets populated via `CustomTaskBlockPayload.broadcastToAll`. So if the server has the phone blocks and broadcasts them, the client will have them in the cache regardless of active task. The problem is just that the renderer skips them because `blockTypeId` doesn't match the active task.

So the cleanest approach is: after the survival rendering loop, iterate the cache again and render any type=90 blocks.

Let me write the code:

After the survival rendering loop (after the closing `}` of `if (renderedCount > 0)` debug log block), add:

```java
// 渲染常量透视方块（非任务，不依赖 active task）
renderConstantOverlaysIfBlackout(renderContext);
```

And add two new methods:

```java
/**
 * 渲染常量透视方块（如 yuushya:street_phone）。
 * 这些方块不注册为任务，但通过 MapScannerMixin 扫描并广播到客户端。
 * 渲染不依赖 active task — 只要 game is running 且缓存中存在就渲染。
 */
private static void renderConstantOverlaysIfBlackout(WorldRenderContext context) {
    var level = context.world();
    if (level == null) return;

    // 只在 game running 时渲染（与生存/旁观模式公共前提一致）
    if (!isGameRunning()) return;

    // 避免旁观模式下重复渲染（旁观模式走 renderAllCustomTaskBlocks，其中包含 type=90 的方块）
    if (SREClient.isPlayerSpectatingOrCreative()) return;

    int rendered = 0;
    for (BlockPos pos : CustomTaskBlockCache.keySet()) {
        Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
        if (typeIds == null || !typeIds.contains(com.habitrain.core.game.blackout.BlackoutOverlayTypes.STREET_PHONE)) {
            continue;
        }
        // 金色描边，5.0f 线宽，方便与任务方块区分
        renderCustomOverlay(context, pos, new java.awt.Color(0xFFD700, true), 5.0f);
        rendered++;
    }
    if (rendered > 0) {
        HabiTrainCore.LOGGER.debug("[CustomTaskBlockRendererMixin] rendered {} constant overlay blocks", rendered);
    }
}
```

Now I also need to ensure `SREClient` is already imported in the file. Looking at the imports... yes, line 19: `import io.wifi.starrailexpress.client.SREClient;` — already imported.

Also need `GameModeRegistry` import... let me check if it's imported. Looking at the existing imports, it's not. But I removed the `isBlackoutModeActive()` approach in favor of just checking `isGameRunning() && !spectating`, so no new import needed.

Wait, `java.awt.Color` — is it imported? Line 46: `import java.awt.Color;` — yes.

- [ ] **Step 4: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutOverlayTypes.java \
       src/main/java/com/habitrain/core/game/sre/mixin/MapScannerMixin.java \
       src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java
git commit -m "feat: phone constant overlay — scan yuushya:street_phone, render type=90 blocks"
```

---

### Task 5: Generic Vote Infrastructure

**Files:**
- Create: `network/BlackoutVotePayload.java` (S2C)
- Create: `network/BlackoutVoteCastPayload.java` (C2S)
- Create: `client/gui/BlackoutVoteState.java` (client state)
- Create: `client/gui/BlackoutVoteScreen.java` (client GUI, extracted from sheriff screen)
- Modify: `HabiTrainCore.java` — register vote payloads, C2S receiver
- Modify: `HabiTrainCoreClient.java` — register S2C receiver
- Modify: `client/BlackoutKeyHandler.java` — V-key opens active vote
- Modify: `PayloadSenders.java` — add `sendVoteCast()`

**Interfaces:**
- Consumes: existing client networking patterns
- Produces: generic vote system ready for exile mode

- [ ] **Step 1: Create BlackoutVotePayload.java (S2C)**

`src/main/java/com/habitrain/core/network/BlackoutVotePayload.java`:

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record BlackoutVotePayload(
        String purpose,         // "EXILE" | future: "SHERIFF"
        boolean active,
        int remainingSeconds,
        int totalSeconds,
        int maxSelections,      // 1 for exile
        String title,
        String description,
        List<Entry> candidates
) implements CustomPacketPayload {

    public record Entry(UUID playerId, String playerName, int votes) {}

    public static final CustomPacketPayload.Type<BlackoutVotePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("vote"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutVotePayload> CODEC =
            StreamCodec.ofMember(BlackoutVotePayload::write, BlackoutVotePayload::new);

    public BlackoutVotePayload {
        candidates = List.copyOf(candidates);
    }

    private BlackoutVotePayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readUtf(128), buf.readUtf(256), readCandidates(buf));
    }

    private static List<Entry> readCandidates(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readUUID(), buf.readUtf(64), buf.readVarInt()));
        }
        return list;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(purpose, 32);
        buf.writeBoolean(active);
        buf.writeVarInt(remainingSeconds);
        buf.writeVarInt(totalSeconds);
        buf.writeVarInt(maxSelections);
        buf.writeUtf(title, 128);
        buf.writeUtf(description, 256);
        buf.writeVarInt(candidates.size());
        for (Entry e : candidates) {
            buf.writeUUID(e.playerId());
            buf.writeUtf(e.playerName(), 64);
            buf.writeVarInt(e.votes());
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToAll(net.minecraft.server.MinecraftServer server,
                                       String purpose, boolean active, int remainingSeconds,
                                       int totalSeconds, int maxSelections,
                                       String title, String description,
                                       List<Entry> candidates) {
        var payload = new BlackoutVotePayload(purpose, active, remainingSeconds,
                totalSeconds, maxSelections, title, description, candidates);
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }
}
```

- [ ] **Step 2: Create BlackoutVoteCastPayload.java (C2S)**

`src/main/java/com/habitrain/core/network/BlackoutVoteCastPayload.java`:

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record BlackoutVoteCastPayload(String purpose, UUID targetPlayerId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutVoteCastPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("vote_cast"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutVoteCastPayload> CODEC =
            StreamCodec.ofMember(BlackoutVoteCastPayload::write, BlackoutVoteCastPayload::new);

    private BlackoutVoteCastPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readUUID());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(purpose, 32);
        buf.writeUUID(targetPlayerId);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
```

- [ ] **Step 3: Create BlackoutVoteState.java (client)**

`src/main/java/com/habitrain/core/client/gui/BlackoutVoteState.java`:

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.network.BlackoutVotePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BlackoutVoteState {
    private static String purpose = "";
    private static boolean active = false;
    private static int remainingSeconds = 0;
    private static int totalSeconds = 15;
    private static int maxSelections = 1;
    private static String title = "";
    private static String description = "";
    private static List<BlackoutVotePayload.Entry> candidates = List.of();
    private static UUID selectedTargetId = null;

    private BlackoutVoteState() {}

    public static void update(BlackoutVotePayload payload) {
        purpose = payload.purpose();
        active = payload.active();
        remainingSeconds = payload.remainingSeconds();
        totalSeconds = payload.totalSeconds();
        maxSelections = payload.maxSelections();
        title = payload.title();
        description = payload.description();
        candidates = List.copyOf(payload.candidates());
        if (!active) {
            selectedTargetId = null;
        }
    }

    public static void clear() {
        active = false;
        remainingSeconds = 0;
        candidates = List.of();
        selectedTargetId = null;
    }

    public static boolean isActive() { return active; }
    public static int getRemainingSeconds() { return remainingSeconds; }
    public static int getTotalSeconds() { return totalSeconds; }
    public static String getTitle() { return title; }
    public static String getDescription() { return description; }
    public static String getPurpose() { return purpose; }
    public static List<BlackoutVotePayload.Entry> getCandidates() { return candidates; }
    public static UUID getSelectedTargetId() { return selectedTargetId; }
    public static boolean isSelected(UUID id) { return id.equals(selectedTargetId); }

    public static void toggleSelection(UUID targetId) {
        if (selectedTargetId != null && selectedTargetId.equals(targetId)) {
            selectedTargetId = null;
        } else {
            selectedTargetId = targetId;
        }
    }
}
```

- [ ] **Step 4: Create BlackoutVoteScreen.java (client)**

`src/main/java/com/habitrain/core/client/gui/BlackoutVoteScreen.java`:

This is adapted from `BlackoutSheriffVoteScreen` but uses `BlackoutVoteState` and supports generic purpose/title.

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.BlackoutVotePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

public class BlackoutVoteScreen extends Screen {
    private static final int PAD = 10;
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 220;
    private static final int ROW_H = 28;
    private static final int ROW_GAP = 4;
    private static final int SCROLLBAR_W = 4;

    private final Screen parent;
    private double scrollOffset = 0;

    public BlackoutVoteScreen(Screen parent) {
        super(Component.literal(BlackoutVoteState.getTitle()));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("关闭"),
                btn -> onClose())
                .bounds(width / 2 - 40, height - 32, 80, 20)
                .build());
    }

    @Override
    public void tick() {
        if (!BlackoutVoteState.isActive()) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (width - PANEL_W) / 2;
        int panelY = 28;
        int listX = panelX + PAD;
        int listY = panelY + 52;
        int listW = PANEL_W - PAD * 2;
        int listH = PANEL_H - 84;

        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xE01A1520, 0xE0281F2E);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, 0xFF6B8CB3);
        g.fill(panelX + 1, panelY + 1, panelX + PANEL_W - 1, panelY + 2, 0x44D8E7FF);

        Font font = this.font;
        g.drawCenteredString(font, this.title, width / 2, panelY + 10, 0xF5F7FB);
        g.drawCenteredString(font, Component.literal(BlackoutVoteState.getDescription()),
                width / 2, panelY + 24, 0xB9C7D9);

        String timer = BlackoutVoteState.isActive()
                ? "剩余时间: " + BlackoutVoteState.getRemainingSeconds() + "s"
                : "投票已结束";
        g.drawCenteredString(font, Component.literal(timer), width / 2, panelY + 36, 0xFFE6B566);

        List<BlackoutVotePayload.Entry> candidates = BlackoutVoteState.getCandidates();
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < candidates.size(); i++) {
            var entry = candidates.get(i);
            int rowY = listY + i * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (rowY + ROW_H < listY || rowY > listY + listH) continue;

            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean selected = BlackoutVoteState.isSelected(entry.playerId());
            renderRow(g, entry, listX, rowY, listW, ROW_H, hovered, selected);
        }
        g.disableScissor();

        int totalHeight = candidates.isEmpty() ? 0 : candidates.size() * (ROW_H + ROW_GAP) - ROW_GAP;
        int maxScroll = Math.max(0, totalHeight - listH);
        if (maxScroll > 0) {
            int barH = Math.max(12, (int) ((float) listH * listH / totalHeight));
            int barY = listY + (int) ((listH - barH) * (scrollOffset / maxScroll));
            g.fill(listX + listW + 2, listY, listX + listW + 2 + SCROLLBAR_W, listY + listH, 0x332B3D55);
            g.fill(listX + listW + 2, barY, listX + listW + 2 + SCROLLBAR_W, barY + barH, 0xFF7E98B8);
        }
    }

    private void renderRow(GuiGraphics g, BlackoutVotePayload.Entry entry, int x, int y, int w, int h,
                           boolean hovered, boolean selected) {
        int border = selected ? 0xFFE6B566 : (hovered ? 0xFF8CA7C7 : 0xFF4B5F78);
        int bg = selected ? 0xFF2A2220 : (hovered ? 0xFF212A36 : 0xFF17202A);
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);

        g.drawString(font, Component.literal(entry.playerName()),
                x + 8, y + 9, 0xFFFFFF, false);
        g.drawString(font, Component.literal("票数: " + entry.votes()),
                x + w - 72, y + 9, 0xB9C7D9, false);

        if (selected) {
            g.fill(x + w - 6, y + 4, x + w - 2, y + h - 4, 0xFFE6B566);
            g.drawCenteredString(font, Component.literal("✓"),
                    x + w - 14, y + 9, 0xFFE6B566);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !BlackoutVoteState.isActive()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int panelX = (width - PANEL_W) / 2;
        int panelY = 28;
        int listX = panelX + PAD;
        int listY = panelY + 52;
        int listW = PANEL_W - PAD * 2;

        List<BlackoutVotePayload.Entry> candidates = BlackoutVoteState.getCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            int rowY = listY + i * (ROW_H + ROW_GAP) - (int) scrollOffset;
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                var entry = candidates.get(i);
                boolean wasSelected = BlackoutVoteState.isSelected(entry.playerId());
                BlackoutVoteState.toggleSelection(entry.playerId());
                if (BlackoutVoteState.isSelected(entry.playerId())) {
                    PayloadSenders.sendVoteCast(BlackoutVoteState.getPurpose(), entry.playerId());
                } else if (wasSelected) {
                    // 取消投票：发送 null UUID 表示弃票
                    PayloadSenders.sendVoteCast(BlackoutVoteState.getPurpose(), null);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int panelX = (width - PANEL_W) / 2;
        int panelY = 28;
        int listX = panelX + PAD;
        int listY = panelY + 52;
        int listW = PANEL_W - PAD * 2;
        int listH = PANEL_H - 84;

        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            List<BlackoutVotePayload.Entry> candidates = BlackoutVoteState.getCandidates();
            int totalHeight = candidates.isEmpty() ? 0 : candidates.size() * (ROW_H + ROW_GAP) - ROW_GAP;
            int maxScroll = Math.max(0, totalHeight - listH);
            scrollOffset = Mth.clamp(scrollOffset - scrollY * (ROW_H + ROW_GAP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
```

- [ ] **Step 5: Add sendVoteCast() to PayloadSenders.java**

```java
/** 从客户端发送投票。purpose 指定投票类型，targetPlayerId 为投票目标（null=弃票）。 */
public static void sendVoteCast(String purpose, UUID targetPlayerId) {
    if (Minecraft.getInstance().getConnection() == null) return;
    ClientPlayNetworking.send(new com.habitrain.core.network.BlackoutVoteCastPayload(purpose, targetPlayerId));
}
```

- [ ] **Step 6: Update BlackoutKeyHandler.java**

Replace the entire class to use the generic vote state:

```java
package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutVoteScreen;
import com.habitrain.core.client.gui.BlackoutVoteState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class BlackoutKeyHandler {
    private static boolean registered = false;
    private static KeyMapping openVoteKey;

    public static KeyMapping getOpenVoteKey() {
        return openVoteKey;
    }

    public static void register() {
        if (registered) return;
        registered = true;

        openVoteKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.habitrain_core.open_vote",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.habitrain_core"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openVoteKey.consumeClick()) {
                openVote(client);
            }
        });
    }

    private static void openVote(Minecraft client) {
        if (client.player == null) return;

        if (!BlackoutVoteState.isActive()) {
            com.habitrain.core.client.util.ClientSubtitleNotifier.sendTop(
                    Component.literal("§e投票"),
                    Component.literal("§e当前没有进行中的投票。"),
                    60);
            return;
        }

        if (client.screen instanceof BlackoutVoteScreen) return;
        client.setScreen(new BlackoutVoteScreen(client.screen));
    }
}
```

- [ ] **Step 7: Register vote payloads and C2S receiver in HabiTrainCore.java**

In `onInitialize()`, after `BlackoutHirePolicePayload.register();`:

```java
BlackoutVotePayload.register();
BlackoutVoteCastPayload.register();
```

After the hire police C2S receiver, add:

```java
// C2S 放逐投票接收器
ServerPlayNetworking.registerGlobalReceiver(BlackoutVoteCastPayload.TYPE, (payload, context) -> {
    context.server().execute(() -> {
        ServerPlayer voter = context.player();
        if (voter == null) return;
        ServerLevel level = voter.serverLevel();
        if (level == null) return;
        if ("EXILE".equals(payload.purpose())) {
            BlackoutExileVoteManager.castVote(level, voter.getUUID(), payload.targetPlayerId());
        }
    });
});
```

Note: This C2S receiver references `BlackoutExileVoteManager` which will be created in Task 6. For now, the file won't compile because the class doesn't exist yet.

To keep this task independent, let me put a TODO placeholder or add the C2S handler in Task 6 instead. Actually, I'll move the C2S registration to Task 6 when `BlackoutExileVoteManager` exists. For this task, just register the payload types.

So for Task 5, only register the payloads:

```java
BlackoutVotePayload.register();
BlackoutVoteCastPayload.register();
```

And the C2S handler goes into Task 6.

- [ ] **Step 8: Register vote S2C handler in HabiTrainCoreClient.java**

```java
ClientPlayNetworking.registerGlobalReceiver(com.habitrain.core.network.BlackoutVotePayload.TYPE, (payload, ctx) -> {
    BlackoutVoteState.update(payload);
    if (!payload.active() && ctx.client().screen instanceof BlackoutVoteScreen) {
        ctx.client().setScreen(null);
    }
    if (payload.active() && "EXILE".equals(payload.purpose())) {
        // 放逐投票自动打开 GUI（如果当前不在其他 screen）
        if (!(ctx.client().screen instanceof BlackoutVoteScreen) && ctx.client().screen == null) {
            ctx.client().setScreen(new BlackoutVoteScreen(null));
        }
    }
});
```

Also add the needed imports at the top of `HabiTrainCoreClient.java`:

```java
import com.habitrain.core.client.gui.BlackoutVoteScreen;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.habitrain.core.network.BlackoutVotePayload;
```

- [ ] **Step 9: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (BlackoutExileVoteManager castVote is referenced in a comment-only way — the C2S handler will be added in Task 6)

Actually wait — I added the C2S handler in step 7 that references `BlackoutExileVoteManager`. Since I decided to defer that to Task 6, step 7 should only do the `register()` calls. Let me make sure step 7 is correct.

The C2S receiver for `BlackoutVoteCastPayload.TYPE` will be added in Task 6 along with `BlackoutExileVoteManager`. So step 7 only does the payload type registration.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/habitrain/core/network/BlackoutVotePayload.java \
       src/main/java/com/habitrain/core/network/BlackoutVoteCastPayload.java \
       src/main/java/com/habitrain/core/client/gui/BlackoutVoteState.java \
       src/main/java/com/habitrain/core/client/gui/BlackoutVoteScreen.java \
       src/main/java/com/habitrain/core/HabiTrainCore.java \
       src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java \
       src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java \
       src/main/java/com/habitrain/core/client/network/PayloadSenders.java
git commit -m "feat: generic vote infrastructure — payloads, state, screen, key bindings"
```

---

### Task 6: Exile Vote Manager + Horn Handler

**Files:**
- Create: `game/blackout/BlackoutExileVoteManager.java`
- Create: `game/blackout/BlackoutHornVoteHandler.java`
- Modify: `BlackoutMode.java` — lifecycle integration
- Modify: `BlackoutTickCoordinator.java` — exile vote tick
- Modify: `HabiTrainCore.java` — register horn handler + C2S vote receiver

**Interfaces:**
- Consumes: `BlackoutVotePayload.broadcastToAll()`, `BlackoutRoleManager.{eliminate,isAlive,getAllAlive}`, `GameUtils.killPlayer()`, `SubtitleNotifier.sendTop()`
- Produces: complete exile vote machine + horn interaction

- [ ] **Step 1: Create BlackoutExileVoteManager.java**

`src/main/java/com/habitrain/core/game/blackout/BlackoutExileVoteManager.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.network.BlackoutVotePayload;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 放逐投票管理器。
 *
 * 按 dimension 隔离，每次最多一个 active 投票。
 * 候选人 = 当前对局内存活玩家（含发起者）。
 * 每人 1 票，不可改票。
 */
public final class BlackoutExileVoteManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutExileVoteManager");
    private static final int VOTE_DURATION_SECONDS = 15;
    private static final ResourceLocation EXILE_DEATH_REASON =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "exile_vote");

    private static final ConcurrentMap<ResourceKey<Level>, VoteState> STATES = new ConcurrentHashMap<>();

    private BlackoutExileVoteManager() {}

    private static final class VoteState {
        boolean active = false;
        int remainingSeconds = 0;
        final List<UUID> candidateOrder = new ArrayList<>();
        final Map<UUID, UUID> votesByVoter = new HashMap<>(); // voter -> target
        UUID initiatorId = null;
    }

    private static VoteState getOrCreate(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new VoteState());
    }

    /** 对局重置/清理 */
    public static void reset(ServerLevel level) {
        STATES.remove(level.dimension());
    }

    /** 当前是否有 active 的放逐投票 */
    public static boolean isVoteActive(ServerLevel level) {
        VoteState state = STATES.get(level.dimension());
        return state != null && state.active;
    }

    /**
     * 发起放逐投票。
     * 调用前需校验：金钱、对局状态、无 active 投票。
     */
    public static void startVote(ServerLevel level, ServerPlayer initiator) {
        VoteState state = getOrCreate(level);
        state.active = true;
        state.remainingSeconds = VOTE_DURATION_SECONDS;
        state.votesByVoter.clear();
        state.candidateOrder.clear();
        state.initiatorId = initiator.getUUID();

        // 候选人 = 所有存活玩家
        for (UUID id : BlackoutRoleManager.getAllAlive(level)) {
            state.candidateOrder.add(id);
        }

        if (state.candidateOrder.isEmpty()) {
            state.active = false;
            broadcastResult(level, "§e当前没有存活玩家，无法发起放逐投票。");
            return;
        }

        LOGGER.info("[ExileVote] {} started exile vote with {} candidates",
                initiator.getName().getString(), state.candidateOrder.size());

        broadcastState(level);
        broadcastResult(level, "§e放逐投票已开启！按 V 键打开投票页面。");
    }

    /** 每 tick 处理（每秒调用一次） */
    public static void tickSecond(ServerLevel level) {
        VoteState state = STATES.get(level.dimension());
        if (state == null || !state.active) return;

        state.remainingSeconds--;
        if (state.remainingSeconds <= 0) {
            resolve(level, state);
        } else {
            broadcastState(level);
        }
    }

    /** 投票 */
    public static void castVote(ServerLevel level, UUID voterId, UUID targetId) {
        VoteState state = STATES.get(level.dimension());
        if (state == null || !state.active) return;

        // 校验投票者存活且在候选人中
        if (!state.candidateOrder.contains(voterId)) return;
        if (!BlackoutRoleManager.isAlive(level, voterId)) return;

        if (targetId != null && !state.candidateOrder.contains(targetId)) return;

        if (targetId != null) {
            state.votesByVoter.put(voterId, targetId);
        } else {
            state.votesByVoter.remove(voterId);
        }

        broadcastState(level);
    }

    /** 结算 */
    private static void resolve(ServerLevel level, VoteState state) {
        state.active = false;

        // 统计票数
        Map<UUID, Integer> voteCounts = new HashMap<>();
        for (UUID candidateId : state.candidateOrder) {
            voteCounts.put(candidateId, 0);
        }
        for (Map.Entry<UUID, UUID> entry : state.votesByVoter.entrySet()) {
            UUID target = entry.getValue();
            voteCounts.merge(target, 1, Integer::sum);
        }

        int totalVotes = state.votesByVoter.size();

        if (totalVotes == 0) {
            broadcastResult(level, "§e无人投票，本轮无人被放逐");
            return;
        }

        // 找最高票
        int maxVotes = voteCounts.values().stream().max(Integer::compareTo).orElse(0);
        List<UUID> topCandidates = new ArrayList<>();
        for (var entry : voteCounts.entrySet()) {
            if (entry.getValue() == maxVotes) {
                topCandidates.add(entry.getKey());
            }
        }

        UUID exiledId;
        if (topCandidates.size() == 1) {
            exiledId = topCandidates.get(0);
        } else {
            // 平票随机
            Random random = new Random(level.getRandom().nextLong());
            exiledId = topCandidates.get(random.nextInt(topCandidates.size()));
        }

        ServerPlayer exiled = level.getServer().getPlayerList().getPlayer(exiledId);
        String exiledName = exiled != null ? exiled.getName().getString() : exiledId.toString();

        // 执行放逐
        GameUtils.killPlayer(exiled, true, null, EXILE_DEATH_REASON);
        BlackoutRoleManager.eliminate(level, exiledId);

        broadcastResult(level, "§e投票结束，§b" + exiledName + " §e被放逐");

        LOGGER.info("[ExileVote] {} exiled ({}/{} votes)", exiledName, maxVotes, totalVotes);

        // 通知胜负检查
        var mode = findBlackoutMode(level);
        if (mode != null) {
            mode.setPendingEndMessage("放逐后胜负条件变化");
        }
    }

    /** 广播投票状态到所有客户端 */
    private static void broadcastState(ServerLevel level) {
        VoteState state = STATES.get(level.dimension());
        if (state == null) return;

        List<BlackoutVotePayload.Entry> entries = buildEntryList(level, state);
        BlackoutVotePayload.broadcastToAll(
                level.getServer(),
                "EXILE",
                state.active,
                state.remainingSeconds,
                VOTE_DURATION_SECONDS,
                1,
                "放逐投票",
                "选择一名玩家放逐",
                entries
        );
    }

    private static List<BlackoutVotePayload.Entry> buildEntryList(ServerLevel level, VoteState state) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (UUID candidateId : state.candidateOrder) {
            if (BlackoutRoleManager.isAlive(level, candidateId)) {
                counts.put(candidateId, 0);
            }
        }
        for (UUID target : state.votesByVoter.values()) {
            counts.merge(target, 1, Integer::sum);
        }

        Map<UUID, String> nameCache = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            nameCache.put(player.getUUID(), player.getName().getString());
        }

        List<BlackoutVotePayload.Entry> entries = new ArrayList<>();
        for (UUID candidateId : state.candidateOrder) {
            if (!counts.containsKey(candidateId)) continue;
            String name = nameCache.getOrDefault(candidateId, candidateId.toString());
            entries.add(new BlackoutVotePayload.Entry(candidateId, name, counts.get(candidateId)));
        }
        return entries;
    }

    /** 全图顶部提示 */
    private static void broadcastResult(ServerLevel level, String message) {
        Component component = Component.literal(message);
        for (ServerPlayer player : level.players()) {
            SubtitleNotifier.sendTop(player, Component.empty(), component, 80);
        }
    }

    /** 从 GameModeRegistry 查找当前 BlackoutMode */
    private static com.habitrain.core.game.blackout.BlackoutMode findBlackoutMode(ServerLevel level) {
        var mode = com.habitrain.core.api.GameModeRegistry.getActiveForLevel(level);
        if (mode.isPresent() && mode.get() instanceof com.habitrain.core.game.blackout.BlackoutMode bm) {
            return bm;
        }
        return null;
    }
}
```

- [ ] **Step 2: Create BlackoutHornVoteHandler.java**

`src/main/java/com/habitrain/core/game/blackout/BlackoutHornVoteHandler.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监听 trainmurdermystery:horn 方块右键，实现二次拉动放逐投票。
 *
 * 第一次拉动（免费）：显示 MC 原生标题 "再次拉动发动投票"，10 秒有效。
 * 第二次拉动（扣 500 金）：发起放逐投票。
 */
public final class BlackoutHornVoteHandler {
    private static final ResourceLocation HORN_ID = ResourceLocation.parse("trainmurdermystery:horn");
    private static final int CONFIRM_WINDOW_SECONDS = 10;
    private static final int EXILE_COST = 500;

    // player UUID -> tick when confirmation expires
    private static final Map<UUID, Long> confirmWindows = new ConcurrentHashMap<>();

    private static Block cachedHorn = null;

    private static Block getHornBlock() {
        if (cachedHorn == null || cachedHorn == Blocks.AIR) {
            cachedHorn = BuiltInRegistries.BLOCK.get(HORN_ID);
        }
        return cachedHorn;
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide) return InteractionResult.PASS;
            if (!hand.equals(net.minecraft.world.InteractionHand.MAIN_HAND)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            Block block = level.getBlockState(pos).getBlock();

            Block hornBlock = getHornBlock();
            if (hornBlock == null || hornBlock == Blocks.AIR) return InteractionResult.PASS;
            if (!block.equals(hornBlock)) return InteractionResult.PASS;

            if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

            // 停电模式对局检查
            var gameMode = GameModeRegistry.getActiveForLevel(serverLevel);
            if (gameMode.isEmpty() || !"habitrains:blackout".equals(gameMode.get().getId())) {
                return InteractionResult.PASS;
            }

            // 发起者必须存活
            if (!BlackoutRoleManager.isAlive(serverLevel, serverPlayer.getUUID())) {
                return InteractionResult.PASS;
            }

            UUID playerId = serverPlayer.getUUID();
            long now = serverLevel.getGameTime();
            Long expiry = confirmWindows.get(playerId);

            if (expiry != null && now < expiry) {
                // 第二次拉动
                confirmWindows.remove(playerId);

                // 不能有正在进行的放逐投票
                if (BlackoutExileVoteManager.isVoteActive(serverLevel)) {
                    SubtitleNotifier.sendTop(serverPlayer, Component.empty(),
                            Component.literal("§c当前已有投票正在进行"), 60);
                    return InteractionResult.SUCCESS;
                }

                // 扣金币校验
                var shop = SREPlayerShopComponent.KEY.get(serverPlayer);
                if (shop == null || shop.balance < EXILE_COST) {
                    SubtitleNotifier.sendTop(serverPlayer, Component.empty(),
                            Component.literal("§c发动投票需要 " + EXILE_COST + " 金币"), 60);
                    return InteractionResult.SUCCESS;
                }

                shop.addToBalance(-EXILE_COST);
                BlackoutExileVoteManager.startVote(serverLevel, serverPlayer);

                HabiTrainCore.LOGGER.info("[HornVote] {} initiated exile vote (cost: {})",
                        serverPlayer.getName().getString(), EXILE_COST);
            } else {
                // 第一次拉动
                confirmWindows.put(playerId, now + CONFIRM_WINDOW_SECONDS * 20L);
                serverPlayer.sendSystemMessage(Component.literal("§e再次拉动发动投票"), true);
            }

            return InteractionResult.SUCCESS;
        });

        HabiTrainCore.LOGGER.info("[HornVoteHandler] registered for trainmurdermystery:horn");
    }

    /** 玩家淘汰/死亡时清除确认窗口 */
    public static void onPlayerRemoved(UUID playerId) {
        confirmWindows.remove(playerId);
    }

    private BlackoutHornVoteHandler() {}
}
```

- [ ] **Step 3: Integrate into BlackoutMode.java lifecycle**

In `BlackoutMode.java`, update `onPreStart()` to add reset calls:

After `BlackoutTimerSystem.init(...)` and before `syncManager.onPreStart()`:

```java
BlackoutPoliceHireService.reset(level);
BlackoutExileVoteManager.reset(level);
```

And update `onCleanup()` if it doesn't exist or in the SERVER_STOPPING handler in HabiTrainCore — actually, let me add the cleanup to `SREGameModeBase.onCleanup()` or the `SERVER_STOPPING` handler in `HabiTrainCore`.

Actually, the spec says to add to `BlackoutMode.onPreStart/onCleanup`. Let me check if BlackoutMode has an `onCleanup` method... Looking at the file I read earlier, it only has `onPreStart`, `onStart`, and `onEnd` (inherited from interface). Let me check if there's an `onCleanup` in the GameMode interface.

I'll add the cleanup to `HabiTrainCore`'s `SERVER_STOPPING` handler, which already cleans up other managers. But also, the per-level cleanup needs to happen when the mode stops. Let me check the existing cleanup flow...

Currently, `BlackoutMode.onPreStart` calls `BlackoutSheriffVoteManager.reset(level)` and `BlackoutTimerSystem.init(...)`. The SERVER_STOPPING handler in HabiTrainCore calls `BlackoutSheriffVoteManager.reset(level)` and `BlackoutShopService.resetRound(level)`.

For the new services, I'll add cleanup/reset in:
1. `BlackoutMode.onPreStart()`: add `BlackoutPoliceHireService.reset(level)` and `BlackoutExileVoteManager.reset(level)` — these prepare for a new game
2. `HabiTrainCore.SERVER_STOPPING`: add `BlackoutPoliceHireService.cleanup(level)` and `BlackoutExileVoteManager.reset(level)` — these clean up when server stops

Actually, `BlackoutPoliceHireService.reset()` already does the full initialization. Let me use `reset` for both purposes.

In `BlackoutMode.onPreStart()`, add after `BlackoutTimerSystem.init(...)`:

```java
BlackoutPoliceHireService.reset(level);
BlackoutExileVoteManager.reset(level);
```

In `HabiTrainCore`, the SERVER_STOPPING handler already has cleanup. Add after `BlackoutShopService.resetRound(level)`:

```java
BlackoutPoliceHireService.cleanup(level);
BlackoutExileVoteManager.reset(level);
```

But we need a `cleanup` method in `BlackoutPoliceHireService`. Let me check... yes, I already added `cleanup()` in Task 2.

Wait, in Task 2's `BlackoutPoliceHireService`:
- `reset(ServerLevel)` — creates new state with gameStartTick
- `cleanup(ServerLevel)` — removes state

Good. For `BlackoutExileVoteManager`:
- `reset(ServerLevel)` — removes state via `STATES.remove`

So in `SERVER_STOPPING`:
```java
BlackoutPoliceHireService.cleanup(level);
BlackoutExileVoteManager.reset(level);
```

- [ ] **Step 4: Add exile vote tick to BlackoutTickCoordinator.java**

In the `tickSecond()` section (around line 65, where sheriff vote tick was removed), add:

```java
BlackoutExileVoteManager.tickSecond(level);
```

- [ ] **Step 5: Register horn handler and C2S vote receiver in HabiTrainCore.java**

In `onInitialize()`, after `BlackoutPhoneHandler.register();`:

```java
BlackoutHornVoteHandler.register();
```

After the sheriff vote C2S receiver block, add:

```java
// C2S 通用投票接收器（用于放逐投票等）
ServerPlayNetworking.registerGlobalReceiver(BlackoutVoteCastPayload.TYPE, (payload, context) -> {
    context.server().execute(() -> {
        ServerPlayer voter = context.player();
        if (voter == null) return;
        ServerLevel level = voter.serverLevel();
        if (level == null) return;
        if ("EXILE".equals(payload.purpose())) {
            BlackoutExileVoteManager.castVote(level, voter.getUUID(), payload.targetPlayerId());
        }
    });
});
```

- [ ] **Step 6: Handle player removal for horn confirmation**

In `BlackoutRoleManager.eliminate()`, which is already called by the existing `eliminate` at line 73-79. I need to add a call to `BlackoutHornVoteHandler.onPlayerRemoved(playerId)`.

Add after line 78 (where `BlackoutSheriffVoteManager.onPlayerRemoved(level, playerId)` is called):

```java
BlackoutHornVoteHandler.onPlayerRemoved(playerId);
```

- [ ] **Step 7: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutExileVoteManager.java \
       src/main/java/com/habitrain/core/game/blackout/BlackoutHornVoteHandler.java \
       src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java \
       src/main/java/com/habitrain/core/game/blackout/BlackoutTickCoordinator.java \
       src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java \
       src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "feat: exile vote manager, horn handler, lifecycle integration"
```

---

### Task 7: Exile Vote Localization + Victory Check

**Files:**
- Modify: `assets/habitrain_core/lang/zh_cn.json` — add death_reason key
- Modify: `assets/habitrain_core/lang/en_us.json` — add death_reason key
- Modify: `game/blackout/BlackoutVictoryChecker.java` — ensure exile votes trigger victory check

**Interfaces:**
- Consumes: existing victory checker pattern
- Produces: death_reason shown in SRE replay, victory check runs after exile

- [ ] **Step 1: Add death_reason to zh_cn.json**

Add to `assets/habitrain_core/lang/zh_cn.json`:

```json
  "death_reason.habitrain_core.exile_vote": "被放逐"
```

- [ ] **Step 2: Add death_reason to en_us.json**

Add to `assets/habitrain_core/lang/en_us.json`:

```json
  "death_reason.habitrain_core.exile_vote": "Exiled"
```

- [ ] **Step 3: Verify victory check integration**

The `BlackoutExileVoteManager.resolve()` already calls `BlackoutRoleManager.eliminate()` which removes the player from the living state. The existing `BlackoutVictoryChecker` runs every second via `BlackoutTickCoordinator.tick()` → `victoryChecker.tickSecond(level)`. Since `resolve()` calls `eliminate()`, the next `tickSecond` call will detect the changed faction counts and trigger victory if needed.

No additional code changes needed — the existing tick-based victory check handles it.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/assets/habitrain_core/lang/zh_cn.json \
       src/main/resources/assets/habitrain_core/lang/en_us.json
git commit -m "feat: exile vote death_reason localization"
```

---

### Task 8: Weather Controller (Rain on Low Players)

**Files:**
- Create: `game/sre/SREWeatherController.java`
- Modify: `ModTickHandler.java` — tick weather controller

**Interfaces:**
- Consumes: `BlackoutRoleManager.getAllAlive()`, `SREGameWorldComponent.KEY.get()` for game running check
- Produces: rain in overworld when <8 players during active game

- [ ] **Step 1: Create SREWeatherController.java**

`src/main/java/com/habitrain/core/game/sre/SREWeatherController.java`:

```java
package com.habitrain.core.game.sre;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 对局内活跃人数不足 8 人时自动下雨的控制器。
 *
 * 只影响主世界，只跟踪由本机制触发的雨。
 * 大厅阶段不触发（SRE game 未运行时不生效）。
 */
public final class SREWeatherController {
    private static boolean forcedRainByLowPlayers = false;
    private static int tickCounter = 0;

    private static final int CHECK_INTERVAL = 20; // 每 20 tick 检查一次
    private static final int MIN_PLAYERS = 8;
    private static final int RAIN_DURATION_TICKS = 20 * 60; // 1 分钟降雨
    private static final int CLEAR_DURATION_TICKS = 20 * 60; // 1 分钟晴天

    private SREWeatherController() {}

    /**
     * 每秒调用一次（每 20 tick）。
     * 对局内活跃人数 < 8 → 下雨；≥ 8 → 恢复晴天。
     * 只操作主世界。
     */
    public static void tick(ServerLevel overworld) {
        if (overworld == null || !overworld.dimension().equals(Level.OVERWORLD)) return;

        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

        // 检查是否有 SRE 对局在运行
        var sreGame = SREGameWorldComponent.KEY.get(overworld);
        boolean gameRunning = sreGame != null && sreGame.isRunning();

        if (!gameRunning) {
            // 对局结束 → 如果之前是本机制触发下雨，恢复晴天
            if (forcedRainByLowPlayers) {
                overworld.setWeatherParameters(CLEAR_DURATION_TICKS, 0, false, false);
                forcedRainByLowPlayers = false;
            }
            return;
        }

        // 计算活跃人数
        int aliveCount = BlackoutRoleManager.getAllAlive(overworld).size();

        if (aliveCount < MIN_PLAYERS) {
            if (!overworld.isRaining()) {
                overworld.setWeatherParameters(0, RAIN_DURATION_TICKS, true, false);
                forcedRainByLowPlayers = true;
            }
        } else {
            if (forcedRainByLowPlayers) {
                overworld.setWeatherParameters(CLEAR_DURATION_TICKS, 0, false, false);
                forcedRainByLowPlayers = false;
            }
        }
    }
}
```

- [ ] **Step 2: Add tick to ModTickHandler.java**

In `ModTickHandler.tickMoreMods()`, before the return check or at the end:

```java
// 人数不足 8 人下雨（只处理主世界）
for (ServerLevel world : server.getAllLevels()) {
    if (world.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
        com.habitrain.core.game.sre.SREWeatherController.tick(world);
    }
}
```

Actually, this should go in the `tickMoreMods` method AFTER the `if (!hasActiveGame) return;` check but BEFORE the player loop, or even outside the game check entirely since it handles its own game check internally.

Let me add it at the end of `tickMoreMods()`:

```java
// 人数不足 8 人下雨（包含大厅→对局→对局结束全覆盖）
for (ServerLevel world : server.getAllLevels()) {
    if (world.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
        SREWeatherController.tick(world);
    }
}
```

Add the import at the top:
```java
import com.habitrain.core.game.sre.SREWeatherController;
```

- [ ] **Step 3: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/SREWeatherController.java \
       src/main/java/com/habitrain/core/ModTickHandler.java
git commit -m "feat: rain when <8 players during active game"
```

---

### Task 9: Voice Chat Lobby Group Retry Fix

**Files:**
- Modify: `game/sre/SREGameModeBase.java` — add queueLobbyGroupJoin, tryAddPlayerToLobbyGroup, fix processPendingVoiceJoins, fix processGameEndGroupJoin
- Modify: `HabiTrainCore.java` — modify JOIN event logic

**Interfaces:**
- Consumes: existing `TrainVoicePlugin` API, existing `SREGameWorldComponent`
- Produces: reliable lobby group joining with retry, no game-join interference

- [ ] **Step 1: Add helper methods to SREGameModeBase.java**

Add before the existing `addPlayerToLobbyGroup` method (around line 113):

```java
/**
 * 玩家加入大厅语音群组的重试队列。
 * 当玩家加入世界时无活跃游戏对局，将其加入队列等待 voicechat 连接就绪。
 */
public static void queueLobbyGroupJoin(MinecraftServer server, UUID playerUUID) {
    pendingVoiceJoins.put(playerUUID, MAX_VOICE_JOIN_RETRIES);
    LOGGER.info("[VoiceGroup] queued {} for lobby group join", playerUUID);
}

/**
 * 尝试将玩家加入大厅语音群组。
 * @return true 表示成功加入或不需要加入（不在队列中），false 表示需要重试
 */
private static boolean tryAddPlayerToLobbyGroup(MinecraftServer server, UUID playerUUID) {
    if (TrainVoicePlugin.isVoiceChatMissing()) return false;
    if (TrainVoicePlugin.SERVER_API == null) return false;

    VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
    VoicechatConnection connection = api.getConnectionOf(playerUUID);
    if (connection == null) return false;

    try {
        if (LOBBY_GROUP == null) {
            LOBBY_GROUP = api.groupBuilder()
                    .setId(LOBBY_GROUP_ID)
                    .setName("LobbyChat")
                    .setPersistent(true)
                    .setType(Group.Type.OPEN)
                    .setHidden(false)
                    .build();
        }
        connection.setGroup(LOBBY_GROUP);
        LOGGER.info("[VoiceGroup] successfully added {} to lobby group", playerUUID);
        return true;
    } catch (Exception e) {
        LOGGER.error("[VoiceGroup] failed to set group for player {}", playerUUID, e);
        return false;
    }
}
```

- [ ] **Step 2: Add isAnySreGameRunning helper**

```java
/**
 * 检查服务器上当前是否有任何 SRE 对局正在运行。
 * 用于 JOIN 事件判断是否应将玩家加入队列。
 */
public static boolean isAnySreGameRunning(MinecraftServer server) {
    for (ServerLevel level : server.getAllLevels()) {
        try {
            var gameWorld = SREGameWorldComponent.KEY.get(level);
            if (gameWorld != null && gameWorld.isRunning()) {
                return true;
            }
        } catch (Exception ignored) {}
    }
    return false;
}
```

- [ ] **Step 3: Fix processPendingVoiceJoins**

Replace the existing method (lines 136-150) with:

```java
public static void processPendingVoiceJoins(MinecraftServer server) {
    if (pendingVoiceJoins.isEmpty()) return;
    Iterator<Map.Entry<UUID, Integer>> it = pendingVoiceJoins.entrySet().iterator();
    while (it.hasNext()) {
        Map.Entry<UUID, Integer> entry = it.next();
        UUID playerId = entry.getKey();

        // 玩家离线 → 移除
        if (server.getPlayerList().getPlayer(playerId) == null) {
            it.remove();
            LOGGER.info("[VoiceGroup] removed {} from pending queue (offline)", playerId);
            continue;
        }

        // 重试次数耗尽 → 移除并记录日志
        if (entry.getValue() <= 0) {
            it.remove();
            LOGGER.warn("[VoiceGroup] removed {} from pending queue (retries exhausted)", playerId);
            continue;
        }

        // 尝试加入
        if (tryAddPlayerToLobbyGroup(server, playerId)) {
            it.remove();
        } else {
            entry.setValue(entry.getValue() - 1);
        }
    }
}
```

- [ ] **Step 4: Fix processGameEndGroupJoin**

Replace the existing method (lines 152-158) with:

```java
public static void processGameEndGroupJoin(MinecraftServer server) {
    if (!pendingGameEndGroupJoin) return;
    pendingGameEndGroupJoin = false;

    // 等待对局完全结束（没有运行中的 SRE 游戏）
    if (isAnySreGameRunning(server)) {
        pendingGameEndGroupJoin = true; // 下一 tick 再试
        return;
    }

    // 惰性入队：pendingVoiceJoins 为空时再入队所有人
    if (pendingVoiceJoins.isEmpty()) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            queueLobbyGroupJoin(server, player.getUUID());
        }
        LOGGER.info("[VoiceGroup] queued all online players for lobby group join after game end");
    }
}
```

- [ ] **Step 5: Update JOIN event in HabiTrainCore.java**

Replace the existing JOIN voice logic (lines 208-221) with:

```java
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    ServerPlayer player = handler.getPlayer();
    try {
        // 如果当前没有 SRE 对局运行 → 入队等待加入大厅语音群组
        if (!SREGameModeBase.isAnySreGameRunning(server)) {
            SREGameModeBase.queueLobbyGroupJoin(server, player.getUUID());
        }
        // 如果有对局运行，不入队（避免把游戏中的玩家拉进大厅群组）
    } catch (Exception e) {
        LOGGER.error("[VoiceGroup] 处理语音群组加入失败", e);
    }
    // 同步配置（原有代码保持不变）
    TaskConfigPayload.sendToPlayer(player);
    CustomTaskBlockPayload.sendToPlayer(player);
    ShaderConfigPayload.sendToPlayer(player);
    FullConfigSyncPayload.sendToPlayer(player);
    // 通知激活的 GameMode 玩家加入
    ServerLevel level = server.getLevel(Level.OVERWORLD);
    if (level != null) {
        GameModeRegistry.getActiveForLevel(level)
            .ifPresent(mode -> mode.onPlayerJoin(player));
    }
});
```

- [ ] **Step 6: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/SREGameModeBase.java \
       src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "fix: voice chat lobby group retry queue + game-aware JOIN logic"
```

---

### Task 10: Full Build + JAR Deployment

- [ ] **Step 1: Clean build the mod**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Copy JAR to temporary directory**

```bash
cp build/libs/habitrain_core-2.0.0.jar "D:\Backup\mc mod\临时\"
```

- [ ] **Step 3: Copy JAR to companion mod's libs (if needed)**

```bash
cp build/libs/habitrain_core-2.0.0.jar "D:\Backup\mc mod\哈比列车更多修改\libs\"
```

- [ ] **Step 4: Commit all remaining uncommitted changes**

```bash
git add -A
git commit -m "chore: final build artifacts and assembly"
```
