# 哈比列车核心（HabiTrain Core）

面向 Minecraft 1.21.1 / Fabric 的 StarRailExpress（SRE）扩展核心，模组 ID 为 `habitrain_core`。它既提供任务、游戏模式、投票和角色扩展公开 API，也实现停电模式、维修模式、配置中心、网络同步、职业书与若干 SRE 行为增强。

当前模组版本为 `2.0.2`，角色扩展 API 协议版本为 `2.0`。角色扩展 v2 仍按 preview / experimental 管理；正式附属模组可继续使用兼容稳定的 v1 `RoleOverrideApi`，或在完成双端测试后采用能力更完整的 v2。

## 环境要求

| 项目 | 版本 |
|---|---|
| Java | 21 |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.18.2 或更高 |
| Fabric API | 0.116.13+1.21.1 |
| Fabric Loom | 1.17.13 |
| StarRailExpress | 4.3.0 |
| HabiTrain Core | 2.0.2 |

运行时硬依赖以 `src/main/resources/fabric.mod.json` 为准。开发构建还依赖项目 `libs/` 中固定的本地 JAR；不要随意替换或删除这些文件。

## 构建与交付

```powershell
./gradlew build
```

完整构建会编译主源码和测试、运行 JUnit、处理资源、remap JAR，并生成：

```text
build/release/habitrain_core-2.0.2-restored.jar
```

按工作区约定，验证成功后把该 JAR 复制到同级目录 `../临时/`。开发客户端可用 `./gradlew runClient` 启动。

## 公开 API 总览

稳定公开入口位于 `com.habitrain.core.api`。附属模组不要把 `task/`、`game/`、`network/`、`config/`、`client/` 或 `role/` 内部实现当成稳定 API。

| 子系统 | 主要入口 | 用途 |
|---|---|---|
| 任务 | `TaskRegistry`、`TaskDefinition`、`TaskInstance`、`TaskCategory` | 注册任务、管理进度与回调 |
| 游戏模式 | `GameModeRegistry`、`GameMode`、`WinResult` | 注册和驱动自定义模式 |
| 投票 | `OptionVoteApi`、`ModeMapVoteApi` | 通用选项投票和模式→地图投票 |
| 道具回收 | `ItemReclaimHelper` | 标记并回收任务临时物品 |
| 角色覆盖 v1 | `com.habitrain.core.api.role.RoleOverrideApi` | 稳定的角色 REPLACE / MODIFY 兼容接口 |
| 角色扩展 v2 | `com.habitrain.core.api.role.v2.*` | ADD / MODIFY / REPLACE / ALIAS、受管行为、状态、动作与客户端扩展 |

任务注册示例：

```java
TaskRegistry.register("my_mod", "pet_cat", builder -> builder
        .displayName("撸猫")
        .category(TaskCategory.ALL)
        .weight(1.0F)
        .blockTypeId(12)
        .instinctColor(255, 200, 100, 180));
```

## 角色扩展 v2 快速开始

在附属模组的 `fabric.mod.json` 中注册通用入口：

```json
{
  "entrypoints": {
    "habitrain:role_extensions": [
      "com.example.roles.ExampleRoleProvider"
    ]
  },
  "depends": {
    "minecraft": "~1.21.1",
    "fabricloader": ">=0.18.2",
    "fabric-api": "*",
    "habitrain_core": ">=2.0.2",
    "starrailexpress": "*"
  }
}
```

Provider 只能使用入口回调传入的 `RoleExtensionRegistrar` 注册。`RoleExtensionApi.instance().registrar()` 是只读兼容门面，所有写方法都会抛异常。

```java
public final class ExampleRoleProvider implements RoleExtensionEntrypoint {
    public static final RoleKey PLAGUE_DOCTOR =
            RoleKey.of("example", "plague_doctor");

    @Override
    public void register(RoleExtensionRegistrar registrar) {
        registrar.add(RoleDefinition.builder(PLAGUE_DOCTOR)
                .presentation(RolePresentation.builder()
                        .color(0xFF7BB661)
                        .nameKey("role.example.plague_doctor")
                        .descriptionKey("role.example.plague_doctor.description")
                        .build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .canBeRandomed()
                        .build())
                .maxSprintTime(20)
                .build());
    }
}
```

完整角色扩展文档见 [角色扩展 API v2 使用教程](docs/角色扩展API-v2使用教程.md)。

## v2 端口地图

| 阶段 | 端口 | 调用方式 |
|---|---|---|
| 注册 | `RoleExtensionEntrypoint` / `RoleExtensionRegistrar` | `habitrain:role_extensions` 入口中声明角色、补丁、hooks、state、action、voice/chat |
| 客户端注册 | `RoleClientExtensionEntrypoint` / `RoleClientExtensionRegistrar` | `habitrain:role_client_extensions` 入口中声明 HUD、直觉、皮肤、名称渲染、屏幕 |
| 查询 | `RoleCatalogApi` | 查找、规范化、过滤和恢复有效角色 |
| 转职 | `RoleChangeApi` | 事务化分配、转换、移除并保留历史 |
| 状态 | `RoleStateApi` | 通过注册所得 `RoleStateKey<T>` 读写、重置状态 |
| 动作 | `RoleActionApi` / `RoleActionClientApi` | 受管 C2S/S2C 动作、回调和服务器推送 |
| 能力 | `RoleCapabilityApi` | 查询并评估 voice/chat 策略和适配器状态 |
| 诊断 | `RoleDiagnostics` | 查询 provider、条目状态、alias 与快照 |

## 配置与生效时机

主配置文件：

```text
config/habitrain_core.json
```

角色扩展 v2 独立配置：

```text
config/habitrain_role_v2.json
```

v2 配置在大厅修改时立即编译为新的 lobby snapshot；对局进行中修改时生成 pending snapshot，并在下一局边界激活，不会中途替换当前对局的角色行为。服务端是配置权威，Mod Menu 或命令写入需要 OP 4 级权限。

## 项目结构

```text
src/main/java/com/habitrain/core/
├─ api/                    公开 API
│  └─ role/v2/            完整角色扩展 API
├─ task/                   任务引擎
├─ game/sre/               SRE 集成、职业与 Mixin 支撑
├─ game/blackout/          停电模式
├─ role/                   v2 角色平台内部实现
├─ network/                S2C/C2S payload
├─ config/                 JSON 配置和同步
├─ client/                 GUI、HUD、渲染与客户端 Mixin
└─ betel/                  槟榔系统集成
```

## 文档

- [使用教程](docs/使用教程.md)：依赖、任务、模式、投票、v1/v2 角色扩展入门
- [API 参考手册](docs/API参考手册.md)：公开 API 入口与关键契约速查
- [角色扩展 API v2 使用教程](docs/角色扩展API-v2使用教程.md)：所有角色扩展端口、示例、限制与诊断

## 验证边界

`./gradlew build` 能验证编译、资源、Mixin 配置、JUnit 和 remap，但不能替代游戏内联机验证。涉及角色分配、行为 hooks、动作握手、状态同步、Mod Menu、皮肤/HUD、语音聊天或胜利判定时，发布前还应执行客户端 + 专用服务器双端冒烟测试。

## 许可证

GPLv3，详见 [LICENSE](LICENSE)。
