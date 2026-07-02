# AGENTS.md — 哈比列车核心 (HabiTrain Core)

## 项目概要

Fabric 1.21.1 模组，为星穹列车 (SRE) 提供可扩展的任务系统 API 和自定义游戏模式 (停电模式)。

## 构建 & 运行

```powershell
./gradlew build          # 完整构建
./gradlew runClient      # 启动开发客户端
```

- Java 21, Fabric Loom 1.16.3
- `libs/` 目录存放本地 JAR 依赖（SRE, TACZ, voicechat, betel-nut-mod），需手动放置

## 重要架构

- **两个 mod ID**: `habitrain_core`（主模组） / `habitrain_taskapi`（仅 assets/lang 包，历史遗留）
- **包结构**: `com.habitrain.core.api/`（公开 API）→ `task/`（引擎）→ `game/sre|blackout/`（模式实现）→ `network/`（网络同步）→ `config/`（JSON 配置）→ `client/`（GUI + 客户端 mixin）
- **入口点**: `HabiTrainCore` (main) → `HabiTrainCoreClient` (client) → `ModMenuIntegration` (modmenu)
- **GameModeRegistry**: 注册/管理游戏模式 (SRE谋杀/修机/停电)
- **TaskRegistry**: DLC 模组通过此 API 注册自定义任务（`builder` 模式）
- **ConfigManager**: JSON 文件 `config/habitrain_taskapi.json`，配置变更自动保存
- **同步机制**: 服务端启动/玩家加入时通过自定义 payload 同步配置 → 客户端

## 命令

- `/instantgroup [range]` — 将范围内玩家加入临时语音群组（需 voicechat）
- `/habi_api blackout` — OP 手动启动停电模式
- `/habi_api list` — 列出已注册模式
- `/habi_api buy_gun|buy_ammo` — 玩家购买枪支弹药（停电模式）

## 关键约定

- Mixin 包: `game.sre.mixin` (服务端) / `client.mixin` (客户端)
- 网络 payload 用 Fabric API `ServerPlayNetworking`/`ClientPlayNetworking`，`PayloadType` 模式
- Iris 光影检测通过反射，无编译期依赖
- 槟榔模组成瘾系统被强制开启 (`initBetelSystem` 覆盖配置)
- 原自动录制回放逻辑已完全移除

## 测试

项目中无测试。手动验证通过运行客户端。
