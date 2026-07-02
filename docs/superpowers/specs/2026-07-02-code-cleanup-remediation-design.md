# 代码清理修复设计文档

日期: 2026-07-02

## 范围

针对 HabiTrain Core 模组代码库一次全面的代码审查发现的问题进行分阶段修复。

## P0 — Bugfix (功能正确性)

| ID | 问题 | 文件 | 处理方法 |
|----|------|------|---------|
| B1 | TACZWeaponBridge 扣款先于库存检查 | `TACZWeaponBridge.java:102,143` | 先 `add()` 检查返回值，成功再扣款 |
| B2 | `/habi_api` 双重注册权限覆盖 | `HabiTrainCore.java:134-162` | 合并为单根节点，子命令分权限 |
| B4 | GenerateTaskMixin 用 @Overwrite | `GenerateTaskMixin.java:47` | 改为 `@Inject(cancellable=true)` |
| B5 | ByteBuf 用平台默认字符集 | 5个网络Payload文件 | `getBytes(UTF_8)` / `new String(bytes, UTF_8)` |
| B6 | 网络解码无长度限制 | `ShaderInfoPayload` 等 | `Math.min(len, MAX_LENGTH)` 截断 |

## P1 — 架构加固

| ID | 问题 | 涉及文件 | 处理方法 |
|----|------|---------|---------|
| A1 | Blackout 系统全静态 | `BlackoutRoleManager`, `BlackoutTimerSystem`, `TACZWeaponBridge` | 改为实例字段，GameMode 持有实例 |
| A2 | API 层泄露实现 | `api/TaskInstance.java:3-5,15` | 剥离对 `TaskManager`/SRE 内部类的直接依赖 |
| A3 | java.awt.Color 用于服务端 | `TaskDefinition`, `TaskConfigEntry`, Blackout任务 | 替换为 `int ARGB` / `FastColor.ARGB32` |
| A4 | Engine 回退不通知 GameMode | `Engine.java:49-53` | 补充 `gameMode.onTaskAssign()` 调用 |
| A5 | suppressCallback 无 try-finally | `ConfigManager.java:380-393` | `try { ... } finally { suppressCallback = false; }` |
| A6 | 单例无 volatile | 4个单例类 | 加 `volatile` + 双检锁或直接初始化 |
| A7 | 补注解 | 多处 | `@Nullable`, `@Unique`, `@Deprecated(forRemoval=true)` |

## P2 — 代码清理

| 类别 | 处理方法 |
|------|---------|
| i18n | 所有 `Component.literal("中文")` → `Component.translatable()`，补 lang 文件 |
| 魔法数字 | `blockTypeId`, 角色类型, 键码 抽为命名常量 |
| 代码重复 | 颜色数组共享、broadcast 合并、布局计算复用 |
| 死代码 | 删除 `releaseAll()`、未播放音效、过期翻译、`calcDlcPercent` |
| 其他 | 补缺失音效文件、改离线按钮为标准 Widget、规范 Mixin 命名 |

## 执行顺序

P0 → P1 → P2，每阶段完成后通过 `./gradlew build` 验证编译通过。
