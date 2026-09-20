# AGENTS.md — 哈比列车核心 (HabiTrain Core)

## 项目概要

Fabric 1.21.1 模组，为星穹列车 (SRE) 提供可扩展的任务系统 API、游戏模式桥接 和增强功能。

## 构建 & 运行

Windows / PowerShell（当前工作区；用仓库自带的 wrapper）：

```powershell
.\gradlew.bat build          # 完整构建（assemble 会触发 copyReleaseJar）
.\gradlew.bat runClient      # 启动开发客户端
```

交付构建（上级 AGENTS 要求）：

```powershell
.\gradlew.bat clean build
# 将 build/libs/habitrain_core-<version>.jar 复制到 D:\Backup\mc mod\临时\
```

- Java 21（`options.release = 21`），Fabric Loom 1.17.13；字节码目标为 21
- `libs/` 目录存放固定的本地 JAR 依赖（SRE、TACZ、voicechat 等）；当前仓库已跟踪这些构建输入，不可随意删除或替换
- `copyReleaseJar` 任务将产物暂存到 `build/release/`，`assemble` 已依赖它；完整验证后按上级工作区约定复制到 `临时/`
- `src/test/java` 包含 API 值对象与角色覆盖 API 的 JUnit 5 测试；验证以 `.\gradlew.bat build` 和必要的游戏内运行共同完成
- README.md、`docs/API参考手册.md` 和 `docs/使用教程.md` 是当前文档入口；移动场景相关另有 `docs/移动场景API使用教程.md` 与 `docs/大厅移动背景.md`

## 重要架构

- **一个 mod ID**: `habitrain_core`。资源根是 `src/main/resources/assets/habitrain_core/`；历史遗留的 `assets/habitrain_taskapi/` 目录**已不存在**（早期文档的误记）。
- **包结构**: `com.habitrain.core.api/`（公开 API）→ `task/`（引擎）→ `game/sre/`（模式实现）→ `network/`（网络同步）→ `config/`（JSON 配置）→ `client/`（GUI + 客户端 mixin）→ `betel/`（槟榔系统）
- **入口点**: `HabiTrainCore` (main) → `HabiTrainCoreClient` (client) → `ModMenuIntegration` (modmenu)
- **API 类名已重命名**: `HabiTaskRegistry` → `TaskRegistry`，`HabiTaskDefinition` → `TaskDefinition`，`HabiTaskInstance` → `TaskInstance`，`HabiTaskCategory` → `TaskCategory`
- **GameModeRegistry**: 注册/管理游戏模式 (SRE谋杀/修机等上游模式)
- **TaskRegistry**: DLC 模组通过此 API 注册自定义任务（`builder` 模式）
- **ConfigManager**: JSON 文件 `config/habitrain_core.json`，配置变更自动保存
- **颜色格式**: API 使用 `int ARGB`（已从 `java.awt.Color` 重构），DLC 可使用 `instinctColor(r, g, b, a)` 辅助方法
- **同步机制**: 服务端启动/玩家加入时通过自定义 payload 同步配置 → 客户端

## 命令

- `/instantgroup [range]` — OP 将范围内玩家加入临时语音群组（需 voicechat）
- `/habi_api list` — OP 列出已注册模式

## 关键约定

- Mixin 包: `game.sre.mixin` (服务端) / `client.mixin` (客户端)
- 网络 payload 用 Fabric API `CustomPacketPayload` + `StreamCodec` 模式，UTF-8 charset
- Iris 光影检测通过反射，无编译期依赖；客户端轮询上报，服务端白名单踢出
- ExtraSlotComponent 每玩家每 tick 调用 `serverTick()`
- task tick: `TaskInstance.tick(player)` → onTick → completion check → onComplete/fail

## 网络 payload

| 包 | 方向 | 说明 |
|----|------|------|
| `TaskConfigPayload` | S2C | 玩家加入时同步完整任务配置 |
| `ActiveTaskPayload` | S2C | 同步当前活跃 DLC 任务（用于透视渲染） |
| `ConfigUpdatePayload` | C2S | OP 通过 ModMenu 修改配置后同步（服务端校验 OP 权限） |
| `ShaderConfigPayload` | S2C | 同步光影白名单配置 |
| `ShaderInfoPayload` | C2S | 客户端上报当前使用的光影包名 |
| `FullConfigSyncPayload` | S2C | 同步完整服务端配置 |
| `GameEndTransitionPayload` | S2C | 同步对局结束过渡与 MVP 数据 |
| `OptionVotePayload` / `OptionVoteCastPayload` | S2C / C2S | 通用选项投票 |
| `EliminatedRestPromptPayload` / `EliminatedRestTogglePayload` | S2C / C2S | 淘汰玩家休息区状态与切换 |

## 相关项目路径

- 哈比列车 core/api（本项目，可修改）: `D:\Backup\mc mod\哈比列车api`
- 哈比列车功能补齐（抽奖，可修改）: `D:\Backup\mc mod\哈比列车抽奖补齐`
- 哈比列车更多职业（可修改）: `D:\Backup\mc mod\哈比列车更多职业`
- 哈比列车更多职业移植（可修改）: `D:\Backup\mc mod\哈比列车更多职业移植`
- 哈比列车 DLC（仅参照，不可修改）: `D:\Backup\mc mod\哈比列车dlc`
