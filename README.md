# 哈比列车核心（HabiTrain Core）

面向 Minecraft 1.21.1 / Fabric 的星穹列车（StarRailExpress）扩展核心，mod id 为 `habitrain_core`。项目提供任务、游戏模式、投票和角色覆盖公开 API，并实现停电模式、修机兼容、配置中心、网络同步及若干 SRE 行为增强。

## 环境要求

| 项目 | 当前版本 |
|---|---|
| Java | 21 |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.18.2 或更高 |
| Fabric API | 0.116.13+1.21.1 |
| Fabric Loom | 1.17.13 |
| Mod 版本 | 2.0.1 |

运行时还需要 `starrailexpress`、Mod Menu、`betel-nut-mod` 以及 `fabric.mod.json` 中声明的依赖。开发构建依赖项目 `libs/` 中固定的本地 JAR；不要随意替换或删除这些文件，否则可能改变映射和运行时行为。

## 构建与产物

```powershell
./gradlew build
```

完整构建会执行主源码/测试源码编译、JUnit 测试、资源处理、JAR remap，并通过 `copyReleaseJar` 生成：

```text
build/release/habitrain_core-2.0.1-restored.jar
```

按工作区交付约定，最终验证成功后还需把该 JAR 复制到同级目录 `../临时/`。开发客户端可用 `./gradlew runClient` 启动。

## 公开 API

公开入口位于 `com.habitrain.core.api`：

| API | 用途 |
|---|---|
| `TaskRegistry` / `TaskDefinition` / `TaskInstance` | 注册和运行自定义任务 |
| `TaskCategory` | 标准或自定义任务分类 |
| `GameModeRegistry` / `GameMode` / `WinResult` | 注册游戏模式和管理生命周期 |
| `OptionVoteApi` / `ModeMapVoteApi` | 通用投票及模式→地图两阶段投票 |
| `RoleOverrideApi` | 替换或修改 SRE 角色 |
| `ItemReclaimHelper` | 标记并回收任务临时道具 |

任务注册示例：

```java
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskRegistry;

TaskRegistry.register("my_mod", "pet_cat", builder -> builder
        .displayName("撸猫")
        .category(TaskCategory.ALL)
        .weight(1.0F)
        .blockTypeId(12)
        .instinctColor(255, 200, 100, 180));
```

注册必须发生在服务端启动后注册表冻结之前。完整契约、回调顺序、角色覆盖和端到端示例见 [API 参考手册](docs/API参考手册.md)。

## 配置与同步

主配置文件为：

```text
config/habitrain_core.json
```

配置包含任务、游戏模式、小游戏、模式/地图投票、环境、光影白名单和角色覆盖等分区。专用服务器由服务端配置权威控制；Mod Menu 写入需要 OP 4 级权限，并受菜单授权门控约束。

网络层使用 Fabric `CustomPacketPayload` + `StreamCodec`。公共初始化注册所有 payload 类型，客户端和服务端分别注册对应接收器；客户端与服务端应使用同版本 JAR。

## 项目结构

```text
src/main/java/com/habitrain/core/
├─ api/              公开 API
├─ task/             任务引擎
├─ game/sre/         SRE 集成、职业与 Mixin 支撑
├─ game/blackout/    停电模式
├─ network/          S2C/C2S payload
├─ config/           JSON 配置和同步
├─ client/           GUI、HUD、渲染与客户端 Mixin
└─ betel/            槟榔系统集成
```

更多资料：

- [使用教程](docs/使用教程.md)
- [API 参考手册](docs/API参考手册.md)
- [模组改动清单](docs/模组改动清单.md)

## 验证边界

`./gradlew build` 能验证编译、资源、Mixin 配置生成、JUnit 测试和 remap，但不能替代游戏内联机验证。涉及 SRE 对局流程、角色分配、Mod Menu、光影或语音聊天的改动，发布前仍应至少执行一次开发客户端/专用服务器冒烟测试。

## 许可证

MIT，详见 [LICENSE](LICENSE)。
