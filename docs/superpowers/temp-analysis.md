# 方案对比分析

## 方案 1：开局赋予杀手不同的 SRE 身份（🌟 推荐）

**做法：** 在 `initializeGame()` 中，将 BAD 阵营玩家设为 `TMMRoles.LOOSE_END`（中立角色），GOOD 保持 `CIVILIAN`。

```java
for (ServerPlayer player : players) {
    boolean isBad = BlackoutRoleManager.getFaction(player.getUUID()) == Faction.BAD;
    game.addRole(player, isBad ? TMMRoles.LOOSE_END : TMMRoles.CIVILIAN, false);
}
```

**原理：** SRE 的 `AlivePlayerRoleTeamInfo` 会追踪存活玩家的阵营分布：
- `innocent`（平民阵营）
- `killer`（杀手阵营）
- `all_neturals`（中立阵营）
- `vigilante`（义警阵营）

当存在**多个阵营**的玩家存活时，SRE **不会**判定游戏结束。

| 优点 | 缺点 |
|------|------|
| ✅ **最简洁** — 改 1 行代码 | ⚠️ `LOOSE_END` 可能有未知专属机制 |
| ✅ **不依赖 Mixin** — 纯 API 调用 | ⚠️ 需要验证胜利判定是否真正跳过 |
| ✅ **不破坏 SRE 其他机制** — 商店、金钱都正常 | |
| ✅ **和你的杀手系统完全分离** — LOOSE_END 不是 KILLER，无杀手能力 | |
| ✅ 无需拦截/取消任何方法 | |

**风险分析：** 从 `SREGameWorldComponent` 提取到的方法显示 `hasNeuturals`、`all_neturals` 等字段会被胜利判定使用。只要存在 CIVILIAN + NEUTRAL，SRE 不会触发"全员同阵营"判定。若 `isLooseEndMode()` 关闭可能有影响，需测试验证。

---

## 方案 2：Mixin 拦截 stopGame

**做法：** 新建 Mixin 在 `SREMurderGameMode.stopGame(ServerLevel world)` 头部注入，当 Blackout 模式激活时 `ci.cancel()`。

```java
@Mixin(SREMurderGameMode.class)
public class SREMurderGameModeMixin {
    @Inject(method = "stopGame", at = @At("HEAD"), cancellable = true)
    private void onStopGame(ServerLevel world, CallbackInfo ci) {
        if (GameModeRegistry.getActiveForLevel(world)
                .filter(m -> "habitrains:blackout".equals(m.getId()))
                .isPresent()) {
            ci.cancel();
        }
    }
}
```

| 优点 | 缺点 |
|------|------|
| ✅ **100% 可靠** — 物理上阻止结束 | ❌ 需要维护 Mixin 文件 |
| ✅ **不影响其他机制** — 商店、金钱照常 | ❌ SRE 更新可能改变方法签名 |
| ✅ 目标精准 | ❌ 始终是"防御性"做法 |

---

## 方案 3：重写 tickServerGameLoop

**做法：** 在 `SREBlackoutGameMode` 中覆盖 `tickServerGameLoop` 为空方法。

```java
@Override
public void tickServerGameLoop(ServerLevel world, SREGameWorldComponent game) {
    // Blackout 模式不需要 SRE 的胜负判定
}
```

| 优点 | 缺点 |
|------|------|
| ✅ 绝对阻止 SRE 胜负判定 | ❌ **断送其他机制** — 被动金钱、商店、时间显示等 |
| ✅ 无需 Mixin | ❌ **杀鸡用牛刀** |

---

## 综合推荐

**方案 1（LOOSE_END 方案）>> 方案 2（Mixin stopGame）>> 方案 3（tick override）**

方案 1 才是你问的"是否能在开局直接赋予身份"的答案。它从源头解决了问题，不依赖任何 hack。
方案 2 作为备选，如果方案 1 经过测试发现有问题（比如 LOOSE_END 有副作用），再上 Mixin。
