# 切片 S4 — config 子系统审计发现

审计日期：2026-07-09
范围：com.habitrain.core.config 包 8 个文件（ConfigManager / ConfigRepository / ConfigStore / ConfigSync / GameModeConfigScope / MinigameConfigEntry / MinigameEnforcement / TaskConfigEntry）
独立性说明：仅基于源码事实判断，未引用仓库内任何既有审计/计划/报告。

## 文件覆盖确认表

| 文件（相对 src/main/java） | 是否已通读 | 行数 |
|---|---|---|
| com/habitrain/core/config/ConfigManager.java | 是 | 205 |
| com/habitrain/core/config/ConfigRepository.java | 是 | 135 |
| com/habitrain/core/config/ConfigStore.java | 是 | 262 |
| com/habitrain/core/config/ConfigSync.java | 是 | 126 |
| com/habitrain/core/config/GameModeConfigScope.java | 是 | 54 |
| com/habitrain/core/config/MinigameConfigEntry.java | 是 | 91 |
| com/habitrain/core/config/MinigameEnforcement.java | 是 | 42 |
| com/habitrain/core/config/TaskConfigEntry.java | 是 | 105 |

跨包调用核查（用于确认本片死方法判断）已对全 src/main/java 做 grep：
- getEffectiveGoldReward / getEffectiveEmotionReward / getEffectiveRefreshWeight：全树无调用方。
- calculateCurrentBoost：全树无调用方。
- getGameModeConfig(String)：全树无调用方（仅 ConfigManager 转发，无人调转发方法）。
- disabledMaps：仅 TaskConfigEntry 内部读写，无任何过滤决策消费它。

---

## 发现清单（按严重度排序）

### S4-001 [死逻辑 / S2] TaskConfigEntry.getEffectiveGoldReward / getEffectiveEmotionReward / getEffectiveRefreshWeight 为死方法
- file: com/habitrain/core/config/TaskConfigEntry.java
- line: 86-96
- dimension: 死逻辑
- severity: S2
- evidence: 三个方法定义存在且签名为 `getEffectiveXxx(TaskDefinition def)`，但全 src/main/java grep 仅命中定义行，零调用方。其中 getEffectiveGoldReward 体 `goldReward >= 0 ? goldReward : -1`、getEffectiveEmotionReward 同构，refreshWeight 才回退 `def.getWeight()`。
- impact: 功能缺失/可维护性：这三个“effective 取值”语义的统一入口从未被消费，调用方（TaskManager、mixins 等）必然各自直接读 public 字段 `goldReward/emotionReward/refreshWeight` 再自行判负，绕过封装。意图的“配置覆盖 vs 回退默认值”统一逻辑实际未生效，未来加新字段易再次各自实现。
- direction: 确认外部消费路径（A2 跨包核对）后，要么删除这三个死方法，要么把现有直接读 public 字段的调用方迁到这三个方法，统一取值口径。

### S4-002 [死逻辑 / S2] @Deprecated disabledMaps 仍被 fromJson 解析并写入实例字段，但从不参与过滤决策，也不写入 toJson
- file: com/habitrain/core/config/TaskConfigEntry.java
- line: 22-23, 67-77
- dimension: 死逻辑
- severity: S2
- evidence: 字段 `@Deprecated(forRemoval=true) public List<String> disabledMaps`（22-23）。fromJson 读取 "disabledMaps"（67-76）填充该字段并打 warn，但 isAllowedOnMap 逻辑在 MinigameConfigEntry/TaskConfigEntry 中只看 enabledMaps + mapFilterMode；toJson（37-52）不写出 disabledMaps。grep 全树确认无任何过滤逻辑消费 disabledMaps。
- impact: 字段持续被反序列化加载进内存却永不使用，且每次加载旧配置打 warn 噪音；标记 forRemoval=true 但仍被解析，违背废弃语义，迁移引导仅靠 warn，无运行时行为变化，用户实际“禁用地图”配置静默失效。
- direction: 既然 forRemoval 且无消费，fromJson 阶段只打迁移 warn、不再填充字段；或彻底移除该字段与解析分支。

### S4-003 [死逻辑 / S2] ConfigStore.calculateCurrentBoost 为死方法
- file: com/habitrain/core/config/ConfigStore.java
- line: 259-261
- dimension: 死逻辑
- severity: S2
- evidence: 包级方法 `float calculateCurrentBoost(ConfigRepository repo)` 调 `TaskBalancer.calcBoost(repo.getDlcProbabilityTarget(), countDlcTasks(), countOriginalTasks())`，与 public getDlcWeightBoost 计算相同但去掉日志。全树 grep 仅命中定义行，零调用方。
- impact: 死代码；与 getDlcWeightBoost 形成重复实现，未来改 calcBoost 调用点易漏改这一份。
- direction: 删除该方法（无人引用）。

### S4-004 [耦合 / S2] client.gui 等跨包直接依赖 ConfigManager 单例具体类，无接口隔离
- file: com/habitrain/core/config/ConfigManager.java
- line: 33-42（getInstance）
- dimension: 耦合
- severity: S2
- evidence: ConfigManager 为静态单例（volatile INSTANCE + 双检锁 15-42）。grep 显示 client.gui.GlobalSettingsScreen / ShaderWhitelistScreen / config/*TabScreen / config/MinigameEditScreen / TaskSaveController，以及 client.mixin、client.cache、network、game.blackout、game.sre.mixin 等全部直接 `ConfigManager.getInstance().getXxx()`，直接耦合 ConfigManager 具体类与 config 包内部具体类型（TaskConfigEntry、MinigameConfigEntry 等 public 字段）。
- impact: API 泄露实现：单例扩散到所有客户端/网络/混入层，无法替换/mock 配置源；GUI 直接读具体类 public 字段，配置内部结构变更会波及整个客户端层。专属高优先检查点“config 是否被 client.gui 直接读具体类而非接口”确认成立。
- direction: 抽取只读配置查询接口，GUI/网络层依赖接口而非 ConfigManager 具体类；逐步收敛单例访问点。

### S4-005 [性能 / S2] ConfigStore.buildJsonRoot 在 save 全量序列化时遍历 TaskRegistry.getAll() 并为每个任务构造 JsonObject，每次配置变更即触发
- file: com/habitrain/core/config/ConfigStore.java
- line: 159-202（尤其 174-181 tasks 段、192-197 minigames 段）
- dimension: 性能
- severity: S2
- evidence: buildJsonRoot 对 `TaskRegistry.getAll()` 全量遍历，每个任务 `repo.getTaskConfig(fullId)` 哈希查表 + 构造完整 JsonObject（含 enabledMaps 数组），minigames 段同样 `safeGetAllMinigames()` 全量遍历。ConfigManager 几乎每个 setter（setTaskConfig / setDlcProbabilityTarget / setShaderWhitelistEnabled / setShaderWhitelist / setShaderWhitelistConfig / setSheriffCountDivisor / setMinigameConfig / setMinigameGlobalEnabled / setAllConfigs）末尾都 `store.save(repository)`，且 save 内部 buildJsonRoot(includeWeightBoost=true) 还额外调 getDlcWeightBoost（含 calcBoost + LOGGER.info）。
- impact: 非每 tick 热路径，但每次 GUI 改一个值就全量重写磁盘文件 + 全量 JSON 构造 + 全量 registry 遍历 + 一次 boost 计算 + info 日志；批量编辑（如 TaskTabScreen 循环 setTaskConfig）会放大为 N 次全量 save。S2（非热路径但可量化劣化）。
- direction: 区分“内存更新”与“落盘”，提供批量提交入口；save 支持增量/脏标记而非每次全量 build。

### S4-006 [标识 / S3] 多个 Logger 复用同名 "ConfigManager"，且中英混用
- file: com/habitrain/core/config/ConfigStore.java, ConfigSync.java, MinigameEnforcement.java, TaskConfigEntry.java
- line: ConfigStore.java:21, ConfigSync.java:11, MinigameEnforcement.java:12, TaskConfigEntry.java:17
- dimension: 标识
- severity: S3
- evidence: ConfigStore、ConfigSync、MinigameEnforcement 三者的 LoggerFactory.getLogger 名都传 "ConfigManager"；TaskConfigEntry 传 "TaskConfigEntry"。日志输出中英文混用（"全局设置: DLC目标占比=..." 与 "applyMinigameEnforcement 失败，SRE 可能未安装"）。
- impact: 日志归类困难：ConfigStore/ConfigSync/MinigameEnforcement 的日志全显示为 ConfigManager logger，排障时无法区分来源。
- direction: 各类用自身类名作为 logger name；日志语种统一。

### S4-007 [死逻辑 / S3] ConfigManager.getGameModeConfig(String) 转发方法全树无调用方，且底层 computeIfAbsent 有写副作用
- file: com/habitrain/core/config/ConfigManager.java（转发 83-84）/ ConfigRepository.java（42-44）
- line: ConfigManager.java:83-84, ConfigRepository.java:42-44
- dimension: 死逻辑
- severity: S3
- evidence: ConfigRepository.getGameModeConfig 用 `gameModeConfigs.computeIfAbsent(gameModeId, GameModeConfigScope::new)`，即“读即创建”。ConfigManager.getGameModeConfig 仅转发它，grep 全树仅命中本转发与底层定义，无任何外部调用方（实际只用 getAllGameModeConfigs 做序列化）。
- impact: 读方法隐含写副作用（无配置也建空 scope 并留存内存），且该入口当前无人用，是潜在陷阱；若未来被调用会在 repository 中累积空 GameModeConfigScope 且不落盘。
- direction: 删除未用的 getGameModeConfig 转发；底层 getGameModeConfig 改为无副作用 get，创建显式走 getOrCreate。

### S4-008 [标识 / S3] TaskConfigEntry.goldReward 默认 -1 用作“未设置”哨兵，与 enabled 默认 true 等布尔/数值语义混用魔法值
- file: com/habitrain/core/config/TaskConfigEntry.java（及 MinigameConfigEntry.java 同构）
- line: TaskConfigEntry.java:27-29, 48-50, 80-82, 86-96
- dimension: 标识
- severity: S3
- evidence: goldReward=-1 / emotionReward=-1f / refreshWeight=-1f 用 -1 作“未配置”哨兵，toJson 用 `>= 0` 判断是否写出，getEffectiveXxx 用 `>= 0` 判断回退。0 是合法奖励值却被 -1 哨兵语义挤占边界，魔法数字散落多处。
- impact: 0 奖励与“未配置”需靠 -1 区分，易误判；minigame 同构复制，规则重复，可维护性差。
- direction: 用 Optional/包装类型或显式 hasXxx 标志位替代 -1 哨兵；或集中“有效值”判定到一个常量方法。

### S4-009 [死逻辑 / S3] ConfigRepository.setTaskConfig 与 putTaskConfig、setMinigameConfig 与 putMinigameConfig 实现完全相同
- file: com/habitrain/core/config/ConfigRepository.java
- line: 22-28（setTaskConfig/putTaskConfig）, 93-99（setMinigameConfig/putMinigameConfig）
- dimension: 死逻辑
- severity: S3
- evidence: setTaskConfig 与 putTaskConfig 体均为 `map.put(fullId, entry)`；setMinigameConfig 与 putMinigameConfig 同构。ConfigManager 层 set* 末尾带 save，put* 不带 save（70-72、181-183），语义差异只在 ConfigManager 层，Repository 层两方法体完全重复。
- impact: 命名暗示语义差异（set vs put）但 Repository 层无差别，易混淆；维护时需同步改两份。
- direction: Repository 层合并为单一 put，ConfigManager 层用“是否 save”参数或两个方法名表达语义。

### S4-010 [耦合 / S3] ConfigStore / MinigameEnforcement 直接依赖 SRE DLC 具体类 QuestMinigames/QuestMinigame，无抽象隔离
- file: com/habitrain/core/config/ConfigStore.java, MinigameEnforcement.java
- line: ConfigStore.java:10-11, 192-197, 213-215, 223-230；MinigameEnforcement.java:3, 29-34
- dimension: 耦合
- severity: S3
- evidence: ConfigStore import `io.wifi.starrailexpress.content.minigame.QuestMinigame/QuestMinigames`，safeGetAllMinigames 直接调 `QuestMinigames.getAll()`（catch Throwable 兜底）。MinigameEnforcement 直接 import 同类 + `io.wifi.starrailexpress.cca.AreasWorldComponent`，直接读写 `areas.minigameQuestEnabled` / `areas.availableMinigameIds` / `areas.mapName` / `areas.sync()`。
- impact: config 包对 SRE DLC 的具体类与具体字段名强耦合（ AreasWorldComponent 字段名硬编码），SRE 升级改字段名会直接崩；catch Throwable 掩盖结构性变化。专属高优先检查点“对外部 DLC 强 @Shadow 耦合”成立（这里是直接字段访问而非 @Shadow，但耦合更强）。
- direction: 经 SRE 提供的稳定接口/SPI 访问小游戏与区域组件，避免 config 包直接依赖 DLC 具体类字段名。

---

## 专属高优先检查点核对结论

1. ConfigManager 单例扩散：确认（S4-004），静态单例被 client/gui/network/mixin/blackout 全部直接 getInstance。
2. ConfigStore/ConfigRepository 每次访问是否重建：Repository 为常驻 HashMap，访问不重建；ConfigStore.buildJsonRoot 每次 save 全量重建 JSON（S4-005）。无每次访问重建 repository 的问题。
3. ConfigSync 全量 vs 增量：loadFromJsonString/applySyncData 均为全量 clear + putAll（ConfigSync.java:83-94, 103-105），无增量 diff；同步频率由 payload 触发（GUI 保存/客户端接收），未见每 tick 广播。
4. getEffectiveGoldReward/getEffectiveEmotionReward/getEffectiveRefreshWeight 是否死方法：确认死方法（S4-001），全树零调用。
5. @Deprecated disabledMaps 是否仍被解析/序列化：仍被 fromJson 解析填充字段（S4-002），但 toJson 不写出、过滤决策不消费，仅 warn。
6. MinigameEnforcement 是否有恒真守卫：未发现恒真/恒假守卫；`entry == null || (entry.enabled && entry.isAllowedOnMap(mapName))` 为正常条件，null 即默认放行（语义可议但非恒真 bug）。
7. config 键命名一致性：tasks/minigames 段键为 fullId/minigame.id()，与 TaskRegistry/QuestMinigame 一致；disabledMaps 字段名仍保留旧键造成迁移负担（S4-002）。无明显命名空间混用。
8. config 是否被 client.gui 直接读具体类而非接口：确认（S4-004），GUI 直接 `ConfigManager.getInstance()` 读 TaskConfigEntry/MinigameConfigEntry public 字段。