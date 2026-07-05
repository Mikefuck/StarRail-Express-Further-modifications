# 停电模式任务编写指南

> 记录停电模式交互式任务的标准写法。下次写同类任务时先读本文档，按此模式套用。
> 参考实现：`AddCoalHandler.java` + `AddCoalTask.java`（两阶段右键流程）。
> 参考实现：`BackpackSearchHandler.java` + `BackpackSearchTask.java`（单阶段右键 + 持续搜索）。

---

## 一、整体结构

每个交互式任务由两个类组成：

| 类 | 职责 |
|----|------|
| `XxxTask.java` | `TaskDefinition` 注册 + `onTick`/`onComplete`/`onFail`/`onRemove` 回调，发放奖励、检查失败条件 |
| `XxxHandler.java` | `UseBlockCallback` 监听右键、施加缓慢、推进 `TaskInstance.setProgress()`、tick 内重施缓慢 |

注册位置：
- 任务本体：`HabiTrainCore.onInitialize()` 中 `XxxTask.register()`
- 处理器：紧邻任务注册处调用 `XxxHandler.register()`
- 清理：`GameLifecycleHandler.handleGameEnd()` 末尾加 `XxxHandler.clearAll()`

---

## 二、TaskDefinition Builder 模式

```java
TaskDefinition.builder("habitrain_core", "add_coal")
    .title(Component.translatable("task.add_coal"))
    .description(Component.literal("给发电机添煤"))
    .icon(ItemStack)
    .color(0xFFFFFFFF)  // int ARGB
    .maxProgress(2)     // 阶段数 = maxProgress
    .timeLimit(120)     // 秒；可选，超时自动 onFail
    .faction(Faction.GOOD)  // 仅好人阵营接取（可选）
    .onTick((inst, player) -> { ... })
    .onComplete((inst, player) -> { ... })
    .onFail((inst, player) -> { ... })
    .onRemove((inst, player) -> { ... })
    .build();
```

要点：
- 任务 ID 用 `habitrain_core:<name>`，与 `TaskConfigEntry.key` 对应
- `maxProgress` = 总阶段数；`setProgress(n)` 推进到阶段 n，达到 `maxProgress` 触发 `onComplete`
- `timeLimit` 由引擎自动倒计时；到点引擎调用 `onFail`，无需自己计时
- 手动失败用 `inst.markFailed()`
- 阵营限定用 `BlackoutRoleManager.getFaction(level, uuid)` 自行判断，或 builder 上若有 `faction` 由引擎过滤

---

## 三、Handler 右键交互模板

```java
public class XxxHandler {
    private static final Map<UUID, State> activeStates = new HashMap<>();

    public static void register() {
        UseBlockCallback.EVENT.register(XxxHandler::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeStates.isEmpty()) return;
            long tick = server.overworld().getGameTime();
            for (var it = activeStates.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                State s = e.getValue();
                ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
                if (sp != null && s.slowUntilTick > tick) {
                    // 重施缓慢对抗 betel-nut-mod 每 tick 清除
                    int remaining = (int) (s.slowUntilTick - tick + 10);
                    sp.addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SLOWDOWN, remaining, 2, false, true, true));
                }
                if (s.slowUntilTick <= tick && s.phaseProgressed) {
                    if (sp != null) sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    it.remove();
                }
            }
        });
    }

    public static void clearState(UUID u) { activeStates.remove(u); }
    public static void clearAll() { activeStates.clear(); }

    private static InteractionResult onUseBlock(Player player, Level world,
                                                InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(sp.getUUID());
        if (task == null || !"habitrain_core:xxx".equals(task.getFullId())) return InteractionResult.PASS;
        if (task.isFulfilled() || task.getProgress() >= DONE) return InteractionResult.PASS;

        // ... 按 progress 分支处理
        // 返回 InteractionResult.FAIL 阻止 vanilla 放置/GUI
        // 返回 InteractionResult.PASS 让其他任务有机会处理
    }
}
```

关键约定：
- **`InteractionResult.FAIL`** 防止 vanilla 方块交互（放置方块、打开 GUI）
- **`InteractionResult.PASS`** 让非本任务的右键正常进行
- 方块查找用 `BuiltInRegistries.BLOCK.get(ResourceLocation.parse("modid:block"))` 懒加载 + `blockChecked` 标志缓存，并防 null/`Blocks.AIR`
- 缓慢持续 120 tick（6 秒），`+10` 作为重施 buffer

---

## 四、阵营与奖励发放

```java
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutRoleManager.Faction;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;

// onComplete 中：
Faction faction = BlackoutRoleManager.getFaction(level, uuid);
if (faction != Faction.GOOD) {
    // 杀手假任务：只发字幕，不减时间、不发奖励
    SubtitleNotifier.sendTop(player, title, Component.literal("§7添煤完成"), 60);
    return;
}
BlackoutTimerSystem.reduceTime(level, 15);   // 减对局时间
SREPlayerShopComponent.addToBalance(player, 20);  // 金币
SREPlayerMoodComponent.addMood(player, 0.5f);     // 情绪
```

奖励双重发放风险：
- `RoleMethodDispatcherMixin` 在 SRE `callOnFinishQuest` 路径上，若 `TaskConfigEntry` 有 `gold/emotion >= 0` 则额外发放
- **约定**：交互式任务的 `TaskConfigEntry` **不配置** gold/emotion，奖励在 `onComplete` 内硬编码发放，避免叠加
- 硬编码默认值（金币 20 / 情绪 0.5）可被管理员 `TaskConfigEntry` 覆盖（若实现读取逻辑）

---

## 五、失败条件检查

常见失败条件（在 `onTick` 中检测，每秒触发）：

| 条件 | 实现 |
|------|------|
| 超时 | builder `.timeLimit(秒)`，引擎自动 `onFail` |
| 关键物品丢失 | `onTick` 检查玩家背包，`inst.markFailed()` |
| 阶段超时 | `onTick` 记录进入阶段时的 tick，超过阈值 `markFailed()` |
| 玩家退出 | `onRemove` 清理 Handler 状态 |

清理约定：`onFail` / `onRemove` / `onComplete` 三处都要调 `XxxHandler.clearState(uuid)`。

---

## 六、方块/物品动态查找

跨模组方块（如 `yuushya:generator`）不能编译期依赖，必须运行时查找：

```java
private static Block generatorBlock = null;
private static boolean blockChecked = false;

private static boolean isGeneratorBlock(Block block) {
    if (!blockChecked) {
        try {
            generatorBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("yuushya:generator"));
            if (generatorBlock == null || generatorBlock == Blocks.AIR) {
                HabiTrainCore.LOGGER.warn("发电机方块未找到");
                generatorBlock = null;
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("查找发电机方块出错", e);
            generatorBlock = null;
        }
        blockChecked = true;
    }
    return generatorBlock != null && block == generatorBlock;
}
```

---

## 七、提示与字幕

用 `SubtitleNotifier.sendTop(player, title, subtitle, ticks)` 在屏幕上方显示提示：
- 操作中提示：`§7正在...，请稍候...`（45 tick）
- 阶段完成提示：`§a已取得煤炭！...`（80 tick）
- 错误提示：`§c需要手持煤炭！`（60 tick）

颜色码用 `§` + 代码（非 RGB）。

---

## 八、调试清单

写完新任务后必须验证：

1. `./gradlew compileJava` 编译通过
2. `HabiTrainCore.onInitialize()` 注册了 `XxxTask.register()` 和 `XxxHandler.register()`
3. `GameLifecycleHandler.handleGameEnd()` 调用了 `XxxHandler.clearAll()`
4. `onComplete`/`onFail`/`onRemove` 都调 `XxxHandler.clearState(uuid)`
5. `TaskConfigEntry` 默认未配置 gold/emotion（避免双重发放）
6. 跨模组方块查找有 null/`Blocks.AIR` 防御
7. `UseBlockCallback` 返回 `FAIL` 阻止 vanilla 交互
8. `END_SERVER_TICK` 重施缓慢对抗 betel-nut-mod

---

## 九、参考文件索引

| 文件 | 作用 |
|------|------|
| `game/blackout/task/AddCoalTask.java` | 两阶段任务定义范例 |
| `game/blackout/task/AddCoalHandler.java` | 两阶段右键交互范例 |
| `task/BackpackSearchTask.java` | 单阶段任务定义范例 |
| `task/BackpackSearchHandler.java` | 单阶段右键 + 持续搜索范例 |
| `api/TaskDefinition.java` | Builder API 定义（`.timeLimit` / `.onFail` 等） |
| `api/TaskInstance.java` | `setProgress` / `markFailed` / `isFulfilled` |
| `task/TaskManager.java` | `getActiveTask(uuid)` |
| `task/GameLifecycleHandler.java` | 游戏开始/结束清理入口 |
| `game/blackout/BlackoutRoleManager.java` | `getFaction()` / `Faction.GOOD/BAD` |
| `game/blackout/BlackoutTimerSystem.java` | `reduceTime(level, seconds)` |
| `util/SubtitleNotifier.java` | 屏幕顶部提示 |
| `HabiTrainCore.java` ~560 行 | 任务 + 处理器注册位置 |