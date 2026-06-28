# 🚂 哈比列车任务API (HabiTrain TaskAPI)

**为星穹列车 (StarRailExpress) 提供可扩展的任务系统 API**

[![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-%23dbaf48)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/MC-1.21.1-success)](https://minecraft.net/)
[![星穹列车](https://img.shields.io/badge/依赖-StarRailExpress-blue)](https://www.mcmod.cn/class/13890.html)

---

## 📋 简介

哈比列车任务API 完全接管星穹列车 (SRE) 的原版任务系统，提供：

- **任务注册 API** — DLC 模组通过 `HabiTaskRegistry` 注册自定义任务，无需修改 SRE 本体
- **自动平衡系统** — DLC 任务和原版任务自动平衡出现概率，加新 DLC 无需配置
- **可视化配置 GUI** — 通过 ModMenu 进行游戏内任务管理（启用/禁用、颜色、奖励、地图过滤等）
- **方块透视高亮** — 自定义任务方块的透视高亮边框，颜色和粗细可配置
- **奖励系统** — 每个任务可独立配置金币/情绪奖励
- **多模式支持** — 谋杀模式 (TMM)、修机模式 (Repair)、通用任务、自定义任务

---

## ⚙️ 安装要求

| 项目 | 版本 |
|------|------|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥0.19.2 |
| Fabric API | ≥0.116.2 |
| 星穹列车 (SRE) | 最新版（需支持自定义任务类型） |
| ModMenu | ≥11.0（可选，提供配置界面） |

---

## 🎮 ModMenu 配置界面

> 需要安装 ModMenu 才能在游戏内访问配置

### 主界面

通过 ModMenu → 哈比列车任务API → 配置 进入，以 2×2 卡片网格展示四个模式：

| 模式 | 说明 |
|------|------|
| 🔪 谋杀模式 | 经典列车谋杀案模式下的任务 |
| 🔧 修机模式 | 修复逃脱模式下的任务 |
| ⭐ 通用任务 | 所有模式均可使用的任务 |
| 📦 自定义任务 | 由 DLC 模组自行控制可用性 |

每个卡片显示：任务数量、启用比例、外部任务数。

### 任务列表

点击任一模式卡片进入任务列表：
- **搜索框** — 按任务名/ID/模组名过滤
- **启用开关** — 每行左侧快速启用/禁用任务
- **编辑按钮** — 点击进入详细配置
- **悬停信息** — 显示任务完整 ID、颜色等信息

### 任务详细编辑

点击某任务进入详细配置界面：

| 设置项 | 说明 |
|--------|------|
| **任务状态** | 启用/禁用此任务 |
| **透视颜色** | 20 色循环切换，修改任务方块高亮颜色 |
| **描边粗细** | 1.0 ~ 10.0，步进 0.5，控制高亮边框粗细 |
| **金币奖励** | 任务完成时额外发放的金币，留空=系统默认 |
| **情绪奖励** | 任务完成时额外增加的情绪值，留空=系统默认 |
| **刷新权重** | 该任务在随机池中的权重，留空=任务定义值 |
| **地图过滤模式** | 全部地图 / 白名单 / 黑名单 |
| **地图列表** | 逗号分隔地图名，配合过滤模式使用 |

### 全局设置

- **DLC 目标占比** — 唯一全局参数（默认 50%），控制 DLC 任务在全部任务中的出现频率
  - 范围 10%~80%，步进 5%
  - 系统根据注册数量自动计算权重乘数
  - 新增 DLC 模组后无需调整，自动适应

---

## 📦 配置文件

配置文件位于 `config/habitrain_taskapi.json`，结构如下：

```json
{
  "global": {
    "dlcProbabilityTarget": 0.5
  },
  "tasks": {
    "habitrain_taskapi:sleep": {
      "enabled": true,
      "instinctColor": -12517376,
      "outlineWidth": 4.0
    },
    "your_mod:custom_task": {
      "enabled": true,
      "enabledMaps": ["map1", "map2"],
      "mapFilterMode": 1,
      "goldReward": 5,
      "emotionReward": 0.2,
      "refreshWeight": 1.5
    }
  }
}
```

每个任务的配置项：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | bool | `true` | 是否启用 |
| `mapFilterMode` | int | `0` | 0=全局 / 1=白名单 / 2=黑名单 |
| `enabledMaps` | string[] | `[]` | 地图名列表 |
| `instinctColor` | int | `200,200,200` | 透视颜色 ARGB 值 |
| `outlineWidth` | float | `4.0` |透视描边粗细 |
| `goldReward` | int | `-1` | 金币奖励（-1=默认） |
| `emotionReward` | float | `-1.0` | 情绪奖励（-1=默认） |
| `refreshWeight` | float | `-1.0` | 刷新权重（-1=默认） |

---

## 🧩 为 DLC 模组开发者

### 添加依赖

在你的 `build.gradle` 中添加：

```groovy
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/your-org/habitrain_taskapi")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    modImplementation "com.habitrain:habitrain_taskapi:1.0.0"
}
```

### 注册一个简单任务

```java
public class MyMod implements ModInitializer {
    public static final String MOD_ID = "my_mod";

    @Override
    public void onInitialize() {
        HabiTaskRegistry.register(MOD_ID, "pet_cat", builder -> builder
            .displayName("撸猫")
            .category(HabiTaskCategory.ALL)
            .weight(1.0f)
            .blockTypeId(12)
            .instinctColor(new Color(255, 200, 100, 180))
        );
    }
}
```

### 注册带回调的复杂任务

```java
HabiTaskRegistry.register(MOD_ID, "collect_wood", builder -> builder
    .displayName("收集木头")
    .category(HabiTaskCategory.MURDER)
    .weight(2.0f)
    .blockTypeId(13)
    .instinctColor(new Color(139, 69, 19, 180))
    .canDirectlyWin(false)
    // 指定任务方块（MapScanner 高亮用）
    .scanBlockIds("minecraft:oak_log", "minecraft:birch_log")
    // 分配时给玩家道具
    .onAssign((player, instance) -> {
        player.addItem(new ItemStack(Items.STONE_AXE));
    })
    // 完成时发放自定义奖励
    .onComplete((player, instance) -> {
        player.addItem(new ItemStack(Items.APPLE, 3));
    })
    // 自定义进度检查
    .completionChecker((player, instance) -> {
        return instance.getProgress() >= 5;
    })
    .onTick((player, instance) -> {
        // 每 tick 检查逻辑
    })
    // 分配条件：仅白天可分配
    .canAssign((player, instance) -> player.level().isDay())
);
```

### API 速查

| 类/接口 | 用途 |
|---------|------|
| `HabiTaskRegistry` | 任务注册中心，注册/查询所有任务 |
| `HabiTaskDefinition` | 任务定义（名称、分类、权重、回调等） |
| `HabiTaskInstance` | 任务运行时实例（进度、完成状态等） |
| `HabiTaskCategory` | 任务分类枚举：`MURDER` / `REPAIR` / `ALL` / `CUSTOM` |

---

## 🔧 自动平衡系统（核心机制）

系统每次构建任务池时**实时计算**权重乘数，无需任何手动配置：

```
autoBoost = target / (1 - target) × 可用原版任务数 / 可用DLC任务数
```

**工作流程：**
1. 原版任务加入池（含情绪/次数权重调整）
2. 统计实际进入池的原版任务数
3. 统计可用的 DLC 任务数（经地图/分类/canAssign 过滤后）
4. 自动计算 `autoBoost` 使 DLC 集体概率 = 目标比例
5. DLC 任务以 ×autoBoost 的权重加入同一池
6. 加权随机选择

**特性：**
- ✅ 加新 DLC 模组 → 自动适应，无需修改配置
- ✅ 地图禁用某些任务 → 自动排除，不影响平衡
- ✅ 原版任务情绪/次数权重 → 不受影响
- ✅ 多于一个可用 DLC → 只有玩家**没有**活跃 DLC 任务时才进入池（防止覆盖）

---

## 🌐 网络同步

| 同步方向 | 包 | 说明 |
|---------|-----|------|
| 服务端 → 客户端 | `TaskConfigSyncPayload` | 玩家加入时同步完整任务配置 |
| 服务端 → 客户端 | `ActiveCustomTaskPayload` | 同步当前活跃 DLC 任务（用于透视渲染） |
| 客户端 → 服务端 | `ConfigUpdateC2SPayload` | OP 通过 ModMenu 修改配置后同步（服务端校验 OP 权限） |

---

## 📄 许可证

本模板基于 CC0 许可证发布，欢迎学习和在您自己的项目中使用。

---

## 🙏 致谢

- 星穹列车 (StarRailExpress) 模组 — 本模组的运行基础
- NoellesRoles — MapScanner 和任务方块渲染支持
- ModMenu — 模组配置界面 API
