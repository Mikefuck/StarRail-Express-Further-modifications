# HabiTrain Core 全量质量审查设计

日期: 2026-07-09  
项目: `哈比列车api`（mod id: `habitrain_core`, Fabric 1.21.1）  
状态: 已确认（brainstorming §1–§3 + 方案 A）

---

## 1. 背景与目标

对 api mod 做系统性代码质量审查，覆盖四类维度：

1. **性能** — 热路径、O(n²)、每 tick 扫描、广播频率、缓存失效  
2. **死逻辑/死代码** — 不可达代码、永不调用方法、无发送 payload、过期分支  
3. **标识不清楚** — 命名、布尔/枚举语义、魔法数字、日志/注释误导  
4. **耦合/架构边界** — 静态上帝类、跨包直连、API 泄露实现、职责纠缠  

### 为何现在做

- 库约 159 Java 文件 / ~1.9 万行，无测试，验证靠 build + 游戏内  
- 2026-07-07～09 连续 blackout 大改 + 多轮 audit 修复，working tree 仍有 WIP  
- 历史已有 07-02 cleanup、07-07 perf refactor、07-08/09 blackout 审核等文档，未完全闭环  
- 需要一次全量穷尽审查，产出可执行报告 + 分批修复计划  

### 已确认决策

| 项 | 选择 |
|----|------|
| 产出 | 报告 + 修复计划（不边审边改） |
| 范围 | 全量代码库 |
| 维度 | 性能 + 死逻辑 + 标识 + 耦合（全选） |
| 深度 | 穷尽式审查 |
| 基线 | HEAD 全量 + WIP 对照 |
| 历史 | 对账 + 新发现 |
| 成功标准 | 完整可执行报告 |
| 执行方案 | 方案 A：子系统流水线穷尽，嵌入历史对账与四维标签 |

本阶段**不改业务代码**。

---

## 2. 方案选择

**采用方案 A（子系统流水线穷尽）：** 按子系统切成 10 个固定切片，每切片用同一套四维清单穷尽读完，再合并去重；历史对账作为独立章节；WIP 单独对照。

**不采用：**

- 方案 B（维度横切）— 业务上下文碎片化，对 blackout 强业务子系统不友好  
- 方案 C 单独作主流程 — 穷尽会缩水（其对账步骤并入切片 9）

---

## 3. 范围与基线

### 3.1 范围

- **包含：** `src/main/java/**` 全部（api / task / game / network / config / client / betel / misc / util）、相关 mixin JSON  
- **按需：** resources（lang/id 漂移）、`docs/` 与 `AGENTS.md`（文档漂移）  
- **排除：** `D:\Backup\mc mod\backup\`（绝对禁止）、DLC 源码修改（只读参照）、纯风格偏好、无行为影响的「可以再拆」  

### 3.2 基线

- **主基线：** git HEAD（设计确认时为 `3e44f72`；执行时以当时 HEAD 为准）  
- **对照：** working tree WIP（含 `BlackoutDeathHandler`、杀手雇佣等）  
- **历史源：** 07-02 cleanup design、07-07 perf plan/spec、07-08 blackout overhaul、07-08 20-issue 修复记忆、07-09 blackout 审核结论  

### 3.3 交付物

| 交付物 | 路径 | 阶段 |
|--------|------|------|
| 审查设计（本文） | `docs/superpowers/specs/2026-07-09-habitrain-core-quality-audit-design.md` | 已写入 |
| 审查报告 | `docs/superpowers/specs/2026-07-09-habitrain-core-quality-audit-report.md` | 执行审查后 |
| 分批修复计划 | `docs/superpowers/plans/2026-07-09-habitrain-core-quality-remediation.md` | writing-plans 后 |

---

## 4. 审查切片

| # | 切片 | 主要路径 | 四维重点 |
|---|------|----------|----------|
| 1 | 生命周期与静态状态 | `HabiTrainCore`, `GameModeRegistry`, `ModTickHandler`, 各 manager clear/reset | 耦合、漏清理 |
| 2 | Blackout 核心玩法 | `BlackoutMode`, Role/Victory/Timer/Vote/Hire/Death/Shop | 全四维，S0 优先 |
| 3 | 任务引擎 | `TaskManager`, `GenerateTaskMixin`, `TaskPoolBuilder`, `blackout/task/*` | 性能、死逻辑、重复 |
| 4 | Tick 预算全景 | 全部 `END_SERVER_TICK` / per-player / client tick | 性能主战场 |
| 5 | 网络与权限 | `network/*`, C2S 接收器, broadcast | 广播风暴、权限、死 payload |
| 6 | SRE 桥接与 mixin | `game/sre/*`, `game/sre/mixin/*`, `SREBlackoutGameMode` | 耦合、模式外误触发 |
| 7 | Client GUI/渲染 | `client/gui/*`, `client/mixin/*` | 性能、死 UI、标识 |
| 8 | Config / Betel / 杂项 | `config/*`, `betel/*`, `misc/*` | 耦合、强制配置副作用 |
| 9 | 历史清单对账 | 既有 specs/plans + 审核结论 | 已修/未修/回归 |
| 10 | HEAD vs WIP | `git diff` + 未跟踪文件 | 设计漂移、新风险 |

切片 1–8 可并行深读；9–10 在合成阶段串行完成。

---

## 5. 证据标准与问题格式

### 5.1 证据标准

每条问题必须有：文件路径 + 符号/方法名（尽量行号）+ 成立理由。禁止仅凭「文件大/乱」定级。

| 维度 | 最低证据 |
|------|----------|
| 性能 | 触发频率 × 工作量（玩家数、实体扫描、反射、全员广播） |
| 死逻辑 | 0 调用 / 只自引用 / 空方法体 / payload 只 register 不 send / 分支恒定 |
| 标识 | 易误解名称 + 真实语义；或命名空间/魔法数字对照 |
| 耦合 | 依赖方向、静态全局状态、API→实现引用 |

**排除：** 纯风格；无痛点的「可再拆」；历史文档笔误且代码已正确（只进对账表）。

### 5.2 问题条目模板

```text
### [S?][维度] ID: 简短标题
- 位置: path/File.java:method / :line
- 现状: …
- 证据: …
- 影响: …
- 建议方向: …（意图级，非完整补丁）
- 基线: HEAD | WIP | 两者
- 标签: needs-ingame? needs-mike-decision?
```

### 5.3 严重度

- **S0** 崩溃 / 胜负错误 / 经济可刷 / 局间状态泄漏致坏档  
- **S1** 明显性能热点 / 可达死逻辑影响玩法 / 权限缺口  
- **S2** 耦合/命名实质阻碍维护或高回归风险  
- **S3** 文档漂移、低风险重复、纯清晰度  

### 5.4 执行方式

- 每切片：并行 agent 深读 → 结构化 findings  
- 合成：去重、S0/S1 二次核实、对账、WIP 对照  
- 无自动化测试：不声称运行时已验证；可疑项标 `needs-ingame`  

---

## 6. 审查报告结构

```markdown
# HabiTrain Core 质量审查报告 (2026-07-09)
## 0. 元信息
## 1. 执行摘要（计数、Top 风险、与历史关系）
## 2. 分切片发现（2.1–2.10，无问题写「本切片无达标 finding」）
## 3. 跨切片主题综合
### 3.1 Tick 预算总图
### 3.2 静态状态矩阵
### 3.3 注册面地图
### 3.4 重复逻辑簇
### 3.5 边界违规
## 4. 历史清单对账表
## 5. HEAD vs WIP 差异风险表
## 6. 修复分批建议（骨架）
## 7. 不在范围内 / 明确不修
## 附录（规模表、术语、命名空间对照）
```

---

## 7. 历史对账

### 7.1 表格式

`源文档+原ID | 原问题摘要 | 现状(已修/部分/未修/回归/作废) | 证据 | 是否并入本次(新ID)`

规则：未修/部分/回归必须并入报告；已修不重复开 finding（除非残留风险）。

### 7.2 对账源文档（至少）

- `docs/superpowers/specs/2026-07-02-code-cleanup-remediation-design.md`  
- `docs/superpowers/plans/2026-07-07-performance-refactor.md`（及对应 spec 若可读）  
- `docs/superpowers/specs/2026-07-08-blackout-mode-overhaul-design.md`  
- 20-issue 修复记录、07-09 blackout 审核结论  
- 若 `D:\Backup\mc mod\临时\` 下有 07-09 审核报告可引用（**禁止**访问 `backup\`）  

---

## 8. HEAD vs WIP 对照

### 8.1 表格式

`文件 | 变更摘要(行为) | 风险(新bug/设计漂移/正确修复/未完成) | 建议`

### 8.2 WIP 已知焦点

- `BlackoutDeathHandler`  
- 杀手可雇佣语义  
- `SREGameModeBase` voice STARTING 态  
- `BlackoutRoleManager` 扩展  
- `HabiTrainCore` 注册接线  

---

## 9. 修复分批原则

| 批次 | 目标 | 典型内容 |
|------|------|----------|
| R0 | 正确性闸门 | S0：胜负/死亡/雇佣/局间泄漏/权限 |
| R1 | Tick & 网络热点 | S1 性能 |
| R2 | 死代码与误导 API | 空 Handler、未用 payload、空 hooks |
| R3 | 边界与去重 | 静态收口、handler 模板、vote 统一 |
| R4 | 标识与文档 | 命名空间、AGENTS/README、魔法数 |

约束：

- 每批 `./gradlew clean build` + JAR → `D:\Backup\mc mod\临时\`  
- 不新增测试源集（除非另行决定）  
- 玩法数值默认不变；改规则标 `needs-mike-decision`  
- API 破坏性变更必须显式列出  
- 不写无意义注释；不碰 `backup\`  

详细步骤在审查报告完成后由 writing-plans 生成 remediation plan。

---

## 10. 成功标准

1. 10 切片均有已审记录  
2. 每条 finding 符合证据标准  
3. 历史对账覆盖约定源文档  
4. WIP 表覆盖 `git status` 全部相关路径  
5. 修复分批骨架可直接交给 writing-plans  

---

## 11. 明确不做

- 审查阶段不改业务代码、不 commit 修复  
- 不做实机压测（仅标注 `needs-ingame`）  
- 不审/不改 DLC 源码  
- 「类行数多」单独不构成缺陷  

---

## 12. 探索阶段热点线索（执行时须二次核实）

以下为只读探索候选，**不得直接当作最终 finding**，执行审查时必须二次核实：

**性能候选：**  
`BlackoutLookMyEyesTask` 每 tick 全玩家扫描；多个独立 `END_SERVER_TICK`；Timer 强制校准广播；Join 多 payload；`MapScannerMixin`；客户端 `CustomTaskBlockRendererMixin`。

**死逻辑候选：**  
空的 `BlackoutEatHandler` / `BlackoutDrinkHandler.register()`；空的 killer task complete hooks；`raed_book` 拼写；AGENTS.md 与 payload 表漂移。

**耦合候选：**  
Blackout 静态 manager 集群；`HabiTrainCore` 上帝初始化；Blackout → SRE CCA 直连；客户端 shop bootstrap。

**标识候选：**  
`habitrain` / `habitrains` / `habitrain_core` / `sre` 命名空间混用；Sheriff vs generic vote 命名不对称；Handler 名与 mixin 实现分裂。

**WIP 风险：**  
雇佣语义偏离 07-08 设计；`BlackoutDeathHandler` 与 eliminate 双路径。

**历史未闭环嫌疑：**  
07-07 perf 计划落地程度；07-02 P1 架构项；PRAY / 部分 canAssign 门控 follow-up。

---

## 13. 执行阶段步骤

### Phase 1 — 穷尽审查

1. 冻结基线：记录 HEAD hash + `git status` 快照  
2. 并行深读切片 1–8  
3. 串行切片 9（历史对账）与 10（HEAD vs WIP）  
4. 合成：去重、S0/S1 对抗核实、跨切片主题图  
5. 写入审查报告并 commit  

### Phase 2 — 修复计划

1. 基于报告第 6 章调用 writing-plans  
2. 生成 `docs/superpowers/plans/2026-07-09-habitrain-core-quality-remediation.md`  
3. 批准后再改代码  

### 审查阶段验证

- 每个 S0/S1：二次 grep/读码核实  
- 报告自检：无 TBD、无空切片、对账完整、WIP 全覆盖  
- 审查阶段不要求 `./gradlew build`（无代码改动）；修复阶段每批必须 build + 复制 JAR  
