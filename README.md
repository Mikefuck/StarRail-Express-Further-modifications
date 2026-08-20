# 哈比列车核心（HabiTrain Core）

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.18.2+-blue.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg)](LICENSE)
[![Mod ID](https://img.shields.io/badge/Mod%20ID-habitrain__core-purple.svg)](#)
[![Version](https://img.shields.io/badge/Version-2.0.2-blueviolet.svg)](#)

**哈比列车核心（HabiTrain Core）** 是专为 Minecraft 1.21.1 / Fabric 开发的《星穹列车》（StarRailExpress，简称 SRE）大型扩展核心模组，模组 ID 为 `habitrain_core`。

它拥有**双重核心定位**：
1. **开箱即用的大型游戏机制增强模组**：内置沉浸式「停电模式」、管理专用「维修人员模式」、5 大特色投稿职业、完整的「七宗罪」角色体系与「七美德」词条修饰符体系、淘汰玩家休息区、全功能 ModMenu 中文可视化配置中心、全服 MVP 结算大屏、智能模式与地图双阶段投票、大厅与局内环境控制、Simple Voice Chat 语音联动以及 Iris 光影白名单检测防护等。
2. **面向下游生态的标准化扩展 API 框架**：为所有 SRE 扩展/DLC 模组提供角色扩展 API v2/v1（支持新增、可撤销修改、完全替换与别名迁移）、自定义任务系统 API、游戏模式注册与生命周期拦截 API、双阶段投票系统 API、任务道具安全回收体系及完整的诊断与快照体系。

---

## 目录

- [环境要求](#环境要求)
- [核心内置功能与玩法](#核心内置功能与玩法)
  - [1. 特色游戏模式](#1-特色游戏模式)
  - [2. 角色与修饰符体系](#2-角色与修饰符体系)
  - [3. 对局与体验增强](#3-对局与体验增强)
  - [4. 安全与运维防御](#4-安全与运维防御)
- [ModMenu 可视化配置中心](#modmenu-可视化配置中心)
- [开发者 API 体系总览](#开发者-api-体系总览)
  - [API 模块索引](#api-模块索引)
  - [快速代码示例](#快速代码示例)
- [完整命令速查表](#完整命令速查表)
- [项目结构](#项目结构)
- [构建与交付](#构建与交付)
- [文档索引](#文档索引)
- [许可证](#许可证)

---

## 环境要求

| 依赖项 | 推荐 / 最低版本 | 说明 |
|---|---|---|
| **Java** | 21 (`options.release = 21`) | 必须使用 Java 21 运行与编译 |
| **Minecraft** | 1.21.1 | 目标游戏版本 |
| **Fabric Loader** | 0.18.2 或更高 | 模组加载器 |
| **Fabric API** | 0.116.13+1.21.1 | 核心运行库 |
| **StarRailExpress (SRE)** | 4.3.0 | 上游本体模组 |
| **Simple Voice Chat** | 1.21.1-2.5.x | 可选，用于语音群组划分与静音集成 |
| **Betel Nut Mod (槟榔)** | 兼容版本 | 集成成瘾系统与特色搜包任务 |

---

## 核心内置功能与玩法

### 1. 特色游戏模式

#### ⚡ 停电模式（Blackout Mode）
- **发电机与电力系统**：游戏开始后全图陷入供电危机，玩家需要前往发电机添加燃煤维持电力或抢修受损电线；杀手/破坏者可破坏电线和破坏发电机加速停电。
- **致盲与氛围增强**：停电时全场失去视野与照明，伴随专属音效、警报声与盲目效果；玩家可使用手电筒或通过黑市购买沙漠之鹰、弹药等防身武器。
- **专属任务与假任务**：内置 7 种日常停电任务（饱腹、饮水、搜寻背包、嚼槟榔、撸猫、独处、对视等），好人执行推进进度，坏人自动获得对应的假任务池用以伪装。
- **电话与紧急会议**：支持特定点位拨打救援电话、拉响号角开启紧急会议投票。

#### 🛠️ 维修人员模式（Repair Mode）
- OP 管理员可通过 `/habi_api repair <mapId>` 直接进入创造模式对指定地图进行实地修缮与排查。
- **智能抽图隔离**：被维修人员锁定的地图会自动从玩家模式投票池和选图池中临时移除，避免对局选入未修好的地图。
- **安全恢复**：退出维修模式时自动恢复原本的参与状态与游戏模式，支持多人协同维修与强制解锁。

#### 🚆 原版模式无缝桥接（SRE Bridge）
- 自动扫描并代理 SRE / Wathe 及其衍生版本中的所有原版模式（谋杀模式、修机模式等），统一纳入 `GameModeRegistry` 并自动展示在模式列表与投票池中。

---

### 2. 角色与修饰符体系

#### 🎭 5 大投稿特色角色
- **替罪羊（Crime Scapegoat）**：具有特殊的罪名替罪与阵营博弈机制。
- **卖花女（Flower Girl）**：拥有鲜花传递、祝福与专属辅助技能。
- **疾风（Swift Wind）**：拥有极速突进与高机动位移能力。
- **默杀者（Mime Killer）**：无声刺杀，击杀时不触发常规声音提示与警报。
- **Mike**：具有专属的代码编辑技能与独特的对局干预机制。

#### 👿 七宗罪角色体系（Seven Sins）
七大具备极强博弈深度的中立/独立阵营角色，各具独特的胜利条件与互斥规则：
- **傲慢（Pride）** / **嫉妒（Envy）** / **暴怒（Wrath）** / **懒惰（Sloth）**
- **贪婪（Greed）**：携带专属「贪婪钱袋」，支持玩家间匿名发起交易与双确认机制（可使用 `/habi_api greed_trade`）。
- **暴食（Gluttony）** / **色欲（Lust）**

#### 🕊️ 七美德修饰符体系（Seven Virtues）
为玩家角色附加的词条修饰符系统，遵循**单美德独占互斥规则**：
- **纯洁（Chastity）**、**谦逊（Humility）**、**宽容（Mercy，仅好人阵营）**、**节制（Temperance）**、**勤勉（Diligence）**、**耐心（Patience）**，并深度关联上游**慷慨（Generosity）**。

---

### 3. 对局与体验增强

- 🛋️ **淘汰玩家休息区（Eliminated Rest Area）**：玩家被击杀或处决后，可通过屏幕提示或按键一键在「休息区漫步」与「全图自由观战」之间自由切换。
- 🏆 **全服 MVP 结算大屏（MvpScoreTracker & GameEndTransition）**：对局结束时全服平滑进入结算大屏，全景展示胜利阵营、MVP 玩家及各维度表现数据。
- 🗳️ **智能双阶段投票（Mode & Map Voting）**：
  - 第一阶段投票选定游戏模式，第二阶段投票选定游戏地图。
  - **按人数动态抽图**：根据当前在线玩家数自动过滤适配该人数区间的地图池，并随机抽取设定数量的地图供玩家投票。
- 🌦️ **全局环境控制器（Environment Controller）**：大厅（Lobby）、对局中（Match）、结算后（Post-Match）三阶段独立配置天气（晴天/雨天/雷暴）与时间锁定。
- 🗡️ **小刀耐久与平衡机制**：可配置杀手小刀耐久度与充能机制，支持基于总人数自动计算警长比例（`sheriffCountDivisor`）。
- 🎙️ **Simple Voice Chat 联动**：提供 `/instantgroup [range]` 范围快速组队，并支持大厅阶段自动组队语音。
- 🌿 **槟榔模组深度集成**：强制启用成瘾系统，并与停电搜包、嚼槟榔任务深度联动。

---

### 4. 安全与运维防御

- 🛡️ **Iris 光影白名单检测**：客户端通过反射探测已启用的 Iris 光影包并上报，服务端校验白名单；非允许光影将收到告警或被强制踢出，确保夜间和停电模式的公平性。
- 🔒 **ModMenu 访问门控（`menugate`）**：支持服务端 OP4 控制台维护可访问 ModMenu 配置界面的白名单玩家，彻底防止普通玩家越权查看或篡改服务端对局参数。
- 🔄 **角色安全转职策略（ForcedRandomRoleChangePolicy）**：转职时全面执行事务化清理（旧状态、CCA 组件、药水效果与计分板），彻底防止非法转职导致的逻辑崩坏与状态残留。

---

## ModMenu 可视化配置中心

模组提供了基于 ModMenu 的全中文交互式 GUI 配置界面（按 `Esc -> 选项 -> 模组 -> HabiTrain Core -> 配置` 打开），共划分为 10 大核心管理页面：

```text
┌─────────────────────────────────────────────────────────────┐
│                 哈比列车核心配置中心 (ModMenu)                │
├───────────────────┬─────────────────────────────────────────┤
│ 1. 局内平衡配置    │ 警长人数比例、小刀耐久开关、临时电力价格等 │
│ 2. 局内环境配置    │ 对局内天气（晴/雨/雷）、时间锁定与日夜交替  │
│ 3. 局内小游戏配置  │ 原版与 DLC 任务启用状态、各任务在各图开关 │
│ 4. 模式任务配置    │ 各游戏模式的任务池权重与生成概率        │
│ 5. 模式职业配置    │ 角色启用状态、覆盖补丁与参数配置        │
│ 6. 角色扩展管理    │ v2 Provider/Entry 状态查看、热开关、裁决│
│ 7. 大厅环境配置    │ 等待大厅天气、时间锁定与自动语音组开关  │
│ 8. 光影检测配置    │ 光影白名单开关、允许的光影包列表维护    │
│ 9. 投票与抽图配置  │ 模式/地图投票时长、按人数动态抽图池参数 │
│ 10. 其他通用配置   │ 调试日志、网络同步与全局安全参数        │
└───────────────────┴─────────────────────────────────────────┘
```

> **提示**：大厅阶段修改配置会立即生效；对局进行中修改时会自动生成待定快照（Pending Snapshot），并在下一局开始时平滑生效，绝不破坏当前对局。

---

## 开发者 API 体系总览

稳定公开 API 均位于 `com.habitrain.core.api`。

### API 模块索引

| 子系统 | 核心包路径 | 主要类 / 接口 | 功能定位 |
|---|---|---|---|
| **角色扩展 v2** | `com.habitrain.core.api.role.v2.*` | `RoleExtensionEntrypoint`<br>`RoleExtensionRegistrar`<br>`RoleDefinition`<br>`RolePatch`<br>`RoleCatalogApi`<br>`RoleChangeApi`<br>`RoleStateApi`<br>`RoleActionApi` | 完整的声明式角色注册、可撤销补丁、受管 Hooks、受管状态、受管网络动作、客户端 HUD 与目录转职体系 |
| **角色覆盖 v1** | `com.habitrain.core.api.role.*` | `RoleOverrideApi`<br>`ModifyRoleDefinition`<br>`ReplaceRoleDefinition` | 兼容稳定的角色 REPLACE / MODIFY 接口 |
| **任务系统** | `com.habitrain.core.api.*` | `TaskRegistry`<br>`TaskDefinition`<br>`TaskInstance`<br>`TaskCategory` | 声明式任务注册、分类、直觉透视、进度监控与回调 |
| **游戏模式** | `com.habitrain.core.api.*` | `GameModeRegistry`<br>`GameMode`<br>`WinResult` | 自定义游戏模式注册、对局生命周期与胜负判定拦截 |
| **投票系统** | `com.habitrain.core.api.*` | `OptionVoteApi`<br>`ModeMapVoteApi`<br>`ModeMapVoteConfig` | 通用选项投票与双阶段模式/地图投票系统 |
| **道具管理** | `com.habitrain.core.api.*` | `ItemReclaimHelper` | 任务临时道具打标、跟踪与安全回收 |

---

### 快速代码示例

#### 1. 注册自定义任务
```java
TaskRegistry.register("my_mod", "repair_radio", builder -> builder
        .displayName("维修无线电台")
        .category(TaskCategory.ALL)
        .gameMode("sre:base")
        .weight(1.2F)
        .timeLimit(60)
        .instinctColor(80, 180, 255, 200) // ARGB 直觉高亮色
        .onAssign((player, task) -> task.setMaxProgress(3))
        .completionChecker((player, task) -> task.getProgress() >= task.getMaxProgress())
        .onComplete((player, task) -> player.sendSystemMessage(Component.literal("§a电台维修完成！")))
        .onFail((player, task) -> player.sendSystemMessage(Component.literal("§c维修超时，任务失败！"))));
```

#### 2. 使用角色扩展 API v2 注册全新角色
在 `fabric.mod.json` 中声明 Entrypoint：
```json
{
  "entrypoints": {
    "habitrain:role_extensions": ["com.example.mod.MyRoleProvider"],
    "habitrain:role_client_extensions": ["com.example.mod.client.MyRoleClientProvider"]
  }
}
```
编写通用 Provider：
```java
public final class MyRoleProvider implements RoleExtensionEntrypoint {
    public static final RoleKey MEDIC = RoleKey.of("my_mod", "medic");

    @Override
    public void register(RoleExtensionRegistrar registrar) {
        registrar.add(RoleDefinition.builder(MEDIC)
                .presentation(RolePresentation.builder()
                        .color(0xFF55FF55)
                        .nameKey("role.my_mod.medic")
                        .descriptionKey("role.my_mod.medic.desc")
                        .build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).needPlayerCount(6).build())
                .compatibility(RoleCompatibilityProfile.builder().canBeRandomed().build())
                .maxSprintTime(20)
                .build());
    }
}
```

---

## 完整命令速查表

| 命令 | 权限要求 | 说明 |
|---|---|---|
| `/instantgroup [range]` | OP 2 | 将指定方块半径（默认 128）内的所有玩家加入临时语音群组 |
| `/habi_api blackout` | OP 2 | 手动强制启动停电模式 |
| `/habi_api list` | OP 2 | 列出当前所有已注册的游戏模式 |
| `/habi_api vote start` | OP 2 | 手动开启双阶段模式→地图投票 |
| `/habi_api vote cancel` | OP 2 | 取消当前正在进行的投票 |
| `/habi_api vote status` | OP 2 / 所有人 | 查看当前投票阶段与剩余秒数 |
| `/habi_api mappool status` | OP 2 | 查看按人数抽图池的配置与状态 |
| `/habi_api repair <map>` | OP 2 | 进入创造模式维修指定地图并锁定该地图 |
| `/habi_api repair cancel` | OP 2 | 退出维修模式，恢复原本状态与模式 |
| `/habi_api repair list` | OP 2 | 列出当前所有维修人员及其锁定的地图 |
| `/habi_api repair unlock <map>` | OP 2 | 强制解锁指定地图 |
| `/habi_api repair add <player> <map>`| OP 2 | 强制指定某玩家进入某地图的维修模式 |
| `/habi_api repair remove <player>` | OP 2 | 强制某玩家退出维修模式 |
| `/habi_api menugate enable\|disable` | 控制台 (OP 4) | 开启/关闭 ModMenu 配置界面的访问门控 |
| `/habi_api menugate status\|list` | 控制台 (OP 4) | 查看 ModMenu 门控状态及授权名单 |
| `/habi_api menugate add\|remove <p>` | 控制台 (OP 4) | 将玩家加入/移除 ModMenu 门控允许名单 |
| `/habi_api greed_trade confirm\|cancel` | 所有人 | 贪婪匿名交易双确认的备用命令接口 |
| `/habitrain roleapi providers` | OP 2 | 查看所有已加载的角色扩展 Provider 状态 |
| `/habitrain roleapi list [filter]` | OP 2 | 列出有效/冲突/禁用/无效等状态的角色目录 |
| `/habitrain roleapi inspect <role>` | OP 2 | 深度检查指定角色的有效配置与合并结果 |
| `/habitrain roleapi trace <role> <field>`| OP 2 | 追踪指定角色某字段由哪些 Provider/Patch 修改 |
| `/habitrain roleapi aliases [role]` | OP 2 | 查询旧 ID 别名映射链 |
| `/habitrain roleapi snapshot` | OP 2 | 查看当前大厅/对局快照版本与哈希 |
| `/habitrain roleapi hooks <role>` | OP 2 | 查看指定角色挂载的所有受管 Hooks |
| `/habitrain roleapi actions [role]` | OP 2 | 查看注册的受管网络动作与调用统计 |
| `/habitrain roleapi capabilities` | OP 2 | 查看语音与聊天策略及适配器状态 |
| `/habitrain roleapi perf` | OP 2 | 查看受管事件分发耗时与性能指标 |
| `/habitrain roleapi state [p] [role]` | OP 2 | 查看玩家或角色的受管状态数据 |
| `/habitrain roleapi config status` | OP 2 | 查看角色扩展 v2 独立配置文件状态 |
| `/habitrain roleapi config set ...` | OP 4 | 热启用/禁用 Provider、Entry 或全局 Hooks |
| `/habitrain roleapi config winner ...`| OP 4 | 手动设置字段补丁冲突的获胜条目 |
| `/habitrain roleapi manifest` | OP 2 | 生成当前所有角色条目的只读清单摘要 |

---

## 项目结构

```text
src/main/java/com/habitrain/core/
├── api/                   # 公开 API 根目录（稳定契约）
│   ├── role/              # 角色覆盖 v1 API
│   └── role/v2/           # 角色扩展 v2 完整 API 体系
├── task/                  # 核心任务调度与进度追踪引擎
├── game/
│   ├── sre/               # SRE 原版桥接、投稿职业、七宗罪、七美德、环境与淘汰休息区
│   └── blackout/          # 停电模式专属玩法、发电机、电线、黑市与日常任务
├── role/                  # 角色扩展平台 v2 内部实现与引擎
├── network/               # S2C / C2S 自定义网络 Payload 与编解码
├── config/                # JSON 配置文件读写、同步与 ModMenu 门控
├── client/                # ModMenu 10大配置页、HUD、GUI、渲染与客户端 Mixin
└── betel/                 # 槟榔模组深度集成与成瘾系统
```

---

## 构建与交付

使用标准 Gradle 命令构建项目：

```powershell
./gradlew build
```

构建完成后，生成的标准交付 JAR 产物位于：
```text
build/release/habitrain_core-2.0.2-restored.jar
```

按工作区统一规范，验证完成后将该 JAR 复制到同级目录 `../临时/` 即可。

---

## 文档索引

- 📘 **[使用教程](docs/使用教程.md)**：面向玩家、服主与模组开发者的全方位实战指南（涵盖玩法机制、运维配置、任务/模式开发与角色扩展实战）。
- 📑 **[API 参考手册](docs/API参考手册.md)**：公开 API 接口契约、方法签名与类族索引速查。
- 📕 **[角色扩展 API v2 使用教程](docs/角色扩展API-v2使用教程.md)**：角色扩展平台 v2 深度开发指南（ADD/MODIFY/REPLACE、Hooks、State、Action、Client HUD 与诊断）。

---

## 许可证

本项目基于 **GNU General Public License v3.0 (GPLv3)** 开源，详见 [LICENSE](LICENSE)。
