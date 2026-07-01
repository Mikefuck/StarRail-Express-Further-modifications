# 模组合并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge all code from `哈比列车更多修改` (HabiTrain More Tasks) into `哈比列车api` (HabiTrain Core), converting Yarn→Mojmap, fixing known bugs, and integrating initialization.

**Architecture:** One Fabric mod (`habitrain_core`) absorbs the companion mod's task definitions, betel-nut quest system, and blackout tasks. The companion mod project becomes unnecessary and is disabled.

**Tech Stack:** Fabric Loom 1.9+, Minecraft 1.21.1, Java 21, Mojmap mappings, betel-nut-mod 4.0.1

## Global Constraints

- All new code uses `com.habitrain.core.*` package prefix (NOT `com.habitrain.moretasks.*`)
- All task/sound IDs use `habitrain_core:` namespace (NOT `habitrain_more_tasks:`)
- All Minecraft API calls use **Mojmap** names — mapping reference in §Mappings below
- `.build()` on TaskRegistry builder is NOT used (inconsistent; existing tasks in core don't use it)
- betel-nut-mod and CCA version must match existing core CCA version (6.1.2)
- No functional/logic changes to any task; pure rename + relocate + convert

---
## 映射对照表（Yarn → Mojmap）

| Yarn (companion mod) | Mojmap (core mod) |
|---|---|
| `ServerPlayerEntity` | `ServerPlayer` |
| `PlayerEntity` | `Player` |
| `player.getServerWorld()` | `player.serverLevel()` |
| `player.getWorld()` | `player.level()` |
| `ServerWorld` | `ServerLevel` |
| `World` | `Level` |
| `Text.literal()` | `Component.literal()` |
| `ActionResult` / `ActionResult.PASS` / `ActionResult.FAIL` | `InteractionResult` / `InteractionResult.PASS` / `InteractionResult.FAIL` |
| `TypedActionResult` | `TypedActionResult<ItemStack>` |
| `UseBlockCallback` → `ActionResult` | Fabric API unchanged — still `InteractionResult` in Mojmap |
| `RegistryKey<World>` | `ResourceKey<Level>` |
| `Identifier.of()` | `ResourceLocation.fromNamespaceAndPath()` |
| `Hand` | `InteractionHand` |
| `player.getUuid()` | unchanged |
| `player.getInventory()` | unchanged |
| `player.getRandom()` | unchanged |
| `Registries.*` | unchanged |
| `StatusEffectInstance` | unchanged |
| `SoundCategory` | unchanged |
| `BlockHitResult` | unchanged |
| `net.minecraft.text.Text` | `net.minecraft.network.chat.Component` |
| `player.sendMessage(Text, boolean)` | `player.sendSystemMessage(Component)` — **注意：Mojmap 中 `sendSystemMessage` 只有一个参数，原 boolean 参数表示 actionbar，Mojmap 需用 `player.displayClientMessage(component, actionbar)`** |

---

### Task 1: 构建依赖配置

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/fabric.mod.json`

- [ ] **Step 1: Add betel-nut-mod dependency to build.gradle**

在 `dependencies` 块末尾追加：
```groovy
modImplementation files("libs/betel-nut-mod-4.0.1.jar")
```
保持 CCA 依赖不变（core 已依赖 6.1.2，betel-nut-mod 兼容此版本）。

- [ ] **Step 2: Add betel-nut-mod to fabric.mod.json depends**

```json
"depends": {
    // ... existing entries ...
    "betel-nut-mod": "*"
}
```

- [ ] **Step 3: Commit**

```bash
git add build.gradle src/main/resources/fabric.mod.json
git commit -m "build: add betel-nut-mod dependency for merged more-tasks code"
```

---

### Task 2: 搬迁槟榔系统 — com.habitrain.core.betel 包

**Files:**
- Create: `src/main/java/com/habitrain/core/betel/BetelQuestState.java`
- Create: `src/main/java/com/habitrain/core/betel/BetelQuestDefinition.java`
- Create: `src/main/java/com/habitrain/core/betel/BetelLeafHandler.java`

**Interfaces:**
- Produces: `BetelQuestState.init()`, `BetelQuestState.tickPlayer(ServerPlayer)`, `BetelQuestState.registerFoodRestriction()`, `BetelQuestState.getPlayerData(UUID)`, `BetelLeafHandler.register()`, `BetelLeafHandler.tickHarvests(Level)`, `BetelLeafHandler.clearAllHarvests()` — all static, all public

#### Step 1: BetelQuestDefinition.java (Yarn → Mojmap)

包 `com.habitrain.core.betel`，其余改动较少：

```java
package com.habitrain.core.betel;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.awt.Color;

public class BetelQuestDefinition {
    public static void register() {
        TaskRegistry.register(HabiTrainCore.MOD_ID, "betel_quest", builder -> builder
            .displayName("你想咀嚼...")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(14)
            .instinctColor(new Color(46, 139, 87, 180))
            .scanBlockIds("betel-nut-mod:betel_palm_leaves")
            .onAssign((player, task) -> {
                BetelQuestState.markQuestAssigned(player.getUUID());
                BetelQuestState.resetEatenStatus(player);
            })
            .completionChecker((player, task) ->
                BetelQuestState.hasPlayerEatenBetelNut(player.getUUID()))
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer sp) {
                    player.sendSystemMessage(
                        Component.literal("§a[任务完成] §7你满足了对槟榔的渴望！"));
                }
            })
            .canAssign((player, task) -> true)
        );
        HabiTrainCore.LOGGER.info("已注册槟榔任务: 感觉嘴巴缺了点东西");
    }
}
```

**Mojmap 转换要点**：
- `displayName` → 不变（自定义API）
- `player.getName().getString()` → `player.getScoreboardName()` 或直接 cast 保留 — 实际上 `getName()` 在 Mojmap 中来自 `Entity.getName()`，返回 `Component`；`plainName` 不变
- `player.sendMessage(Text.literal(...), false)` → `player.sendSystemMessage(Component.literal(...))`
- `PlayerEntity` → `Player`
- `serverPlayer.getUuid()` → `player.getUUID()`

#### Step 2: BetelLeafHandler.java

同样搬迁到 `com.habitrain.core.betel`：

关键 Mojmap 转换：
- `UseBlockCallback.EVENT.register(BetelLeafHandler::onUseBlock)` → 事件回调签名中 `PlayerEntity` → `Player`
- `player.getServerWorld()` → `player.serverLevel()`
- `World` → `Level`
- `ActionResult` → `InteractionResult`
- `ActionResult.PASS` → `InteractionResult.PASS`
- `ActionResult.FAIL` → `InteractionResult.FAIL`
- `Hand` → `InteractionHand`
- `player.getBlockPos()` → `player.blockPosition()`
- `player.getMainHandStack()` → `player.getMainHandItem()`
- `Text.literal(...)` → `Component.literal(...)`
- `RegistryKey<World>` → `ResourceKey<Level>`
- `Identifier.of(...)` → `ResourceLocation.fromNamespaceAndPath(...)`
- `ServerWorld` → `ServerLevel`
- `player.sendMessage(...)` → `player.sendSystemMessage(...)` (非 actionbar)

特别注意 `getBetelNutCount` 中 `player.getInventory().main` → Mojmap 中是 `player.getInventory().items`。

#### Step 3: BetelQuestState.java

搬迁到 `com.habitrain.core.betel`。这是最大的文件（~680 行）。

**核心映射规则**：
- 所有 `ServerPlayerEntity` → `ServerPlayer`
- 所有 `PlayerEntity` → `Player`
- `getServerWorld()` → `serverLevel()`
- `getWorld()` → `level()`
- `Text.literal(...)` → `Component.literal(...)`
- `sendMessage(Text, boolean)` → `sendSystemMessage(Component)` (非 actionbar)；`displayClientMessage(Component, boolean)` (actionbar)
- `Identifier.of(...)` → `ResourceLocation.fromNamespaceAndPath(...)`
- `Registries.STATUS_EFFECT.getEntry(Identifier.of(...))` → same（不变）
- `SREGameWorldComponent.KEY.get(player.getWorld())` → `SREGameWorldComponent.KEY.get(player.level())`
- `player.getWorld().getTime()` → `player.level().getGameTime()`
- `StatusEffectInstance` → 不变
- `player.addStatusEffect(...)` → 不变

**特别注意**：`registerFoodRestriction()` 中的 `UseItemCallback.EVENT` 签名发生 Yarn→Mojmap 变化：
- `(PlayerEntity player, World world, Hand hand)` → `(Player player, Level level, InteractionHand hand)`
- `TypedActionResult.pass(...)` → Mend 中仍为 `TypedActionResult.pass(...)` 但包路径不变
- `stack.get(DataComponentTypes.FOOD)` → 不变

**HabiTrainCore 引用变更**：
- `HabiTrainMoreTasks.BETEL_NUT_EAT_SOUND` → `HabiTrainCore.BETEL_NUT_EAT_SOUND`
- `HabiTrainMoreTasks.BETEL_NUT_GET_SOUND` → `HabiTrainCore.BETEL_NUT_GET_SOUND`
- `BetelQuestMod.MOD_ID` → `HabiTrainCore.MOD_ID`
- `BetelQuestMod.LOGGER` → `HabiTrainCore.LOGGER`

#### Step 4: 将原 BetelQuestMod 常量内联

原 `BetelQuestMod.java` 只有 MOD_ID 和 LOGGER 常量引用，不需要搬迁。所有引用改为 `HabiTrainCore.MOD_ID` / `HabiTrainCore.LOGGER`。

- [ ] **Step 5: Commit the three new files**

```bash
git add src/main/java/com/habitrain/core/betel/
git commit -m "feat: migrate betel quest system (Yarn→Mojmap, core namespace)"
```

---

### Task 3: 搬迁 task 辅助系统 — com.habitrain.core.task + com.habitrain.core.game.blackout.task

**Files:**
- Create: `src/main/java/com/habitrain/core/task/BackpackQuestState.java`
- Create: `src/main/java/com/habitrain/core/task/BackpackSearchHandler.java`
- Create: `src/main/java/com/habitrain/core/task/GameLifecycleHandler.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/task/AddCoalTask.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionTask.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerTask.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringTask.java`

**Interfaces:**
- Produces: All register() static methods — called from HabiTrainCore.onInitialize()

#### Step 1: BackpackQuestState.java

包 `com.habitrain.core.task`。此文件几乎纯 Java 逻辑，无 Minecraft API 调用，只需要改包路径：
- `BetelQuestMod.LOGGER` → `HabiTrainCore.LOGGER`

```java
package com.habitrain.core.task;

import com.habitrain.core.HabiTrainCore;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BackpackQuestState {
    private static BackpackQuestState instance;
    private final Set<UUID> completedPlayers = new HashSet<>();

    private BackpackQuestState() {}

    public static void init() {
        instance = new BackpackQuestState();
    }

    public static BackpackQuestState getInstance() {
        if (instance == null) instance = new BackpackQuestState();
        return instance;
    }

    public static void markCompleted(UUID uuid) {
        getInstance().completedPlayers.add(uuid);
        HabiTrainCore.LOGGER.debug("玩家 {} 已在本局完成背包翻找任务", uuid);
    }

    public static boolean hasCompleted(UUID uuid) {
        return getInstance().completedPlayers.contains(uuid);
    }

    public void resetAll() {
        completedPlayers.clear();
        HabiTrainCore.LOGGER.info("已重置所有玩家的背包翻找任务完成状态");
    }
}
```

#### Step 2: BackpackSearchHandler.java

包 `com.habitrain.core.task`。关键 Mojmap 转换：

| Yarn | Mojmap |
|---|---|
| `UseBlockCallback.EVENT.register(...)` | 不变（Fabric API） |
| `PlayerEntity` → `Player` | 事件参数 |
| `ServerPlayerEntity` → `ServerPlayer` | Cast |
| `ActionResult` / `ActionResult.PASS` / `FAIL` | `InteractionResult` |
| `Hand` | `InteractionHand` |
| `World` | `Level` |
| `BlockHitResult` | 不变 |
| `Text.literal(...)` | `Component.literal(...)` |
| `Identifier.of(...)` | `ResourceLocation.fromNamespaceAndPath(...)` |
| `HabiTrainMoreTasks.BACKPACK_SEARCH_SOUND` | `HabiTrainCore.BACKPACK_SEARCH_SOUND` |
| `BetelQuestMod.LOGGER` | `HabiTrainCore.LOGGER` |
| `"habitrain_more_tasks:search_backpack"` | `"habitrain_core:search_backpack"` |
| `player.getServerWorld()` | `player.serverLevel()` |
| `player.getWorld().getTime()` | `player.level().getGameTime()` |
| `StatusEffectInstance` | 不变 |
| `ServerWorld` | `ServerLevel` |

注意：`World.getTime()` → Mojmap 中是 `Level.getGameTime()`，返回 `long`，与 Yarn 一致。

#### Step 3: GameLifecycleHandler.java

包 `com.habitrain.core.task`。Mojmap 转换：

| Yarn | Mojmap |
|---|---|
| `ServerPlayerEntity` | `ServerPlayer` |
| `player.getUuid()` | `player.getUUID()` |
| `Identifier.of(...)` | `ResourceLocation.fromNamespaceAndPath(...)` |
| `Registries.STATUS_EFFECT.getEntry(...)` | 不变 |
| `Text.literal(...)` | `Component.literal(...)` |
| `BetelQuestMod.LOGGER` | `HabiTrainCore.LOGGER` |

#### Step 4: Blackout tasks（5 个文件）

包 `com.habitrain.core.game.blackout.task`。

转换要点：
| Yarn | Mojmap |
|---|---|
| `Text.literal(...)` | `Component.literal(...)` |
| `net.minecraft.text.Text` | `net.minecraft.network.chat.Component` |
| `ServerWorld` | `ServerLevel` |
| `player.getWorld()` | `player.level()` |
| `serverLevel.isClient()` | `serverLevel.isClientSide()` |
| `BlockPos` | 不变 |
| `createExplosion(...)` 参数 | 不变 |

**Bug 修复**：`MaintainPowerTask.java` 和 `RepairWiringTask.java` 中的 `.build()` 调用必须移除 — 其他任务和 core 现有代码均不使用 `.build()`。直接删掉 `.build()` 这一行。

- [ ] **Step 5: Commit all 8 new files**

```bash
git add src/main/java/com/habitrain/core/task/ src/main/java/com/habitrain/core/game/blackout/task/
git commit -m "feat: migrate task helpers and blackout tasks (Yarn→Mojmap, core namespace, fix .build inconsistency)"
```

---

### Task 4: 整合初始化逻辑到 HabiTrainCore.java

**Files:**
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java` — 追加初始化

**Interfaces:**
- Consumes: All Task 2 + Task 3 `register()` / `init()` static methods

#### Step 1: Add sound event declarations

在 `HabiTrainCore.java` 中追加音效常量：
```java
/** 吃槟榔音效 */
public static final ResourceLocation BETEL_NUT_EAT_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "betel_nut_eat");
public static final SoundEvent BETEL_NUT_EAT_SOUND = SoundEvent.of(BETEL_NUT_EAT_ID);

/** 获得槟榔音效 */
public static final ResourceLocation BETEL_NUT_GET_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "betel_nut_get");
public static final SoundEvent BETEL_NUT_GET_SOUND = SoundEvent.of(BETEL_NUT_GET_ID);

/** 翻找背包音效 */
public static final ResourceLocation BACKPACK_SEARCH_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "backpack_search");
public static final SoundEvent BACKPACK_SEARCH_SOUND = SoundEvent.of(BACKPACK_SEARCH_ID);

/** 对视音效 */
public static final ResourceLocation LOOK_MY_EYES_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "look_my_eyes");
public static final SoundEvent LOOK_MY_EYES_SOUND = SoundEvent.of(LOOK_MY_EYES_ID);
```

需要 import：
```java
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.resources.ResourceLocation;
```

#### Step 2: Add more-tasks registration to onInitialize()

在现有 `onInitialize()` 末尾追加子方法调用：

```java
// 5. 注册更多模组的任务和系统（合并自 HabiTrainMoreTasks）
registerMoreTasks();
registerMoreSounds();
initBetelSystem();
```

#### Step 3: Implement registerMoreTasks()

谋杀模式自定义任务注册（原 HabiTrainMoreTasks 中主体代码）：
```java
private void registerMoreTasks() {
    int GRASS_BLOCK_TYPE_ID = 12;
    int CAT_BLOCK_TYPE_ID = 13;
    int BACKPACK_TYPE_ID = 15;

    // 任务: test_grass
    TaskRegistry.register(MOD_ID, "test_grass", builder -> builder
        .displayName("test_grass")
        .category(TaskCategory.MURDER)
        .weight(1.0f)
        .blockTypeId(GRASS_BLOCK_TYPE_ID)
        .instinctColor(new Color(0, 200, 0, 180))
        .scanBlocks(Blocks.GRASS_BLOCK)
        .onAssign((player, task) -> task.setMaxProgress(80))
        .onTick((player, task) -> {
            if (task.getProgress() >= task.getMaxProgress()) return;
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getLookAngle();
            double reach = 5.0;
            Vec3 targetPos = eyePos.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);
            BlockHitResult hitResult = player.level().clip(
                new ClipContext(eyePos, targetPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hitResult.getType() == HitResult.Type.BLOCK
                && player.level().getBlockState(hitResult.getBlockPos()).is(Blocks.GRASS_BLOCK)) {
                task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
            } else {
                if (task.getProgress() > 0)
                    task.setProgress(Math.max(0, task.getProgress() - 2));
            }
        })
        .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
    );

    // 任务: pet_cat (摸猫猫)
    TaskRegistry.register(MOD_ID, "pet_cat", builder -> builder
        .displayName("摸猫猫")
        .category(TaskCategory.MURDER)
        .weight(1.0f)
        .blockTypeId(CAT_BLOCK_TYPE_ID)
        .instinctColor(new Color(255, 182, 193, 200))
        .scanBlockIds("yuushya:british_shorthair", "yuushya:white_cat", "yuushya:black_cat",
            "yuushya:ragdoll", "yuushya:calico", "yuushya:siamese", "yuushya:tabby")
        .onAssign((player, task) -> {
            task.setMaxProgress(100);
            player.sendSystemMessage(Component.literal("§d【任务】去找一只猫猫摸一摸！盯着猫猫看5秒！"));
        })
        .onTick((player, task) -> {
            if (task.getProgress() >= task.getMaxProgress()) return;
            Set<Block> catBlocks = resolveCatBlocks();
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getLookAngle();
            double reach = 5.0;
            Vec3 targetPos = eyePos.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);
            BlockHitResult hitResult = player.level().clip(
                new ClipContext(eyePos, targetPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                Block lookedBlock = player.level().getBlockState(hitResult.getBlockPos()).getBlock();
                if (catBlocks.contains(lookedBlock)) {
                    task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                    return;
                }
            }
            if (task.getProgress() > 0)
                task.setProgress(Math.max(0, task.getProgress() - 2));
        })
        .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
        .onComplete((player, task) ->
            player.sendSystemMessage(Component.literal("§a✔ 摸猫猫任务完成！猫猫真可爱！")))
    );

    // 任务: search_backpack (翻找背包)
    TaskRegistry.register(MOD_ID, "search_backpack", builder -> builder
        .displayName("翻找一下自己的背包...")
        .category(TaskCategory.MURDER)
        .weight(1.0f)
        .blockTypeId(BACKPACK_TYPE_ID)
        .instinctColor(new Color(139, 90, 43, 200))
        .scanBlockIds("decocraft:backpack_red")
        .canAssign((player, task) -> !BackpackQuestState.hasCompleted(player.getUUID()))
        .onAssign((player, task) -> {
            task.setMaxProgress(120);
            player.sendSystemMessage(Component.literal("§6【任务】翻找一下自己的背包...右键背包来翻找！"));
        })
        .onTick((player, task) -> {
            if (task.getProgress() >= task.getMaxProgress()) return;
            if (BackpackSearchHandler.isSearching(player.getUUID()))
                task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
        })
        .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
        .onComplete((player, task) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            BackpackQuestState.markCompleted(serverPlayer.getUUID());
            BackpackSearchHandler.stopSearching(serverPlayer.getUUID());
            serverPlayer.removeEffect(MobEffects.SLOWNESS);
            giveRandomBackpackItem(serverPlayer);
            serverPlayer.sendSystemMessage(
                Component.literal("§a✔ 翻找背包完成！你找到了一些有用的东西！"));
        })
    );

    // 任务: look_my_eyes (LOOK MY EYES)
    TaskRegistry.register(MOD_ID, "look_my_eyes", builder -> builder
        .displayName("LOOK MY EYES")
        .category(TaskCategory.MURDER)
        .weight(1.0f)
        .blockTypeId(-1)
        .instinctColor(new Color(255, 105, 180, 200))
        .onAssign((player, task) -> {
            task.setMaxProgress(60);
            player.sendSystemMessage(Component.literal("§d【任务】找到一名玩家，和ta对视3秒！"));
        })
        .onTick((player, task) -> {
            if (task.getProgress() >= task.getMaxProgress()) return;
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            Vec3 eyePos = serverPlayer.getEyePosition();
            Vec3 lookVec = serverPlayer.getLookAngle();
            boolean eyeContact = false;

            for (ServerPlayer otherPlayer : serverPlayer.serverLevel().players()) {
                if (otherPlayer == serverPlayer) continue;
                if (!otherPlayer.isAlive()) continue;

                Vec3 toOther = otherPlayer.getEyePosition().subtract(eyePos);
                double distance = toOther.length();
                if (distance > 3.0) continue;

                Vec3 dirToOther = toOther.normalize();
                Vec3 otherLookVec = otherPlayer.getLookAngle();
                Vec3 dirToThis = eyePos.subtract(otherPlayer.getEyePosition()).normalize();

                double dotThis = lookVec.dot(dirToOther);
                double dotOther = otherLookVec.dot(dirToThis);

                if (dotThis > 0.8 && dotOther > 0.8) {
                    eyeContact = true;
                    break;
                }
            }

            if (eyeContact) {
                task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
            } else if (task.getProgress() > 0) {
                task.setProgress(0);
            }
        })
        .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
        .onComplete((player, task) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.serverLevel().playSound(null, serverPlayer.blockPosition(),
                    LOOK_MY_EYES_SOUND, SoundCategory.PLAYERS, 1.0f, 1.0f);
            }
            player.sendSystemMessage(Component.literal("§a✔ LOOK MY EYES 完成！你们对视了3秒！"));
        })
    );

    // 停电模式任务注册
    com.habitrain.core.game.blackout.task.AddCoalTask.register();
    com.habitrain.core.game.blackout.task.RepairWiringTask.register();
    com.habitrain.core.game.blackout.task.SabotageWiringTask.register();
    com.habitrain.core.game.blackout.task.FurnaceExplosionTask.register();
    com.habitrain.core.game.blackout.task.MaintainPowerTask.register();

    LOGGER.info("已注册更多任务: test_grass, 摸猫猫, 翻找背包, LOOK MY EYES, 停电模式x5");
}
```

**Mojmap 转换要点**：
- `Blocks.GRASS_BLOCK` → 不变
- `BlockHitResult` → 不变
- `HitResult.Type.BLOCK` → 不变
- `player.getWorld().raycast(context)` → `player.level().clip(context)`
- `RaycastContext` → `ClipContext`
- `RaycastContext.ShapeType.OUTLINE` → `ClipContext.Block.OUTLINE`
- `RaycastContext.FluidHandling.NONE` → `ClipContext.Fluid.NONE`
- `Vec3d` → `Vec3`
- `Vec3d.subtract(...)` → `Vec3.subtract(...)`
- `Vec3d.normalize()` → `Vec3.normalize()`
- `Vec3d.dotProduct(...)` → `Vec3.dot(...)`
- `player.getEyePos()` → `player.getEyePosition()`
- `player.getRotationVec(1.0f)` → `player.getLookAngle()`
- `player.sendMessage(Text.literal(...), false)` → `player.sendSystemMessage(Component.literal(...))`
- `StatusEffects.SLOWNESS` → `MobEffects.SLOWNESS`
- `player.removeStatusEffect(...)` → `player.removeEffect(...)`
- `Registry.ITEM.get(Identifier.of(...))` → `Registries.ITEM.get(ResourceLocation.fromNamespaceAndPath(...))`
- `ItemStack(...)` → 不变
- `player.getStackInHand(Hand.MAIN_HAND)` → `player.getItemInHand(InteractionHand.MAIN_HAND)`

#### Step 4: 实现 giveRandomBackpackItem 辅助方法

```java
private void giveRandomBackpackItem(ServerPlayer player) {
    try {
        var gameWorld = SREGameWorldComponent.KEY.get(player.level());
        var roles = gameWorld.getRoles();
        var role = roles.get(player.getUUID());
        if (role == null) {
            LOGGER.warn("玩家没有角色数据，无法发放背包奖励");
            return;
        }

        int roleType = role.getRoleType();
        List<String> itemPool;

        if (roleType == 4) {
            itemPool = List.of(
                "trainmurdermystery:crowbar", "trainmurdermystery:nunchuck",
                "noellesroles:fake_revolver", "noellesroles:fire_axe",
                "noellesroles:bucket_of_h2so4", "noellesroles:throwing_knife",
                "noellesroles:boxing_glove", "noellesroles:pan",
                "noellesroles:handcuffs", "noellesroles:rope",
                "noellesroles:signed_paper", "noellesroles:delivery_box",
                "exposure_polaroid:instant_camera", "noellesroles:extinguisher"
            );
        } else if (roleType == 5) {
            itemPool = List.of(
                "trainmurdermystery:lockpick", "trainmurdermystery:firecracker",
                "trainmurdermystery:iron_door_key", "noellesroles:handcuffs"
            );
        } else {
            itemPool = List.of(
                "betel-nut-mod:synthetic_world_betel", "trainmurdermystery:emoji_helmet",
                "trainmurdermystery:defense_vial", "trainmurdermystery:poison_vial",
                "noellesroles:noell_paperclip", "noellesroles:screwdriver"
            );
        }

        int idx = player.getRandom().nextInt(itemPool.size());
        String itemId = itemPool.get(idx);

        var item = Registries.ITEM.get(ResourceLocation.fromNamespaceAndPath(
            itemId.contains(":") ? itemId.substring(0, itemId.indexOf(':')) : "minecraft",
            itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId));
        if (item != Items.AIR) {
            ItemStack stack = new ItemStack(item, 1);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }

            if ("trainmurdermystery:nunchuck".equals(itemId)) {
                int initialCooldown = (roleType == 4) ? 1000 : 200;
                player.getCooldowns().addCooldown(item, initialCooldown);
                LOGGER.debug("双节棍初始冷却: {} ticks ({}秒, roleType={})",
                    initialCooldown, initialCooldown / 20, roleType);
            }

            player.displayClientMessage(
                Component.literal("§e你从背包中翻找到了: ").append(stack.getHoverName()), true);
            LOGGER.info("玩家 {} 翻找背包获得: {} (阵营类型: {})",
                player.getName().getString(), itemId, roleType);
        } else {
            LOGGER.warn("找不到背包奖励物品: {}", itemId);
        }
    } catch (Exception e) {
        LOGGER.error("发放背包奖励时出错", e);
    }
}
```

Mojmap 注意：
- `player.getInventory().insertStack(stack)` → `player.getInventory().add(stack)`
- `player.dropItem(stack, false)` → `player.drop(stack, false)`
- `player.getItemCooldownManager().set(item, ticks)` → `player.getCooldowns().addCooldown(item, ticks)`
- `item.getName()` → `stack.getHoverName()`
- `player.sendMessage(Text, true)` (actionbar) → `player.displayClientMessage(Component, true)`

#### Step 5: 实现 resolveCatBlocks 辅助方法

```java
private static final String[] CAT_BLOCK_IDS = {
    "yuushya:british_shorthair", "yuushya:white_cat", "yuushya:black_cat",
    "yuushya:ragdoll", "yuushya:calico", "yuushya:siamese", "yuushya:tabby"
};

private static Set<Block> resolveCatBlocks() {
    return Arrays.stream(CAT_BLOCK_IDS)
        .map(id -> {
            String[] parts = id.split(":", 2);
            return Registries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(parts[0], parts[1]));
        })
        .filter(block -> block != Blocks.AIR)
        .collect(Collectors.toSet());
}
```

> 原 `Identifier.of(id)` 在 Mojmap 中可直接用 `ResourceLocation.fromNamespaceAndPath(id)` 解析，但如果 id 格式不规范，拆分成两部分更安全。

#### Step 6: Implement registerMoreSounds()

```java
private void registerMoreSounds() {
    Registry.register(BuiltInRegistries.SOUND_EVENT, BETEL_NUT_EAT_ID, BETEL_NUT_EAT_SOUND);
    Registry.register(BuiltInRegistries.SOUND_EVENT, BETEL_NUT_GET_ID, BETEL_NUT_GET_SOUND);
    Registry.register(BuiltInRegistries.SOUND_EVENT, BACKPACK_SEARCH_ID, BACKPACK_SEARCH_SOUND);
    Registry.register(BuiltInRegistries.SOUND_EVENT, LOOK_MY_EYES_ID, LOOK_MY_EYES_SOUND);
    LOGGER.info("已注册自定义音效: betel_nut_eat, betel_nut_get, backpack_search, look_my_eyes");
}
```

Mojmap 注意：`Registry.register(Registries.SOUND_EVENT, ...)` → `Registry.register(BuiltInRegistries.SOUND_EVENT, ...)`

#### Step 7: Implement initBetelSystem()

```java
private void initBetelSystem() {
    // 强制开启槟榔合成系统
    var betelConfig = BetelNutConfig.get();
    if (!betelConfig.enableAddictionSystem) {
        betelConfig.enableAddictionSystem = true;
        LOGGER.info("已强制开启槟榔mod的成瘾系统（覆盖配置文件设置）");
    } else {
        LOGGER.info("槟榔mod的成瘾系统已开启");
    }

    BetelQuestState.init();
    BackpackQuestState.init();
    BetelQuestDefinition.register();
    BetelLeafHandler.register();
    BackpackSearchHandler.register();
    BetelQuestState.registerFoodRestriction();
    GameLifecycleHandler.register();
}
```

#### Step 8: 在 registerLifecycleEvents() 中追加 tick 处理

在现有 `registerLifecycleEvents()` 方法的 `ServerTickEvents.END_SERVER_TICK` 回调内，在 `GameModeRegistry.tickAll(server);` 之后追加：

```java
// 更多模组 tick 处理器（槟榔、成瘾、游戏检测）
tickMoreMods(server);
```

新建私有方法：
```java
private void tickMoreMods(MinecraftServer server) {
    boolean anyGameActive = false;
    for (ServerLevel world : server.getAllLevels()) {
        BetelLeafHandler.tickHarvests(world);
        if (BetelQuestState.isGameActive(world)) {
            anyGameActive = true;
        }
    }
    GameLifecycleHandler.tickGameEndCheck(anyGameActive, server);
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        BetelQuestState.tickPlayer(player);
        ExtraSlotComponent.KEY.get(player).serverTick();
    }
}
```

Mojmap 注意：
- `server.getWorlds()` → `server.getAllLevels()`
- `server.getPlayerManager().getPlayerList()` → `server.getPlayerList().getPlayers()`

#### Step 9: Add all required imports

`HabiTrainCore.java` 头部追加：
```java
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestDefinition;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.GameLifecycleHandler;
import betel.nut.BetelNutConfig;
import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
```

#### Step 10: 删除 HabiTrainMoreTasks 引用中的旧导入

检查 `HabiTrainCore.java` 中是否有旧的 `import com.habitrain.moretasks.*` — 不应有（原不依赖更多修改）。

- [ ] **Step 11: Commit HabiTrainCore.java changes**

```bash
git add src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "feat: integrate more-tasks initialization into HabiTrainCore"
```

---

### Task 5: 资源文件合并 + Bug 修复

**Files:**
- Create: `src/main/resources/assets/habitrain_core/sounds/betel_nut_eat.ogg`
- Create: `src/main/resources/assets/habitrain_core/sounds/betel_nut_get.ogg`
- Create: `src/main/resources/assets/habitrain_core/sounds/backpack_search.ogg`
- Modify: `src/main/resources/assets/habitrain_core/sounds.json`（已存在则合并，不存在则新建）

#### Step 1: 复制音频文件

将 `D:\Backup\mc mod\哈比列车更多修改\src\main\resources\assets\habitrain_more_tasks\sounds\` 下的 3 个 .ogg 文件复制到 `D:\Backup\mc mod\哈比列车api\src\main\resources\assets\habitrain_core\sounds\`。

#### Step 2: 合并 sounds.json

当前 API mod 无 sounds.json 文件（通过 Glob 确认），新建 `src/main/resources/assets/habitrain_core/sounds.json`：

```json
{
  "betel_nut_eat": {
    "subtitle": "嚼槟榔",
    "sounds": ["habitrain_core:betel_nut_eat"]
  },
  "betel_nut_get": {
    "subtitle": "获得槟榔",
    "sounds": ["habitrain_core:betel_nut_get"]
  },
  "backpack_search": {
    "subtitle": "翻找背包",
    "sounds": ["habitrain_core:backpack_search"]
  },
  "look_my_eyes": {
    "subtitle": "对视",
    "sounds": ["habitrain_core:look_my_eyes"]
  }
}
```

**Bug 修复**：原 `habitrain_more_tasks` 的 `sounds.json` 中 `"sounds"` 数组使用 `"test_more_tasks:"` 命名空间（遗留拼写错误），已修正为 `"habitrain_core:"`。

#### Step 3: 语言文件已合并

通过之前验证，API mod 的 `zh_cn.json` 和 `en_us.json` 已包含更多模组的任务名称条目。无需额外操作。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/assets/habitrain_core/sounds/ src/main/resources/assets/habitrain_core/sounds.json
git commit -m "fix: merge more-tasks sounds (fix namespace bug in sounds.json)"
```

---

### Task 6: 构建验证 + 完整测试

**Files:** 全项目

- [ ] **Step 1: Clean build**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`。JAR 输出到 `build/libs/habitrain_core-*.jar`。

- [ ] **Step 2: 排除报错修复**

如果 build 失败，检查：
- 所有 `Identifier.of()` → `ResourceLocation.fromNamespaceAndPath()`
- 所有 `ServerPlayerEntity` → `ServerPlayer`
- 所有 `ServerWorld` → `ServerLevel`
- 所有 `World` → `Level` (注意 Level/ServerLevel/ClientLevel)
- 所有 `player.getWorld()` → `player.level()`
- 所有 `RegistryKey<World>` → `ResourceKey<Level>`
- 所有 `player.getServerWorld()` → `player.serverLevel()`
- 所有 `Text.literal()` → `Component.literal()`
- 所有 `ActionResult` → `InteractionResult`
- 所有 `Hand` → `InteractionHand`
- `registerFoodRestriction` 中 `TypedActionResult.pass()` 参数类型
- `giveRandomBackpackItem` 中 `Registries.ITEM.get()` 和 `player.getInventory().add()`
- `BetelLeafHandler` 中 `activeHarvests` Iterator 循环中的 import
- `server.getWorlds()` → `server.getAllLevels()`
- `server.getPlayerManager().getPlayerList()` → `server.getPlayerList().getPlayers()`

- [ ] **Step 3: 禁用原更多修改模组**

通知用户：更多修改模组的 `fabric.mod.json` 中 `"depends"` 需添加对 `habitrain_core` 的引用说明，或直接移除该模组。

修改更多修改项目的 `fabric.mod.json`（添加说明或在 IDE 中移除模块）：

可以在更多修改的 `fabric.mod.json` 中添加注释说明模组功能已合并到 `habitrain_core`。或者保留原位不动但移除加载。

更干净的做法：删除该项目的 fabric.mod.json 的 `entrypoints.main`，使模组在运行时不再加载。

```json
// fabric.mod.json — 修改 entrypoints 为空的数组
"entrypoints": {
    "main": []
}
```

- [ ] **Step 4: Copy JAR**

根据 CLAUDE.md 的 Post-Modification Build Rule：
```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 5: 最终提交**

```bash
git add -A
git commit -m "merge: integrate HabiTrain More Tasks into Core (Yarn→Mojmap, bugfixes)"
```
