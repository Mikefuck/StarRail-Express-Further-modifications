# AGENTS.md — 哈比列车核心 (HabiTrain Core)

## 项目概要

Fabric 1.21.1 模组，为星穹列车 (SRE) 提供可扩展的任务系统 API、自定义游戏模式 (停电模式) 和增强功能。

## 构建 & 运行

```powershell
./gradlew build          # 完整构建（assemble 会触发 copyReleaseJar）
./gradlew runClient      # 启动开发客户端
```

- Java 21（`options.release = 21`），Fabric Loom 1.17.13；字节码目标为 21
- `libs/` 目录存放固定的本地 JAR 依赖（SRE、TACZ、voicechat、betel-nut-mod 等）；当前仓库已跟踪这些构建输入，不可随意删除或替换
- `copyReleaseJar` 任务将产物暂存到 `build/release/`，`assemble` 已依赖它；完整验证后按上级工作区约定复制到 `../临时/`
- `src/test/java` 包含 API 值对象与角色覆盖 API 的 JUnit 5 测试；验证仍以 `./gradlew build` 和必要的游戏内运行共同完成
- README.md、`docs/API参考手册.md` 和 `docs/使用教程.md` 是当前文档入口

## 重要架构

- **一个 mod ID**: `habitrain_core`。`assets/habitrain_taskapi/` 仅含 lang/icon 文件，历史遗留。
- **包结构**: `com.habitrain.core.api/`（公开 API）→ `task/`（引擎）→ `game/sre|blackout/`（模式实现）→ `network/`（网络同步）→ `config/`（JSON 配置）→ `client/`（GUI + 客户端 mixin）→ `betel/`（槟榔系统）
- **入口点**: `HabiTrainCore` (main) → `HabiTrainCoreClient` (client) → `ModMenuIntegration` (modmenu)
- **API 类名已重命名**: `HabiTaskRegistry` → `TaskRegistry`，`HabiTaskDefinition` → `TaskDefinition`，`HabiTaskInstance` → `TaskInstance`，`HabiTaskCategory` → `TaskCategory`
- **GameModeRegistry**: 注册/管理游戏模式 (SRE谋杀/修机/停电)
- **TaskRegistry**: DLC 模组通过此 API 注册自定义任务（`builder` 模式）
- **ConfigManager**: JSON 文件 `config/habitrain_core.json`，配置变更自动保存
- **颜色格式**: API 使用 `int ARGB`（已从 `java.awt.Color` 重构），DLC 可使用 `instinctColor(r, g, b, a)` 辅助方法
- **同步机制**: 服务端启动/玩家加入时通过自定义 payload 同步配置 → 客户端

## 命令

- `/instantgroup [range]` — OP 将范围内玩家加入临时语音群组（需 voicechat）
- `/habi_api blackout` — OP 手动启动停电模式
- `/habi_api list` — OP 列出已注册模式
- `/habi_api buy_gun` — 玩家购买沙漠之鹰（停电模式）
- `/habi_api buy_ammo` — 玩家购买弹药（停电模式）

## 关键约定

- Mixin 包: `game.sre.mixin` (服务端) / `client.mixin` (客户端)
- 网络 payload 用 Fabric API `CustomPacketPayload` + `StreamCodec` 模式，UTF-8 charset
- Iris 光影检测通过反射，无编译期依赖；客户端轮询上报，服务端白名单踢出
- 槟榔模组成瘾系统被强制开启 (`initBetelSystem` 覆盖配置)
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
| `BlackoutTimerPayload` | S2C | 停电模式倒计时同步 |
| `BlackoutAnnouncePayload` | S2C | 开局报幕（角色/目标信息） |
| `FullConfigSyncPayload` | S2C | 同步完整服务端配置 |
| `GameEndTransitionPayload` | S2C | 同步对局结束过渡与 MVP 数据 |
| `OptionVotePayload` / `OptionVoteCastPayload` | S2C / C2S | 通用选项投票 |
| `EliminatedRestPromptPayload` / `EliminatedRestTogglePayload` | S2C / C2S | 淘汰玩家休息区状态与切换 |

## 相关项目路径

- 哈比列车附属mod（本项目，可修改）: `D:\Backup\mc mod\哈比列车api`
- 哈比列车槟榔任务mod（可修改）: `D:\Backup\mc mod\槟榔`
- 哈比列车 DLC（仅参照，不可修改）: `D:\Backup\mc mod\哈比列车dlc\StarRailExpress-master`
