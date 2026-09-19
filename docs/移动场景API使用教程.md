# 哈比列车核心 · 移动场景 API v2 使用教程

> 面向对象：要给自己的 Mod 加"窗外会动的场景"的开发者、需要脚本化控制列车外景的服主。
> 相关代码：`com.habitrain.core.api.scene`、`com.habitrain.core.scene`。
> 适用版本：`habitrain_core` 2.0.7 起（含 `SceneApi` v2 门面）。

---

## 目录

1. [这套 API 能做什么](#1-这套-api-能做什么)
2. [两条通道：地图级场景 vs API 实例](#2-两条通道地图级场景-vs-api-实例)
3. [依赖接入与入口](#3-依赖接入与入口)
4. [五个核心概念](#4-五个核心概念)
5. [极简上手：三分钟跑通一个场景](#5-极简上手三分钟跑通一个场景)
6. [资产从哪里来](#6-资产从哪里来)
7. [实例完整参数表（SceneInstanceSpec）](#7-实例完整参数表sceneinstancespec)
8. [运动参数完整参数表（SceneProfileBuilder）](#8-运动参数完整参数表sceneprofilebuilder)
9. [空间锚点（跟随玩家/实体）](#9-空间锚点跟随玩家实体)
10. [运行时实时调参](#10-运行时实时调参)
11. [原子操作：modify 与描述重建](#11-原子操作modify-与描述重建)
12. [查询接口](#12-查询接口)
13. [事件监听](#13-事件监听)
14. [客户端 API](#14-客户端-api)
15. [地图级场景与配置通道](#15-地图级场景与配置通道)
16. [网络模型、性能与"无上限"的确切含义](#16-网络模型性能与无上限的确切含义)
17. [生命周期、线程与全局开关](#17-生命周期线程与全局开关)
18. [实战配方](#18-实战配方)
19. [调试与排错](#19-调试与排错)
20. [从 SceneMotionApi 迁移](#20-从-scenemotionapi-迁移)
21. [附录：API 速查表](#21-附录api-速查表)

---

## 1. 这套 API 能做什么

`SceneMotionApi`（v1）只暴露了"读写配置 / 手动开关地图场景"这几件事。`SceneApi`（v2）把整个移动场景系统摊开到外部 Mod 面前：

| 能力 | 说明 |
|---|---|
| **无数量上限的运行时实例** | 同一个维度里想放多少个"会动的场景"就放多少个，API 侧没有配额 |
| **逐字段实时调参** | 速度、方向、原点、旋转、循环、渲染距离、环绕参数、音效、微震，全部可以在运行时改，下一帧生效 |
| **完整时间轴控制** | 提前起跑（headStart）、时间缩放、暂停/恢复（不跳变）、时间轴重置（restart） |
| **空间锚点** | 场景可以钉在世界坐标，也可以跟随某个玩家或某个实体 |
| **可见性控制** | 全服可见，或只发给白名单里的玩家 |
| **生命周期** | 永久存在，或按 tick/秒自动过期回收 |
| **资产发布** | 直接发布 `.hscene` 字节，或用代码构造几何后编码发布；也可以发起世界区域捕获 |
| **事件** | 实例注册/更新/回收、资产发布、地图级场景启停 |
| **诊断** | JSON 快照、可读列表、游戏内命令 |

一句话：**"另一个 Mod 想在玩家窗外放一个会移动的场景"这件事，从资产到生命周期都在这个 API 里。**

---

## 2. 两条通道：地图级场景 vs API 实例

系统里同时存在两套"移动场景"，它们互不干扰、可以叠加显示：

| | 地图级场景（配置页） | API 实例（`SceneApi`） |
|---|---|---|
| 数量 | 每张地图 **1 个主背景 + 最多 4 个附加背景**（合计 5） | **无上限** |
| 数据来源 | `config/habitrain_core.json` 的 `sceneMotion` 节点 | 运行时注册表（内存） |
| 持久化 | 落盘，重启保留 | 不落盘，服务器停止即清空 |
| 归属 | 地图配置 | 注册它的 Mod（`ownerId`） |
| 生命周期 | 跟随对局开始/结束、大厅状态 | 由调用方显式注册/回收，可设置存活时长 |
| 修改方式 | 配置页 / 移动场景配置器 | 本 API 逐字段实时修改 |
| 声音/微震 | 地图级通用效果，多个背景不重复叠加 | **每个实例一条独立音轨**；微震为全局单通道（见 §16.4） |

> ⚠️ 配置页"每张地图最多 5 个"的上限**只约束配置通道**。你要 50 个同屏移动场景，走 API 实例，不会有任何数量限制。

---

## 3. 依赖接入与入口

### 3.1 Gradle 依赖

和接入其它 HabiTrain Core API 完全一样（把上游 JAR 放进 `libs/`）：

```groovy
repositories {
    mavenCentral()
    flatDir { dirs "libs" }
}

dependencies {
    minecraft "com.mojang:minecraft:1.21.1"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:0.18.2"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.116.13+1.21.1"

    modImplementation files("libs/star_rail_express-4.3.0-dev.jar")
    modImplementation files("libs/habitrain_core-2.0.7.jar")   // ← 提供 SceneApi
}
```

`fabric.mod.json` 里声明依赖：

```json
"depends": {
  "habitrain_core": ">=2.0.7"
}
```

### 3.2 取得 API 门面

```java
import com.habitrain.core.api.scene.SceneApi;

SceneApi api = SceneApi.instance();   // 无状态单例，随时可取
```

所有方法都是**服务端调用**（客户端查询请用 `SceneClientApi`，见 §14）。

---

## 4. 五个核心概念

```
资产（Asset）          一份烘焙好的场景几何（.hscene），按"资产键"索引
   │                   例：mymap 或 mymap::habiscene::bg1
   ▼
描述（Spec）            SceneInstanceSpec：资产键 + 运动参数 + 时间轴 + 锚点 + 生命周期
   │
   ▼
实例（Instance）        服务端注册表里的一条记录，有唯一 ID，按维度隔离，实时同步给客户端
   │
   ▼
渲染（Render）          客户端按"assetHash → GPU 网格"去重，实例只提供变换与时间
```

1. **资产键（assetKey）**：资产的字符串主键。地图级场景用的键就是地图键（主背景）或 `地图键::habiscene::背景ID`（附加背景）。API 实例可以复用任意已发布的键，也可以用 `publishAsset` 发布自己的键。
2. **描述（`SceneInstanceSpec`）**：不可变值对象，"我想在世界里放一个什么样的场景"。用 builder 构造。
3. **实例 ID**：全服唯一的字符串，建议用 `命名空间:路径` 形式（如 `mymod:window_a`）。ID 的前缀会自动成为 `ownerId`，用于批量回收。
4. **时间轴**：所有运动都是"`startGameTime` 起、按 `timeScale` 推进"的确定性函数。客户端与服务端各自用本地 `gameTime` 算出同一个相位，**不需要每 tick 发包**。
5. **锚点**：显示原点取自世界坐标（默认）、某个玩家、或某个实体。

---

## 5. 极简上手：三分钟跑通一个场景

假设地图 `mymap` 已经用配置器生成并发布过场景资产（见 §6），现在要额外放一个在它右侧 40 格、速度更快的副本：

```java
package com.example.addon;

import com.habitrain.core.api.scene.SceneApi;
import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.SceneSpawnResult;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;

public final class MyAddon implements ModInitializer {
    private static final String SCENE_ID = "myaddon:window_fast";

    @Override
    public void onInitialize() {
        // 对局开始时注册一个实例（也可以在任何你需要的时候注册）
        com.habitrain.core.api.match.MatchEvents.STARTED.register(level -> spawn(level));
        com.habitrain.core.api.match.MatchEvents.ROUND_ENDED.register((level, settlement) ->
                SceneApi.instance().despawn(SCENE_ID));
    }

    private void spawn(ServerLevel level) {
        SceneSpawnResult result = SceneApi.instance().spawn(level,
                SceneInstanceSpec.builder(SCENE_ID)
                        .assetKey("mymap")                       // 复用该地图的资产
                        .profileEditor(p -> p
                                .displayOrigin(40.0, 64.0, 300.0) // 世界原点
                                .direction(-1.0, 0.0, 0.0)       // 沿 -X 移动
                                .speed(36.0)                     // 比地图场景更快
                                .loopCustom(true, 512.0))        // 每 512 格无缝循环
                        .build());

        if (!result.isSuccess()) {
            // INVALID_SPEC / ALREADY_EXISTS / GLOBAL_DISABLED / LEVEL_NOT_FOUND / SERVER_UNAVAILABLE
            System.out.println("场景注册失败: " + result.status() + " - " + result.message());
        }
    }
}
```

就这些。玩家进图后会看到两份各自移动的窗外场景。

---

## 6. 资产从哪里来

实例只负责"怎么动"，几何本身来自资产。四条路径：

### 6.1 复用已有的地图资产（最常用）

```java
String assetKey = api.primaryAssetKey("mymap");                 // mymap
String bgKey    = api.backgroundAssetKey("mymap", "bg1");        // mymap::habiscene::bg1
boolean ready   = api.hasAsset(assetKey);
```

### 6.2 用配置器 + 捕获（走管理员诊断流程）

```java
api.requestCapture(level, "mymod:my_scene", new SceneBounds(-30, 60, -30, 30, 90, 30), moderatorPlayer);
```

> ⚠️ 捕获走的是**诊断暂存**流程：生成完成后资产进入暂存区，需要持有配置器的管理员在诊断页确认后才会成为正式资产。这是既有的安全设计，API 不绕过它。
> 另外 `requester` 必须是 **OP2** 玩家（捕获会加载选区区块并在其聊天栏播报进度）。
> 需要"立刻可用"的场景请用 6.3 / 6.4。

### 6.3 发布现成的 `.hscene` 字节

```java
byte[] hscene = Files.readAllBytes(Path.of("my_scene.hscene"));
boolean ok = api.publishAsset("mymod:my_scene", hscene);
```

发布流程：格式解码校验 → 体积上限校验（压缩 ≤ 64 MiB，解压 ≤ 128 MiB）→ 内容寻址落盘 → 索引原子替换 → 广播 Manifest → **热更新所有引用该资产的运行中实例**（换哈希并重发）。

### 6.4 用代码构造几何

```java
SceneAssetCodec.AssetData data = new SceneAssetCodec.AssetData(
        SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
        "minecraft:overworld",
        bounds,
        "my-fingerprint",
        List.of(sectionData1, sectionData2)   // SceneAssetCodec.SectionData
);
api.publishAssetData("mymod:generated", data);
```

### 6.5 资产查询与删除

```java
SceneAssetDescriptor descriptor = api.asset("mymap");     // 不存在时返回 EMPTY
Map<String, SceneAssetDescriptor> all = api.assets();
api.deleteAsset("mymod:old_scene");                        // 只摘索引，不删磁盘文件
```

---

## 7. 实例完整参数表（SceneInstanceSpec）

```java
SceneInstanceSpec spec = SceneInstanceSpec.builder("mymod:scene")
        .owner("mymod")                    // 可选；默认取 ID 的命名空间
        .dimension("minecraft:overworld")  // 可选；spawn(level, spec) 时以 level 为准
        .assetKey("mymap")                 // 必填
        .profile(profile)                  // 必填（运动参数，见 §8）
        .profileEditor(p -> p.speed(24))   // 或就地改
        .startGameTime(SceneInstanceSpec.AUTO_START_GAME_TIME) // 默认 = 注册时的 gameTime
        .headStartSeconds(3.0)             // 让场景"一出现就已经走了 3 秒"
        .durationTicks(20 * 60)            // 一分钟后自动回收；<=0 = 永久
        .durationSeconds(60.0)             // 同上，按秒
        .timeScale(1.0)                    // 1 = 正常，2 = 两倍速，0 = 冻结
        .paused(false)
        .priority(10)                      // 排序权重，越大越"上层"
        .tag("train").tag("window")        // 分组标签（最多 32 个）
        .anchor(SceneInstanceAnchor.world())
        .visibleToAll()                    // 默认
        // .visibleTo(playerUuid1, playerUuid2)   // 只对指定玩家可见
        .build();
```

| 字段 | 默认 | 语义与边界 |
|---|---|---|
| `id` | 必填 | 全服唯一。只允许 `a-z A-Z 0-9 _ - . : /`，≤128 字符 |
| `ownerId` | ID 命名空间 | 批量回收用（`despawnAllOfOwner`） |
| `dimensionKey` | 由 spawn 决定 | `spawn(level, spec)` 时 **level 优先** |
| `assetKey` | 必填 | 资产未就绪时实例照常存在，只是画不出来 |
| `startGameTime` | `AUTO` | 显式给一个过去的 `gameTime` = 从那个时刻的相位开始 |
| `headStartSeconds` | `0` | 注册/更新时把时间轴往前挪；与 `phaseOffset` 的区别是它改变时间轴本身 |
| `durationTicks` | `0` | `>0` 时自动过期回收；**每次更新都会重新开始倒计时** |
| `timeScale` | `1.0` | `[0, 16]`，负值不支持（要倒放请反 `direction` / 反 `clockwise`） |
| `paused` | `false` | 暂停时相位冻结在暂停时刻；恢复时把暂停时长补回时间轴，**不会跳变** |
| `priority` | `0` | 影响渲染顺序（半透明叠放）与"取哪一个实例的微震" |
| `tags` | 空 | ≤32 个，便于 `despawnAllWithTag` |
| `anchor` | `WORLD` | 见 §9 |
| `visibleToAll` | `true` | `false` 时必须给出非空白名单，否则校验失败 |

**校验失败**不会抛异常，`spawn/upsert` 返回 `INVALID_SPEC` 并在 `message()` 里给出第一条原因；也可以用 `spec.validationError()` 提前自查。

---

## 8. 运动参数完整参数表（SceneProfileBuilder）

`SceneProfileBuilder` 覆盖配置页里的每一个字段：

```java
SceneProfile profile = SceneProfileBuilder.create()      // create() 默认 enabled=true
        .enabled(true)
        .dimension("minecraft:overworld")                // 仅供配置页展示
        .bounds(-40, 64, -40, 40, 96, 40)                // 源选区（闭区间，含两端）
        .boundsExclusive(0, 0, 0, 16, 16, 16)            // 或直接给 maxExclusive
        .boundsFromPoints(posA, posB)                    // 或两个方块点
        .displayOrigin(0, 64, 300)                       // 局部原点在世界中的落点
        .pivotLocal(0, 8, 0)                             // 旋转枢轴（局部坐标）
        .direction(-1, 0, 0)                             // 自动归一化
        .speed(24.0)                                     // 格/秒，夹取 [0, 64]
        .rotation(yaw, pitch, roll)                      // 度
        .phaseOffset(64.0)                               // 相位偏移（格），不改时间轴
        .loopCustom(true, 512.0)                         // 手动循环距离，夹取 [1, 4096]
        .loopAuto(true)                                  // 或让系统推荐距离
        .render(r -> r.maxDistance(320.0).translucent(true))
        .sound(s -> s.enabled(true).soundId("mymod:rumble").volume(0.5).pitch(1.0).fadeTicks(40))
        .shake(s -> s.intense())                         // 或 .subtle() / .standard()
        .build();
```

### 8.1 直线运动 vs 环绕运动

```java
// 直线（默认）
SceneProfileBuilder.create().linear().direction(-1, 0, 0).speed(20).build();

// 环绕：自动切成 ORBIT 模式
SceneProfileBuilder.create()
        .orbit(o -> o.centerWorld(0, 70, 0)   // 或 .centerModel() 用模型几何中心
                .axis(SceneOrbitAxis.Y)       // Y / X / Z
                .startAngle(45.0)
                .sweep(180.0)                 // 360 = 整圈
                .clockwise(false)
                .angularSpeed(60.0)           // 度/秒，[0, 720]
                .verticalBob(4.0)             // 垂直浮动振幅（格），[0, 64]
                .radialBob(2.0)               // 径向浮动振幅（格），[0, 64]
                .bobCycles(0.5)               // 浮动频率（次/秒），[0, 10]
                .instances(6)                 // 同屏副本数，[1, 16]
                .instanceSpread(60.0)         // 副本角间隔（度）
                .rotateModelWithOrbit(true))
        .build();
```

> 环绕模式的"副本数"是**同一个实例内部的重复**（共享一份网格、只提交不同模型矩阵）；而 API 实例之间的重复共享的是同一份资产网格。两者都不会让显存线性增长。

### 8.2 渲染设置

| 方法 | 区间 | 语义 |
|---|---|---|
| `render.maxDistance(blocks)` | `[32, 512]` | 最远显示距离，超出后整块剔除（包围球判定） |
| `render.translucent(bool)` | — | 是否渲染半透明/裁切层 |

### 8.3 车外环境音

| 方法 | 区间 | 说明 |
|---|---|---|
| `sound.enabled(bool)` | — | 开关 |
| `sound.soundId(id)` | — | 音效 ID，如 `mymod:train_rumble` |
| `sound.volume(v)` | `[0, 1]` | 音量 |
| `sound.pitch(p)` | `[0.5, 2]` | 音高 |
| `sound.fadeTicks(t)` | `[0, 200]` | 淡入淡出时长 |
| `sound.defaultTrainSound()` | — | 用内置列车行驶音并启用 |

**每个 API 实例拥有独立音轨**（内部键 `instance:<实例ID>`），互不打断淡入淡出；地图级场景占用保留音轨 `__primary__`。

### 8.4 镜头微震

| 方法 | 区间 | 说明 |
|---|---|---|
| `shake.enabled(bool)` | — | 开关 |
| `shake.translation(blocks)` | `[0, 0.15]` | 位移振幅 |
| `shake.rotation(deg)` | `[0, 1.5]` | 旋转振幅 |
| `shake.frequency(hz)` | `[0.1, 8]` | 频率 |
| `shake.subtle() / standard() / intense()` | — | 三个预设 |

微震是**全局单通道**：地图级场景在跑微震时不会被动；否则取 `priority` 最大（渲染层级最高）的那个启用微震的实例。

---

## 9. 空间锚点（跟随玩家/实体）

```java
// 1) 世界坐标（默认，零额外开销）
SceneInstanceAnchor.world();

// 2) 跟随玩家：显示原点 = 玩家当前位置 + 偏移
SceneInstanceAnchor.player(player.getUUID(), 0.0, 0.0, 24.0);

// 3) 跟随实体：按客户端网络实体 ID（Entity#getId()）
SceneInstanceAnchor.entity(entity.getId(), 0.0, 1.0, 0.0);

api.setAnchor("mymod:shield", SceneInstanceAnchor.player(uuid, 0, 1, 0));
```

要点：

- 锚点在**客户端每帧解析**（服务端 tick 不参与），所以你不需要每 tick 重发位置。
- 偏移是纯世界轴偏移，不随实体朝向旋转。
- 目标离线/不存在/不在当前维度时，自动回退到 profile 里的静态 `displayOrigin`。
- 实现上会把"锚点位置 + 偏移"写进一份临时的 profile 副本，因此包围球裁剪、环绕中心、起始角等全部随之移动，语义与静态原点完全一致。

---

## 10. 运行时实时调参

所有 `setXxx` 立刻改注册表并同步到客户端，**下一帧生效**，不需要重发资产、不需要重启对局。

```java
SceneApi api = SceneApi.instance();
String id = "mymod:window_a";

api.setSpeed(id, 48.0);                       // 速度
api.setDirection(id, 0, 0, -1);              // 方向
api.setDisplayOrigin(id, 0, 64, 400);        // 原点
api.setRotation(id, 0, 0, 15);               // 欧拉角
api.setPhaseOffset(id, 128.0);               // 相位偏移
api.setLoop(id, true, SceneLoopDistanceMode.CUSTOM, 768.0);
api.setRenderDistance(id, 384.0);
api.setTranslucent(id, false);
api.setMotionMode(id, SceneMotionMode.ORBIT);
api.setOrbit(id, o -> o.centerModel().instances(8).angularSpeed(90.0));
api.setProfileEnabled(id, false);            // 保留实例但停止渲染
api.setAssetKey(id, "myothermap");           // 热换资产（客户端拉新网格）
api.setAnchor(id, SceneInstanceAnchor.world());
api.setPaused(id, true);                     // 冻结相位
api.setTimeScale(id, 0.5);                   // 慢动作
api.restart(id);                             // 时间轴归零（回到起始相位）
api.setHeadStartSeconds(id, 5.0);            // 下一次更新时提前 5 秒起跑
api.setDurationSeconds(id, 30.0);            // 30 秒后自动回收
api.setPriority(id, 5);
api.addTag(id, "boss_intro");
api.setVisibleTo(id, playerA.getUUID(), playerB.getUUID());
api.setVisibleToAll(id);
api.setDimension(id, "minecraft:the_nether");// 迁移到另一个维度
api.editSound(id, s -> s.volume(0.2));
api.editShake(id, s -> s.subtle().enabled(true));
api.editProfile(id, p -> p.speed(60).render(r -> r.maxDistance(512)));
api.modify(id, b -> b.tag("phase_two").priority(20));   // 一次改多项
```

返回值：`boolean`（实例不存在或描述非法时为 `false`）。

---

## 11. 原子操作：modify 与描述重建

`modify` 以"当前描述"为基底套用回调，因此**不需要先查再写**，也不会丢掉没提到的字段：

```java
api.modify("mymod:scene", builder -> builder
        .priority(99)
        .durationTicks(600)
        .profileEditor(p -> p.speed(10).renderDistance(256)));   // profileEditor 同样保留未提及字段
```

想完全替换描述：

```java
SceneInstanceView current = api.instance("mymod:scene").orElseThrow();
SceneInstanceSpec replaced = SceneInstanceSpec.builder("mymod:scene")
        .assetKey(current.assetKey())
        .profile(newProfile)
        .build();
api.upsert(replaced);
```

---

## 12. 查询接口

```java
int total          = api.instanceCount();
int inOverworld    = api.instanceCountIn("minecraft:overworld");
List<SceneInstanceView> all       = api.instances();
List<SceneInstanceView> inLevel   = api.instancesIn(serverLevel);
List<SceneInstanceView> inDim     = api.instancesIn("minecraft:overworld");
List<SceneInstanceView> mine      = api.instancesOfOwner("mymod");
List<SceneInstanceView> tagged    = api.instancesWithTag("train");
Optional<SceneInstanceView> one   = api.instance("mymod:scene");
boolean exists     = api.hasInstance("mymod:scene");
```

`SceneInstanceView` 提供：`id / ownerId / dimensionKey / assetKey / assetHash / hasAsset / profile / anchor / startGameTime / timeScale / paused / durationTicks / expireAtGameTime / remainingTicks(gameTime) / isExpired(gameTime) / priority / tags / hasTag / visibleToAll / visibleTo / isVisibleTo(uuid) / belongsTo(dimensionKey) / revision / createdAtMillis / elapsedSeconds(gameTime, partialTick)`。

批量回收：

```java
api.despawn("mymod:scene");
api.despawnAllOfOwner("mymod");
api.despawnAllWithTag("train");
api.despawnAllIn(serverLevel);
api.despawnMatching(view -> view.priority() < 0);
api.despawnAll();
```

---

## 13. 事件监听

```java
api.addListener(new SceneListener() {
    @Override public void onInstanceSpawned(SceneInstanceView instance) { }
    @Override public void onInstanceUpdated(SceneInstanceView instance) { }
    @Override public void onInstanceRemoved(SceneInstanceView instance, String reason) { }
    @Override public void onMapSceneStarted(ServerLevel level, String mapKey) { }
    @Override public void onMapSceneStopped(ServerLevel level, String mapKey) { }
    @Override public void onAssetPublished(String assetKey, SceneAssetDescriptor descriptor) { }
    @Override public void onInstancesResynced() { }
});
```

- 回调都在**服务端主线程**（tick / 玩家事件）里同步触发，可以直接操作世界。
- `onInstanceRemoved` 的 `reason` 取值：`despawn`（显式回收）、`expired`（存活期到）、`replaced`（被替换）、`reset`（全局清空）。
- 单个监听器抛异常会被隔离（记日志），不会影响其它监听器与场景系统。

---

## 14. 客户端 API

只在客户端加载，用于查询"服务端现在让我看到什么"：

```java
import com.habitrain.core.api.scene.client.SceneClientApi;

SceneClientApi client = SceneClientApi.instance();

boolean mapSceneActive = client.isMapSceneActive();     // 地图级场景
SceneRuntimeState state = client.mapSceneState();
boolean previewing      = client.isPreviewActive();

List<SceneInstance> mine = client.instancesInCurrentDimension();
int known               = client.instanceCount();
boolean ready           = client.isMeshReady("mymod:scene");   // 网格是否已烘焙完成
boolean inRange         = client.instance("mymod:scene")
        .map(i -> i.elapsedSeconds(...) > 0).orElse(false);
long gpuBytes           = client.meshBytes();
client.requestResync();                                  // 主动要求服务端全量重发
```

客户端 Mod 想做"场景实体化"（比如根据场景相位生成粒子/音源）时，用 `instancesInCurrentDimension()` + `elapsedSeconds(...)` 就能拿到与服务端一致的确定性相位。

---

## 15. 地图级场景与配置通道

API 同样能读写配置通道（改动会写进 `config/habitrain_core.json`）：

```java
api.isGlobalEnabled();
api.setGlobalEnabled(true);

SceneRuntimeState state = api.runtimeState(level);
api.isSceneActive(level);
api.startScene(level, "mymap");
api.stopScene(level);
SceneContextResolver.SceneContext ctx = api.resolveContext(level);  // mapKey/dimensionKey/matchActive
api.registerContextResolver(myResolver);                            // 替换默认 SRE 解析器

SceneProfile profile = api.profile("mymap");
api.setMapProfile("mymap", SceneProfileBuilder.create().speed(30).build());
api.editMapProfile("mymap", p -> p.renderDistance(256));
Set<String> maps = api.profileMapKeys();

List<SceneMotionSettings.ResolvedBackground> backgrounds = api.backgrounds("mymap");
api.putBackground("mymap", "bg1", "动态背景 1", profile);   // 受每图 4 个上限约束
api.removeBackground("mymap", "bg1");

api.defaultMapKey();          // __default__
api.lobbyMapKey();            // __lobby__
api.isLobbyMapKey("__lobby__");
api.maxBackgroundsPerMap();   // 5
```

---

## 16. 网络模型、性能与"无上限"的确切含义

### 16.1 同步模型

| 时机 | 行为 |
|---|---|
| `spawn / upsert` | 只向**该维度内**、且通过可见性过滤的玩家发送"单实例一包"，并附带资产 Manifest 授权 |
| 任意 `setXxx` | 同上（revision 自增） |
| `despawn` | 向所有在线玩家发一条删除包 |
| 玩家 JOIN | 先发 `clear`（清掉客户端残留），再全量重发该维度实例 |
| 换维度 | 同上（清空 + 目标维度全量） |
| 对局结束 | 客户端会清空本地状态，随后主动请求重同步；服务端也会补一次全量，双向兜底 |
| 全局开关重新打开 | 全量重发 |
| 单实例一包 | 意味着**实例总数与单包大小无关**；唯一的包级限制是单包 ≤256 条目（服务端永远只发 1 条）与单实例 profile JSON ≤128 KiB |

### 16.2 "无上限"的确切含义

- 服务端注册表是无界的 `ConcurrentHashMap`，没有配额、没有预分配数组。
- 协议没有"总数上限"字段；同步按变更条目增量下发。
- **客户端显存**才是真实约束：实例按 `assetHash` **共享同一份 GPU 网格**。50 个实例引用 1 份资产 = 1 份网格；50 个实例引用 50 份不同资产 = 50 份网格。
- 显存配额（配置页 `scenePerformance`）只在超限时淘汰**当前没有任何实例/背景引用**的网格；在用的网格不会被淘汰，只会打印一条 debug 日志说明"仍在配额之上"。
- 结论：**实例数量本身几乎免费，真正贵的是"不同资产的数量"。** 大批量实例请尽量复用同一份资产。

### 16.3 时间与相位

- 时间是确定性的：`elapsed = (gameTime - startGameTime) * timeScale / 20`，暂停时冻结在暂停时刻。
- 服务端只在注册/更新/删除时发包，**运动本身零网络流量**。

### 16.4 声音与微震

- 环境音：每个实例一条独立音轨（`instance:<id>`），按 tick 对齐期望集合，音量各自淡入淡出。
- 微震：全局单通道。地图级场景在跑微震时优先；否则取 `priority` 最大的启用微震的实例。
- 客户端状态被清空后，下一个 tick 会自动补上（音轨对齐是自愈的）。

### 16.5 维度隔离

实例只在自己所属维度渲染，也只发给该维度的玩家。跨维度迁移用 `setDimension(id, dimKey)`。

---

## 17. 生命周期、线程与全局开关

### 17.1 线程约定

- **写操作**（`spawn/upsert/despawn/setXxx/publishAsset`…）会立即改注册表并同步发包，**必须在服务端主线程调用**：tick 回调、命令、`MatchEvents`、`SceneListener` 回调里都是主线程。
- **读操作**（`instanceCount/instances/asset/...`）任意线程安全。
- `publishAsset` 内含磁盘写与解码，字节生成请放在后台线程，或接受一次写盘卡顿。

### 17.2 持久化

- API 实例是**纯运行时**状态：不写配置、不跨存档保存、服务器停止即全部清空。
- 需要持久化的场景请用地图级配置（配置页 / `setMapProfile`）。
- 典型模式：下游 Mod 在 `SERVER_STARTED` / `MatchEvents.STARTED` 里按自己的规则重新注册。

### 17.3 全局开关

`sceneMotion.enabled = false` 时：

- `spawn/upsert` 直接返回 `GLOBAL_DISABLED`，不占注册表。
- 已登记的实例**保留在注册表中但不下发**；关闭瞬间客户端会收到清空指令。
- 重新打开后自动全量重发，无需重新注册。

```java
if (!api.isGlobalEnabled()) {
    // 提前判断，避免无意义的调用
}
```

### 17.4 重同步

```java
api.resync("mymod:scene");        // 单实例
api.resyncDimension(level);       // 单维度
api.resyncAll();                  // 全服
```

---

## 18. 实战配方

### 配方 1：与地图场景并行的"双层车窗"

```java
// 远层：慢速、大循环距离
api.spawn(level, SceneInstanceSpec.builder("mymod:far_layer")
        .assetKey("mymap")
        .profileEditor(p -> p.displayOrigin(0, 64, 600).speed(12).loopCustom(true, 1024.0))
        .priority(0)
        .build());

// 近层：快速、小循环距离、略微旋转
api.spawn(level, SceneInstanceSpec.builder("mymod:near_layer")
        .assetKey("mymap::habiscene::bg1")
        .profileEditor(p -> p.displayOrigin(0, 64, 260).speed(48)
                .loopCustom(true, 256.0).rotation(0, 0, 2.5))
        .priority(10)
        .build());
```

### 配方 2：跟随玩家的光环/护盾

```java
api.spawn(level, SceneInstanceSpec.builder("mymod:aura")
        .assetKey("mymod:aura_asset")
        .anchor(SceneInstanceAnchor.player(player.getUUID(), 0.0, 1.2, 0.0))
        .profileEditor(p -> p.orbit(o -> o.centerModel().instances(8)
                .angularSpeed(120.0).radialBob(0.15).bobCycles(0.8)))
        .durationSeconds(15.0)
        .visibleTo(player.getUUID())
        .build());
```

### 配方 3：过场演出（临时 + 慢动作 + 倒放）

```java
api.spawn(level, SceneInstanceSpec.builder("mymod:intro")
        .assetKey("mymod:intro_asset")
        .headStartSeconds(2.0)              // 一出现就已经走了 2 秒
        .timeScale(0.35)                    // 慢动作
        .durationSeconds(12.0)              // 12 秒后自动回收
        .direction(1, 0, 0)                 // 反向运动 = 倒放效果
        .build());
```

### 配方 4：每名玩家一份独立场景

```java
for (ServerPlayer player : level.players()) {
    api.upsert(level, SceneInstanceSpec.builder("mymod:private_" + player.getUUID())
            .assetKey("mymod:personal_scene")
            .anchor(SceneInstanceAnchor.player(player.getUUID(), 0, 0, 30))
            .visibleTo(player.getUUID())
            .build());
}
```

### 配方 5：把场景钉在移动的列车上

```java
// 服务端实体（比如列车车厢）每 tick 移动，客户端无需发包即可跟随
api.spawn(level, SceneInstanceSpec.builder("mymod:carriage_view")
        .assetKey("mymod:outside")
        .anchor(SceneInstanceAnchor.entity(carriageEntity.getId(), 0.0, 4.0, 0.0))
        .build());
```

### 配方 6：按阶段改编排

```java
// 开局：慢速
api.setSpeed("mymod:view", 18);
// 进入危险阶段：加速 + 抖动加强
api.modify("mymod:view", b -> b
        .profileEditor(p -> p.speed(60).shake(s -> s.intense()))
        .tag("danger"));
// 结束时：全部回收
api.despawnAllWithTag("danger");
```

---

## 19. 调试与排错

### 19.1 游戏内命令（OP2）

```text
/habitrain scene api            # 列出全部 API 实例 + 总数 + 全局开关
/habitrain scene api clear      # 回收全部 API 实例
/habitrain scene api resync     # 向所有在线玩家全量重发
/habitrain scene status         # 地图级场景状态（原有命令）
/habitrain scene start|stop     # 手动启停地图级场景
```

### 19.2 代码诊断

```java
JsonObject snapshot = api.diagnostics();     // 含每个实例的完整 profile
List<String> lines  = api.describeInstances();
```

### 19.3 常见问题

| 现象 | 原因与处理 |
|---|---|
| `spawn` 返回 `GLOBAL_DISABLED` | `sceneMotion.enabled=false`。`api.setGlobalEnabled(true)` 或让服主打开 |
| `spawn` 返回 `ALREADY_EXISTS` | ID 已被占用。用 `upsert` 覆盖，或先 `despawn` |
| `spawn` 返回 `LEVEL_NOT_FOUND` | `spec.dimensionKey()` 写错，或该维度尚未加载 |
| `spawn` 返回 `INVALID_SPEC` | 看 `message()`：ID 字符非法 / assetKey 为空 / timeScale 超界 / 白名单为空 |
| 实例存在但看不见 | ①资产键没有已发布资产（`api.hasAsset(key)`）；②`profile.enabled=false`；③玩家不在该维度；④可见性白名单没包含他；⑤超出 `render.maxDistance`；⑥全局开关关闭 |
| 客户端一直在下载 | 资产体积大或首次进入；`SceneClientApi.isMeshReady(id)` 可用于 UI 提示 |
| 场景位置不对 | 锚点解析失败会静默回退静态原点；检查目标玩家/实体是否在线、是否同维度 |
| 改参数没反应 | 确认在服务端主线程调用；确认 ID 完全一致（区分大小写） |
| 暂停后位置跳了一段 | 不会发生：恢复时暂停时长会被补回时间轴。若确实跳变，检查是不是同时改了 `startGameTime` |
| 大量实例后掉帧 | 检查是否引用了大量**不同**资产；复用同一资产键即可共享网格 |

---

## 20. 从 SceneMotionApi 迁移

`SceneMotionApi`（v1）保留且行为不变，二者共用同一套底层实现：

| v1 | v2 等价 |
|---|---|
| `SceneMotionApi.instance().getProfile(mapKey)` | `SceneApi.instance().profile(mapKey)` |
| `setProfile(mapKey, profile)` | `setMapProfile(mapKey, profile)` |
| `isSceneActive(level)` | 同名 |
| `startScene(level, mapKey)` / `stopScene(level)` | 同名 |
| `getAssetDescriptor(mapKey)` | `asset(assetKey)` |
| `registerContextResolver(resolver)` | 同名 |
| `getSettings()` | `config()` |

v2 新增的是**整条实例通道**（§7–§14），两者可以混用：地图级场景负责"本图默认外景"，API 实例负责"额外/动态/个性化的外景"。

---

## 21. 附录：API 速查表

### 21.1 `SceneApi`（`com.habitrain.core.api.scene.SceneApi`）

| 分组 | 方法 |
|---|---|
| 全局 | `config()` `isGlobalEnabled()` `setGlobalEnabled(bool)` `defaultMapKey()` `lobbyMapKey()` `isLobbyMapKey(k)` `maxBackgroundsPerMap()` |
| 资产键助手 | `normalizeMapKey` `primaryAssetKey` `backgroundAssetKey` `mapKeyFromAssetKey` `backgroundIdFromAssetKey` |
| 查询 | `instanceCount` `instanceCountIn` `instances` `instancesIn(level/键)` `instancesOfOwner` `instancesWithTag` `instance` `hasInstance` |
| 生命周期 | `spawn(spec)` `spawn(level,spec)` `upsert(spec)` `upsert(level,spec)` `despawn` `despawnAllOfOwner` `despawnAllWithTag` `despawnMatching` `despawnAllIn` `despawnAll` `resync` `resyncDimension` `resyncAll` `modify` |
| 实时调参 | `setProfile` `editProfile` `setProfileEnabled` `setSpeed` `setDirection` `setDisplayOrigin` `setRotation` `setPhaseOffset` `setLoop` `setRenderDistance` `setTranslucent` `setMotionMode` `setOrbit` `setOrbitSettings` `editSound` `editShake` `setAssetKey` `setAnchor` `setPaused` `setTimeScale` `restart` `setStartGameTime` `setHeadStartSeconds` `setDurationTicks` `setDurationSeconds` `setPriority` `addTag` `setTags` `setVisibleToAll` `setVisibleTo` `setDimension` |
| 地图级 | `runtimeState` `isSceneActive` `startScene` `stopScene` `resolveContext` `registerContextResolver` |
| 配置 | `profile` `getOrCreateProfile` `setMapProfile` `editMapProfile` `profileMapKeys` `backgrounds` `backgroundProfile` `backgroundName` `putBackground` `removeBackground` |
| 资产 | `asset` `hasAsset` `assets` `deleteAsset` `publishAsset` `publishAssetData` `requestCapture` `cancelCapture` |
| 事件 | `addListener` `removeListener` |
| 诊断 | `diagnostics` `describeInstances` `snapshot` |

### 21.2 相关类型

| 类型 | 用途 |
|---|---|
| `SceneInstanceSpec` / `SceneInstanceSpec.Builder` | 实例描述与构建 |
| `SceneProfileBuilder`（含 `Orbit` / `Render` / `Sound` / `Shake`） | 运动参数构建 |
| `SceneInstanceAnchor` | 空间锚点（`world/player/entity`） |
| `SceneSpawnResult` / `SceneSpawnResult.SceneSpawnStatus` | 注册结果与失败原因 |
| `SceneInstanceView` | 实例只读视图 |
| `SceneListener` | 服务端事件回调 |
| `SceneClientApi` | 客户端查询 |
| `SceneProfile` / `SceneBounds` / `SceneLoopSettings` / `SceneOrbitSettings` / `SceneRenderSettings` / `SceneSoundSettings` / `SceneShakeSettings` | 底层值对象（可直接读写） |
| `SceneAssetDescriptor` / `SceneAssetCodec` | 资产元数据与编解码 |

---

## 相关文档

- 📑 [API 参考手册](API参考手册.md)
- 📕 [哈比列车核心全方位使用教程](使用教程.md)
- 🎬 [大厅移动背景](大厅移动背景.md)
