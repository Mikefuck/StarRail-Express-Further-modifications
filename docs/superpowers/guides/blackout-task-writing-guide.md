# 鍋滅數妯″紡浠诲姟缂栧啓鎸囧崡

> 璁板綍鍋滅數妯″紡浜や簰寮忎换鍔＄殑鏍囧噯鍐欐硶銆備笅娆″啓鍚岀被浠诲姟鏃跺厛璇绘湰鏂囨。锛屾寜姝ゆā寮忓鐢ㄣ€?> 鍙傝€冨疄鐜帮細`AddCoalHandler.java` + `AddCoalTask.java`锛堜袱闃舵鍙抽敭娴佺▼锛夈€?> 鍙傝€冨疄鐜帮細`BackpackSearchHandler.java` + `BackpackSearchTask.java`锛堝崟闃舵鍙抽敭 + 鎸佺画鎼滅储锛夈€?
---

## 涓€銆佹暣浣撶粨鏋?
姣忎釜浜や簰寮忎换鍔＄敱涓や釜绫荤粍鎴愶細

| 绫?| 鑱岃矗 |
|----|------|
| `XxxTask.java` | `TaskDefinition` 娉ㄥ唽 + `onTick`/`onComplete`/`onFail`/`onRemove` 鍥炶皟锛屽彂鏀惧鍔便€佹鏌ュけ璐ユ潯浠?|
| `XxxHandler.java` | `UseBlockCallback` 鐩戝惉鍙抽敭銆佹柦鍔犵紦鎱€佹帹杩?`TaskInstance.setProgress()`銆乼ick 鍐呴噸鏂界紦鎱?|

娉ㄥ唽浣嶇疆锛?- 浠诲姟鏈綋锛歚HabiTrainCore.onInitialize()` 涓?`XxxTask.register()`
- 澶勭悊鍣細绱ч偦浠诲姟娉ㄥ唽澶勮皟鐢?`XxxHandler.register()`
- 娓呯悊锛歚GameLifecycleHandler.handleGameEnd()` 鏈熬鍔?`XxxHandler.clearAll()`

---

## 浜屻€乀askDefinition Builder 妯″紡

```java
TaskDefinition.builder("habitrain_core", "add_coal")
    .title(Component.translatable("task.add_coal"))
    .description(Component.literal("缁欏彂鐢垫満娣荤叅"))
    .icon(ItemStack)
    .color(0xFFFFFFFF)  // int ARGB
    .maxProgress(2)     // 闃舵鏁?= maxProgress
    .timeLimit(120)     // 绉掞紱鍙€夛紝瓒呮椂鑷姩 onFail
    .faction(Faction.GOOD)  // 浠呭ソ浜洪樀钀ユ帴鍙栵紙鍙€夛級
    .onTick((inst, player) -> { ... })
    .onComplete((inst, player) -> { ... })
    .onFail((inst, player) -> { ... })
    .onRemove((inst, player) -> { ... })
    .build();
```

瑕佺偣锛?- 浠诲姟 ID 鐢?`habitrain_core:<name>`锛屼笌 `TaskConfigEntry.key` 瀵瑰簲
- `maxProgress` = 鎬婚樁娈垫暟锛沗setProgress(n)` 鎺ㄨ繘鍒伴樁娈?n锛岃揪鍒?`maxProgress` 瑙﹀彂 `onComplete`
- `timeLimit` 鐢卞紩鎿庤嚜鍔ㄥ€掕鏃讹紱鍒扮偣寮曟搸璋冪敤 `onFail`锛屾棤闇€鑷繁璁℃椂
- 鎵嬪姩澶辫触鐢?`inst.markFailed()`
- 闃佃惀闄愬畾鐢?`BlackoutRoleManager.getFaction(level, uuid)` 鑷鍒ゆ柇锛屾垨 builder 涓婅嫢鏈?`faction` 鐢卞紩鎿庤繃婊?
---

## 涓夈€丠andler 鍙抽敭浜や簰妯℃澘

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
                    // 閲嶆柦缂撴參瀵规姉 betel-nut-mod 姣?tick 娓呴櫎
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

        // ... 鎸?progress 鍒嗘敮澶勭悊
        // 杩斿洖 InteractionResult.FAIL 闃绘 vanilla 鏀剧疆/GUI
        // 杩斿洖 InteractionResult.PASS 璁╁叾浠栦换鍔℃湁鏈轰細澶勭悊
    }
}
```

鍏抽敭绾﹀畾锛?- **`InteractionResult.FAIL`** 闃叉 vanilla 鏂瑰潡浜や簰锛堟斁缃柟鍧椼€佹墦寮€ GUI锛?- **`InteractionResult.PASS`** 璁╅潪鏈换鍔＄殑鍙抽敭姝ｅ父杩涜
- 鏂瑰潡鏌ユ壘鐢?`BuiltInRegistries.BLOCK.get(ResourceLocation.parse("modid:block"))` 鎳掑姞杞?+ `blockChecked` 鏍囧織缂撳瓨锛屽苟闃?null/`Blocks.AIR`
- 缂撴參鎸佺画 120 tick锛? 绉掞級锛宍+10` 浣滀负閲嶆柦 buffer

---

## 鍥涖€侀樀钀ヤ笌濂栧姳鍙戞斁

```java
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutRoleManager.Faction;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;

// onComplete 涓細
Faction faction = BlackoutRoleManager.getFaction(level, uuid);
if (faction != Faction.GOOD) {
    // 鏉€鎵嬪亣浠诲姟锛氬彧鍙戝瓧骞曪紝涓嶅噺鏃堕棿銆佷笉鍙戝鍔?    SubtitleNotifier.sendTop(player, title, Component.literal("搂7娣荤叅瀹屾垚"), 60);
    return;
}
BlackoutTimerSystem.reduceTime(level, 15);   // 鍑忓灞€鏃堕棿
SREPlayerShopComponent.addToBalance(player, 20);  // 閲戝竵
SREPlayerMoodComponent.addMood(player, 0.5f);     // 鎯呯华
```

濂栧姳鍙岄噸鍙戞斁椋庨櫓锛?- `RoleMethodDispatcherMixin` 鍦?SRE `callOnFinishQuest` 璺緞涓婏紝鑻?`TaskConfigEntry` 鏈?`gold/emotion >= 0` 鍒欓澶栧彂鏀?- **绾﹀畾**锛氫氦浜掑紡浠诲姟鐨?`TaskConfigEntry` **涓嶉厤缃?* gold/emotion锛屽鍔卞湪 `onComplete` 鍐呯‖缂栫爜鍙戞斁锛岄伩鍏嶅彔鍔?- 纭紪鐮侀粯璁ゅ€硷紙閲戝竵 20 / 鎯呯华 0.5锛夊彲琚鐞嗗憳 `TaskConfigEntry` 瑕嗙洊锛堣嫢瀹炵幇璇诲彇閫昏緫锛?
---

## 浜斻€佸け璐ユ潯浠舵鏌?
甯歌澶辫触鏉′欢锛堝湪 `onTick` 涓娴嬶紝姣忕瑙﹀彂锛夛細

| 鏉′欢 | 瀹炵幇 |
|------|------|
| 瓒呮椂 | builder `.timeLimit(绉?`锛屽紩鎿庤嚜鍔?`onFail` |
| 鍏抽敭鐗╁搧涓㈠け | `onTick` 妫€鏌ョ帺瀹惰儗鍖咃紝`inst.markFailed()` |
| 闃舵瓒呮椂 | `onTick` 璁板綍杩涘叆闃舵鏃剁殑 tick锛岃秴杩囬槇鍊?`markFailed()` |
| 鐜╁閫€鍑?| `onRemove` 娓呯悊 Handler 鐘舵€?|

娓呯悊绾﹀畾锛歚onFail` / `onRemove` / `onComplete` 涓夊閮借璋?`XxxHandler.clearState(uuid)`銆?
---

## 鍏€佹柟鍧?鐗╁搧鍔ㄦ€佹煡鎵?
璺ㄦā缁勬柟鍧楋紙濡?`yuushya:generator`锛変笉鑳界紪璇戞湡渚濊禆锛屽繀椤昏繍琛屾椂鏌ユ壘锛?
```java
private static Block generatorBlock = null;
private static boolean blockChecked = false;

private static boolean isGeneratorBlock(Block block) {
    if (!blockChecked) {
        try {
            generatorBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("yuushya:generator"));
            if (generatorBlock == null || generatorBlock == Blocks.AIR) {
                HabiTrainCore.LOGGER.warn("鍙戠數鏈烘柟鍧楁湭鎵惧埌");
                generatorBlock = null;
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("鏌ユ壘鍙戠數鏈烘柟鍧楀嚭閿?, e);
            generatorBlock = null;
        }
        blockChecked = true;
    }
    return generatorBlock != null && block == generatorBlock;
}
```

---

## 涓冦€佹彁绀轰笌瀛楀箷

鐢?`SubtitleNotifier.sendTop(player, title, subtitle, ticks)` 鍦ㄥ睆骞曚笂鏂规樉绀烘彁绀猴細
- 鎿嶄綔涓彁绀猴細`搂7姝ｅ湪...锛岃绋嶅€?..`锛?5 tick锛?- 闃舵瀹屾垚鎻愮ず锛歚搂a宸插彇寰楃叅鐐紒...`锛?0 tick锛?- 閿欒鎻愮ず锛歚搂c闇€瑕佹墜鎸佺叅鐐紒`锛?0 tick锛?
棰滆壊鐮佺敤 `搂` + 浠ｇ爜锛堥潪 RGB锛夈€?
---

## 鍏€佽皟璇曟竻鍗?
鍐欏畬鏂颁换鍔″悗蹇呴』楠岃瘉锛?
1. `./gradlew compileJava` 缂栬瘧閫氳繃
2. `HabiTrainCore.onInitialize()` 娉ㄥ唽浜?`XxxTask.register()` 鍜?`XxxHandler.register()`
3. `GameLifecycleHandler.handleGameEnd()` 璋冪敤浜?`XxxHandler.clearAll()`
4. `onComplete`/`onFail`/`onRemove` 閮借皟 `XxxHandler.clearState(uuid)`
5. `TaskConfigEntry` 榛樿鏈厤缃?gold/emotion锛堥伩鍏嶅弻閲嶅彂鏀撅級
6. 璺ㄦā缁勬柟鍧楁煡鎵炬湁 null/`Blocks.AIR` 闃插尽
7. `UseBlockCallback` 杩斿洖 `FAIL` 闃绘 vanilla 浜や簰
8. `END_SERVER_TICK` 閲嶆柦缂撴參瀵规姉 betel-nut-mod

---

## 涔濄€佸弬鑰冩枃浠剁储寮?
| 鏂囦欢 | 浣滅敤 |
|------|------|
| `game/blackout/task/AddCoalTask.java` | 涓ら樁娈典换鍔″畾涔夎寖渚?|
| `game/blackout/task/AddCoalHandler.java` | 涓ら樁娈靛彸閿氦浜掕寖渚?|
| `task/BackpackSearchTask.java` | 鍗曢樁娈典换鍔″畾涔夎寖渚?|
| `task/BackpackSearchHandler.java` | 鍗曢樁娈靛彸閿?+ 鎸佺画鎼滅储鑼冧緥 |
| `api/TaskDefinition.java` | Builder API 瀹氫箟锛坄.timeLimit` / `.onFail` 绛夛級 |
| `api/TaskInstance.java` | `setProgress` / `markFailed` / `isFulfilled` |
| `task/TaskManager.java` | `getActiveTask(uuid)` |
| `task/GameLifecycleHandler.java` | 娓告垙寮€濮?缁撴潫娓呯悊鍏ュ彛 |
| `game/blackout/BlackoutRoleManager.java` | `getFaction()` / `Faction.GOOD/BAD` |
| `game/blackout/BlackoutTimerSystem.java` | `reduceTime(level, seconds)` |
| `util/SubtitleNotifier.java` | 灞忓箷椤堕儴鎻愮ず |
| `HabiTrainCore.java` ~560 琛?| 浠诲姟 + 澶勭悊鍣ㄦ敞鍐屼綅缃?|

---

## 任务时间影响与自适应刷新概率

### 背景

停电模式供电池任务（add_coal / repair_wiring / maintain_power）会改变停电倒计时。
用户需求：倒计时 < 1 分钟 → 大增刷新权重；倒计时 > 3 分钟 → 几乎不刷。
且改任务时间 delta 时刷新概率曲线自动适配，无需改概率逻辑代码。

### 1. 声明时间影响（TaskDefinition.Builder.timeImpact）

注册任务时通过 .timeImpact(axis, deltaSeconds) 声明：

`java
TaskRegistry.register("habitrain_core", "maintain_power", builder -> builder
    .displayName("维持供电")
    .category(BlackoutMode.BLACKOUT_GOOD)
    .weight(3.0f)
    .blockTypeId(38)
    .instinctColor(0, 200, 255, 200)
    .scanBlockIds("yuushya:generator")
    // ★ 声明时间影响：完成后增加停电倒计时/维护时间 80 秒
    .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 80)
    .onComplete((player, task) -> {
        if (player instanceof ServerPlayer sp) {
            // ★ 通过 applyTimeImpact 统一调用，替代硬编码 BlackoutTimerSystem.delayMaintenanceOrCountdown(level, 80)
            BlackoutTaskHelper.applyTimeImpact(sp.serverLevel(), "habitrain_core:maintain_power");
            BlackoutTaskHelper.grantRewards(sp, "habitrain_core:maintain_power");
        }
    })
);
`

### 2. TimeAxis 时间轴枚举

| 轴 | 含义 | 对应 BlackoutTimerSystem 方法 | delta 符号 |
|---|---|---|---|
| MAINTENANCE_OR_COUNTDOWN | 停电倒计时/维护时间 | delayMaintenanceOrCountdown / reduceMaintenanceOrCountdown | +增加 / -减少 |
| TOTAL_TIME | 对局总时间 | addTime / reduceTime | +增加 / -减少 |
| RESTORE_POWER | 恢复供电（一次性） | restorePower | 0（不关心 delta） |
| TRANSIENT | 触发瞬时停电 | triggerTransientBlackout | 0 |

### 3. 自适应刷新概率曲线（computeUrgencyMultiplier）

供电池任务（MAINTENANCE_OR_COUNTDOWN 轴 + delta > 0）的刷新权重按倒计时自适应：

- 倒计时 ≤ low 阈值 → 权重 = CAP（4.0，大增）
- 倒计时 ≥ high 阈值 → 权重 = FLOOR（0.05，几乎不刷）
- 中间用 smoothstep 反曲线插值

阈值从 delta 派生（自适应）：
- low = max(30, delta × 0.75)
- high = max(180, delta × 3)

| 任务 | delta | low 阈值 | high 阈值 |
|---|---|---|---|
| maintain_power | 80s | 60s | 240s |
| repair_wiring | 40s | 30s | 120s |

**改 delta 时只需改 .timeImpact(...) 一处，曲线自动适配。**

### 4. 维护约定

- 时间 delta 必须在注册时通过 .timeImpact(axis, delta) 声明，不要在 onComplete 写魔法数字（如 BlackoutTimerSystem.delayMaintenanceOrCountdown(level, 80)）。
- onComplete 调用 BlackoutTaskHelper.applyTimeImpact(level, fullId) 即可，它会查 TaskDefinition 的 timeImpact 并调对应 BlackoutTimerSystem 方法。
- 改 delta 自动影响：1) 停电时间效果 2) 自适应刷新概率曲线阈值。
- 一个任务有多个时间效果（如 FurnaceExplosionTask 的 transient + reduce）暂不接入 applyTimeImpact（单 impact 设计），保留硬编码。

### 5. 示例：新增一个 +50s 供电任务

`java
TaskRegistry.register("habitrain_core", "my_supply_task", builder -> builder
    .displayName("我的供电任务")
    .category(BlackoutMode.BLACKOUT_GOOD)
    .weight(3.0f)
    .blockTypeId(99)
    .instinctColor(0, 255, 0, 200)
    .scanBlockIds("mymod:my_block")
    .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 50)
    .onAssign((player, task) -> task.setMaxProgress(1))
    .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
    .onComplete((player, task) -> {
        if (player instanceof ServerPlayer sp) {
            BlackoutTaskHelper.applyTimeImpact(sp.serverLevel(), "habitrain_core:my_supply_task");
            BlackoutTaskHelper.grantRewards(sp, "habitrain_core:my_supply_task");
            SupplyTaskSyncHelper.syncCompletion(sp.serverLevel(), sp.getUUID(), "habitrain_core:my_supply_task");
        }
    })
);
`

delta=50 → low=max(30, 37)=37s, high=max(180, 150)=180s。倒计时 < 37s 时权重=4.0，> 180s 时权重=0.05。