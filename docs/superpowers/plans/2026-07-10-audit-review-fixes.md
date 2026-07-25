# 哈比列车 API 审计复核修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 2026-07-10 代码审计清单中经 6 个并行复核代理逐条源码核对后确认的真实缺陷（3 条必须 + 配置持久化批 + blackout 正确性 + 客户端 GUI + sre/betel 逻辑 + 网络加固，共约 19 条）。跳过 10 条假阳性与 10 条卡在 Mike 设计意图上的条目。

**Architecture:** 4 个批次按修复源文件耦合关系划分。每批修完按 CLAUDE.md 规则执行 `./gradlew clean build` 并把 jar 拷到 `D:\Backup\mc mod\临时\`。批次1=配置持久化（同源），批次2=blackout 正确性，批次3=客户端 GUI，批次4=sre mixin 与 betel 逻辑 + 网络加固。每条修复都是外科式小改，不改设计意图、不引入新行为。

**Tech Stack:** Fabric 1.21、Mixin、Fabric Networking、Gson。构建用 Gradle wrapper（Windows 下 `./gradlew.bat` 或 `.\gradlew`）。

## Global Constraints

- **文件访问边界（CLAUDE.md 硬规则）：** 只能访问 `D:\Backup\mc mod\` 下文件；**绝对禁止**访问 `D:\Backup\mc mod\backup\`。
- **构建规则（CLAUDE.md 硬规则）：** 每完成一个批次必须：① 在 `D:\Backup\mc mod\哈比列车api\` 运行 `./gradlew clean build`；② 把 `build/libs/` 下产生的非 `-sources`/`-dev` 的 jar 拷到 `D:\Backup\mc mod\临时\`。
- **平台：** Windows + PowerShell 主，Bash 辅。本计划命令以 PowerShell 语法给出。
- **不要改设计意图：** 卡在 Mike 设计意图上的 10 条（P1-1/P1-2/P1-3/P1-9/P1-11/P1-20/P1-21/P2-1/P2-6/P2-24）本计划**不包含**，列在文末待 Mike 拍板。
- **地址用户：** Address the user as Mike.

---

## 文件结构总览

| 批次 | 文件 | 负责修复 |
|---|---|---|
| 1 | `config/ConfigStore.java` | P0-8/P0-9 commit 清 dirty 早 + save 异常未捕获 |
| 1 | `config/ConfigManager.java` | P1-12 put* 不标 dirty + P2-17 minigame re-enforce + P1-14/P5-36 入口 |
| 1 | `config/TaskConfigEntry.java` | P2-19 mapFilterMode 隐式升级 |
| 1 | `config/MinigameConfigEntry.java` | P2-19 mapFilterMode 隐式升级 |
| 1 | `config/ConfigSync.java` | P1-14 客户端不写盘 + P5-36 merge 入口 |
| 1 | `config/LifecycleEventsRegistrar.java` | P2-17 注入 server |
| 1 | `C2SReceiverRegistrar.java` | P5-36 服务端改 merge + P1-16 去冗余广播 |
| 1 | `client/NetworkReceiverRegistrar.java:105` | P1-14 客户端不写盘调用方 |
| 2 | `game/blackout/task/RestorePowerHandler.java:65` | P0-1 离线 NPE |
| 2 | `game/blackout/task/FurnaceExplosionHandler.java` | P0-2 爆炸维度错 |
| 2 | `task/BackpackSearchHandler.java:78-92` | P1-7 超时不清客户端 HUD |
| 3 | `client/ClientLifecycleHandler.java:86-93` | P1-22 disconnect 不清缓存 + P1-23 |
| 3 | `client/gui/BlackoutTaskShopState.java` | P1-23 加 clear() |
| 3 | `client/gui/BlackoutSheriffVoteScreen.java:145-156` | P4-6 替换不撤回旧票 |
| 4 | `game/sre/mixin/RoleMethodDispatcherMixin.java` | P2-28 emotion 双发 |
| 4 | `game/sre/mixin/NunchuckCooldownMixin.java` | P5-19 game null 守卫 + ThreadLocal remove |
| 4 | `game/sre/mixin/MinigameTaskAssignmentMixin.java` | P5-20 补 @Pseudo + @Mutable |
| 4 | `betel/BetelWithdrawal.java` | P2-9 戒断重应用空窗 |
| 4 | `betel/BetelTickEngine.java:200-235` | P2-9 缓解窗口过期检测 |
| 4 | `network/ConfigUpdatePayload.java` | P2-34 C2S 截断改拒绝 |
| 4 | `network/ShaderInfoPayload.java` | P2-34 C2S 截断改拒绝 |
| 4 | `network/FullConfigSyncPayload.java` | P2-34 统一拒绝语义 |

---

# 批次 1 — 配置持久化

### Task 1.1: ConfigStore 不再静默丢数据（P0-8 + P0-9）

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/ConfigStore.java:42-47`（commit）与 `:163-177`（save）

**背景：** `commit()` 在 `save()` 之前清 dirty；`save()` 只捕 IOException。若 `buildJsonRoot` 抛 RuntimeException（:169），dirty 已被清且异常传出 → 下次 commit 跳过 → 改动永久丢失。两处叠加是最严重的一项。

- [ ] **Step 1: 修改 `commit()` —— save 成功后才清 dirty**

把 `ConfigStore.java:42-47`：
```java
    public boolean commit(ConfigRepository repo) {
        if (!dirty) return false;
        dirty = false;
        save(repo);
        return true;
    }
```
改为：
```java
    public boolean commit(ConfigRepository repo) {
        if (!dirty) return false;
        boolean saved = save(repo);
        if (saved) {
            dirty = false;
            return true;
        }
        // save 失败：保留 dirty，下次 commit 重试，避免改动永久丢失
        return false;
    }
```

- [ ] **Step 2: 修改 `save()` —— 返回 boolean + 捕获所有异常**

把 `ConfigStore.java:163-177`：
```java
    public void save(ConfigRepository repo) {
        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

            JsonObject root = buildJsonRoot(repo, true);

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.error("保存配置失败", e);
        }
    }
```
改为：
```java
    /**
     * @return true 写盘成功；false 失败（含 IO 与 buildJsonRoot 抛出的 RuntimeException），
     *         调用方据此决定是否保留 dirty flag（见 commit）。
     */
    public boolean save(ConfigRepository repo) {
        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

            JsonObject root = buildJsonRoot(repo, true);

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
            return true;
        } catch (Exception e) {
            // 同时捕获 IOException 与 buildJsonRoot 抛出的 RuntimeException，
            // 避免非 IO 异常传出后 commit 已清 dirty 导致改动永久丢失。
            LOGGER.error("保存配置失败", e);
            return false;
        }
    }
```

- [ ] **Step 3: 确认 save() 其它调用点不受影响**

`save()` 返回值变化后，`ConfigStore.java:58` 与 `:159` 的 `save(repo);` 语句忽略返回值仍合法。Grep 确认无调用方读返回值。

Run: Grep `\.save\(repo\)` in `src/main/java/com/habitrain/core/config/`

---

### Task 1.2: ConfigManager.put* 标 dirty + 失效缓存（P1-12）

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java:91-93, 210-212`

**背景：** `putTaskConfig`/`putMinigameConfig` 只 `repository.put*`，不标 dirty、不失效 `TaskPoolBuilder` 缓存。GUI 内联按钮（enable/color/outline/mapFilter）走 `saveCurrent()`→`putMinigameConfig`，OP 按 ESC 退出时 `commit` 见 dirty=false 不写盘、不发服务器 → 改动丢失。`set*` 是正确范式。

- [ ] **Step 1: putTaskConfig 对齐 setTaskConfig**

把 `ConfigManager.java:91-93`：
```java
    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        repository.putTaskConfig(fullId, entry);
    }
```
改为：
```java
    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        repository.putTaskConfig(fullId, entry);
        store.markDirty();
        TaskPoolBuilder.invalidateAll();
    }
```
（`TaskPoolBuilder` 已在 :3 import。）

- [ ] **Step 2: putMinigameConfig 对齐 setMinigameConfig**

把 `ConfigManager.java:210-212`：
```java
    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.putMinigameConfig(minigameId, entry);
    }
```
改为（本步只加 markDirty，re-enforce 在 Task 1.3 合并进来）：
```java
    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.putMinigameConfig(minigameId, entry);
        store.markDirty();
    }
```

---

### Task 1.3: MinigameEnforcement 运行时改配置后 re-enforce（P2-17）

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`（字段区 + setMinigameConfig/putMinigameConfig）
- Modify: `src/main/java/com/habitrain/core/config/LifecycleEventsRegistrar.java`（SERVER_STARTED/STOPPING）

**背景：** `MinigameEnforcement.apply` 只在 SERVER_STARTED 跑一次。单机下 OP 用 GUI 改 minigame 配置后，`setMinigameConfig`/`putMinigameConfig` 不触发 re-enforce → SRE 的 `areas.availableMinigameIds` 不更新直到重启。联机 JSON 路径（C2S handler `:39` 已调 `applyMinigameEnforcement`）已覆盖。需让 set/put 后也 re-enforce。`applyMinigameEnforcement(@Nullable MinecraftServer)` 已存在（ConfigManager.java:233）。

- [ ] **Step 1: ConfigManager 加 server 持有者字段**

在 `ConfigManager.java:20`（`private final MinigameEnforcement enforcement;` 之后）加：
```java
    private volatile MinecraftServer currentServer;
```
（`MinecraftServer` 已在 :4 import。）

- [ ] **Step 2: 加 setServer 方法**

在 `getInstance()` 方法之后（:42 之后）加：
```java
    /** 由 LifecycleEventsRegistrar 在 SERVER_STARTED 注入，供运行时改 minigame 配置后 re-enforce。 */
    public void setServer(@Nullable MinecraftServer server) {
        this.currentServer = server;
    }
```
（`@Nullable` 已在 :5 import。）

- [ ] **Step 3: setMinigameConfig / putMinigameConfig 末尾 re-enforce**

把 `ConfigManager.java:205-208`（setMinigameConfig）：
```java
    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.setMinigameConfig(minigameId, entry);
        store.markDirty();
    }
```
改为：
```java
    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.setMinigameConfig(minigameId, entry);
        store.markDirty();
        if (currentServer != null) applyMinigameEnforcement(currentServer);
    }
```

把 Task 1.2 Step 2 改后的 putMinigameConfig：
```java
    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.putMinigameConfig(minigameId, entry);
        store.markDirty();
    }
```
改为：
```java
    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.putMinigameConfig(minigameId, entry);
        store.markDirty();
        if (currentServer != null) applyMinigameEnforcement(currentServer);
    }
```

- [ ] **Step 4: LifecycleEventsRegistrar 注入/清空 server**

Read `src/main/java/com/habitrain/core/config/LifecycleEventsRegistrar.java`，定位 SERVER_STARTED 处理器（含 `MinigameEnforcement.apply` 或 `ConfigManager.getInstance().load()` 调用，约 :44）与 SERVER_STOPPING 处理器（约 :77）。

在 SERVER_STARTED 处理器内 `ConfigManager.getInstance().load()` 之后加：
```java
            ConfigManager.getInstance().setServer(server);
```
（`server` 是该 lambda 的参数名，按实际确认——若参数名不同按实际改。）

在 SERVER_STOPPING 处理器内加：
```java
            ConfigManager.getInstance().setServer(null);
```

- [ ] **Step 5: 确认 LifecycleEventsRegistrar 已 import ConfigManager**

Read 文件头部确认 `import com.habitrain.core.config.ConfigManager;` 存在；若无则加。

---

### Task 1.4: mapFilterMode 不再隐式升级为白名单（P2-19）

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/TaskConfigEntry.java:50, 68-72`
- Modify: `src/main/java/com/habitrain/core/config/MinigameConfigEntry.java:44, 61-65`

**背景：** 用户从白名单（mode=1+列表）切到"全部允许"（mode=0）后列表仍非空。`toJson` 仅在 `mode!=0` 时写 mode。重载后缺 mode + 有列表 → fromJson 强制 mode=1 → 语义翻回白名单。修法：toJson 始终写 mode（含 0），fromJson 缺 mode 时默认 0。

- [ ] **Step 1: TaskConfigEntry.toJson 始终写 mapFilterMode**

把 `TaskConfigEntry.java:50`：
```java
        if (mapFilterMode != 0) json.addProperty("mapFilterMode", mapFilterMode);
```
改为：
```java
        json.addProperty("mapFilterMode", mapFilterMode);
```

- [ ] **Step 2: TaskConfigEntry.fromJson 缺 mode 默认 0**

把 `TaskConfigEntry.java:68-72`：
```java
        if (json.has("mapFilterMode")) {
            entry.mapFilterMode = Math.max(0, Math.min(2, json.get("mapFilterMode").getAsInt()));
        } else if (!entry.enabledMaps.isEmpty()) {
            entry.mapFilterMode = 1;
        }
```
改为：
```java
        if (json.has("mapFilterMode")) {
            entry.mapFilterMode = Math.max(0, Math.min(2, json.get("mapFilterMode").getAsInt()));
        }
        // 缺 mapFilterMode 时默认 0（全部允许），不再因 enabledMaps 非空隐式升级为白名单（mode=1）。
        // mode=0 下 enabledMaps 仅作信息记录、不影响启用判断（见 ConfigManager.isMapAllowed）。
```

- [ ] **Step 3: MinigameConfigEntry.toJson 始终写 mapFilterMode**

把 `MinigameConfigEntry.java:44`：
```java
        if (mapFilterMode != 0) json.addProperty("mapFilterMode", mapFilterMode);
```
改为：
```java
        json.addProperty("mapFilterMode", mapFilterMode);
```

- [ ] **Step 4: MinigameConfigEntry.fromJson 缺 mode 默认 0**

把 `MinigameConfigEntry.java:61-65`：
```java
        if (json.has("mapFilterMode")) {
            entry.mapFilterMode = Math.max(0, Math.min(2, json.get("mapFilterMode").getAsInt()));
        } else if (!entry.enabledMaps.isEmpty()) {
            entry.mapFilterMode = 1;
        }
```
改为：
```java
        if (json.has("mapFilterMode")) {
            entry.mapFilterMode = Math.max(0, Math.min(2, json.get("mapFilterMode").getAsInt()));
        }
        // 缺 mapFilterMode 时默认 0，不再隐式升级为白名单（同 TaskConfigEntry）。
```

---

### Task 1.5: 客户端收服务器配置不写本地盘（P1-14）

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/ConfigSync.java:117-126`
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java:170-172`
- Modify: `src/main/java/com/habitrain/core/client/NetworkReceiverRegistrar.java:105`

**背景：** `applySyncFromJson` 在客户端调 `store.markDirty()`+`store.commit(repo)` 写盘，专用服务器客户端把服务器配置持久化到本地 `habitrain_core.json`，下次单机开服被污染。客户端只需更新内存供 GUI 显示，不需写盘。**唯一调用方是客户端 receiver（`client/NetworkReceiverRegistrar.java:105`）**——服务端 C2S 路径用的是 `loadFromJsonString`（见 Task 1.6），不走 `applySyncFromJson`。所以 `applySyncFromJson` 永远在客户端上下文调用，直接去掉写盘即可，无需加参数区分。

- [ ] **Step 1: ConfigSync.applySyncFromJson 去掉写盘**

把 `ConfigSync.java:117-126`：
```java
    public void applySyncFromJson(ConfigRepository repo, String json) {
        repo.setSuppressCallback(true);
        try {
            loadFromJsonString(repo, json);
            store.markDirty();
            store.commit(repo);
        } finally {
            repo.setSuppressCallback(false);
        }
    }
```
改为：
```java
    public void applySyncFromJson(ConfigRepository repo, String json) {
        repo.setSuppressCallback(true);
        try {
            // 客户端收到服务器配置后只更新内存供 GUI 显示，不写本地盘，
            // 避免把服务器配置污染到本地 habitrain_core.json（影响下次单机开服）。
            loadFromJsonString(repo, json);
        } finally {
            repo.setSuppressCallback(false);
        }
    }
```

- [ ] **Step 2: ConfigManager.applySyncFromJson 无需改签名**

`ConfigManager.java:170-172` 不变：
```java
    public void applySyncFromJson(String json) {
        sync.applySyncFromJson(repository, json);
    }
```
（因为唯一调用方是客户端，且内部已去掉写盘。）

- [ ] **Step 3: 客户端 receiver 调用方无需改**

`client/NetworkReceiverRegistrar.java:105` `ConfigManager.getInstance().applySyncFromJson(payload.getConfigJson());` 不变（行为已由 Step 1 改变）。

---

### Task 1.6: P5-36 服务端 OP 配置更新改 merge 语义 + P1-16 去冗余广播

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/ConfigSync.java`（新增 mergeFromJsonString）
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`（新增 mergeFromJsonString 入口）
- Modify: `src/main/java/com/habitrain/core/C2SReceiverRegistrar.java:37-45`（改用 merge + 去冗余广播）

**背景：** OP 保存时客户端 `toJsonString()` 序列化客户端整个配置视图发 `ConfigUpdatePayload`，服务端 `loadFromJsonString`（clear+putAll）整体替换。客户端视图陈旧时（服务器有客户端未收到的条目），客户端广播会删除那些服务端独有条目。改服务端为 merge 语义（仅覆盖 JSON 中存在的键，不删除缺失的键）。同时去掉三重广播里的冗余项——`FullConfigSyncPayload` 已含全部内容，`TaskConfigPayload` 与 `ShaderConfigPayload` 冗余（但 ShaderConfig 含 enabled 字段需保留？经核对 `buildJsonRoot` 的 global 段含 `shaderWhitelistEnabled`+`shaderWhitelist`，FullConfigSync 也带 global，故 ShaderConfigPayload 确冗余）。

- [ ] **Step 1: ConfigSync 新增 mergeFromJsonString**

在 `ConfigSync.java` 的 `loadFromJsonString` 方法之后（:104 之后）新增：
```java
    /**
     * 合并语义：仅用 json 中存在的 tasks/gameModes/minigames 覆盖对应键，
     * 不删除 json 中缺失的键（避免 OP 客户端陈旧视图删除服务端独有条目）。
     * global 字段按 json 覆盖（整体项）。
     */
    public void mergeFromJsonString(ConfigRepository repo, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");
                if (global.has("dlcProbabilityTarget")) {
                    repo.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble())));
                }
                if (global.has("shaderWhitelistEnabled")) {
                    repo.setShaderWhitelistEnabled(global.get("shaderWhitelistEnabled").getAsBoolean());
                }
                if (global.has("shaderWhitelist")) {
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    List<String> list = new ArrayList<>();
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) list.add(name);
                    }
                    repo.getShaderWhitelist().clear();
                    repo.getShaderWhitelist().addAll(list);
                }
                if (global.has("sheriffCountDivisor")) {
                    int div = global.get("sheriffCountDivisor").getAsInt();
                    if (div > 0) repo.setSheriffCountDivisor(div);
                }
                if (global.has("tempPowerPrice")) {
                    int price = global.get("tempPowerPrice").getAsInt();
                    if (price >= 0) repo.setTempPowerPrice(price);
                }
            }

            // 合并而非替换：json 中有的键覆盖，缺失的键保留服务端原值
            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                for (var entry : tasks.entrySet()) {
                    repo.getMutableTaskConfigs().put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }
            if (root.has("gameModes")) {
                JsonObject modes = root.getAsJsonObject("gameModes");
                for (var entry : modes.entrySet()) {
                    repo.getMutableGameModeConfigs().put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
            if (root.has("minigames")) {
                JsonObject mg = root.getAsJsonObject("minigames");
                if (mg.has("globalEnabled")) {
                    repo.setMinigameGlobalEnabled(mg.get("globalEnabled").getAsBoolean());
                }
                if (mg.has("entries")) {
                    JsonObject entries = mg.getAsJsonObject("entries");
                    for (var e : entries.entrySet()) {
                        repo.getMutableMinigameConfigs().put(e.getKey(),
                                MinigameConfigEntry.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("从 JSON 字符串合并配置失败，保持原有内存状态不变", e);
        }
    }
```
（`GameModeConfigScope`、`MinigameConfigEntry`、`TaskConfigEntry` 同包，无需 import。`ArrayList`/`List` 已在 :8 `import java.util.*;`。）

- [ ] **Step 2: ConfigManager 加 mergeFromJsonString 入口**

在 `ConfigManager.java:161-164`（loadFromJsonString 之后）新增：
```java
    public void mergeFromJsonString(String json) {
        sync.mergeFromJsonString(repository, json);
        store.markDirty();
    }
```

- [ ] **Step 3: C2S ConfigUpdate handler 改用 merge + 去冗余广播**

把 `C2SReceiverRegistrar.java:37-45`：
```java
                ConfigManager.getInstance().loadFromJsonString(payload.getConfigJson());
                ConfigManager.getInstance().save();
                ConfigManager.getInstance().applyMinigameEnforcement(context.server());
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端配置", player.getName().getString());
                if (context.server().isSingleplayer()) return;
                TaskConfigPayload.broadcastToAll(context.server());
                ShaderConfigPayload.broadcastToAll(context.server());
                // 广播完整配置，让所有客户端的全局项同步到服务端最新值。
                FullConfigSyncPayload.broadcastToAll(context.server());
```
改为：
```java
                // merge 语义：仅覆盖 OP 客户端发送的条目，不删除客户端视图缺失的服务端独有条目（P5-36）
                ConfigManager.getInstance().mergeFromJsonString(payload.getConfigJson());
                ConfigManager.getInstance().save();
                ConfigManager.getInstance().applyMinigameEnforcement(context.server());
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端配置", player.getName().getString());
                if (context.server().isSingleplayer()) return;
                // FullConfigSyncPayload 已含 global + tasks + gameModes + minigames + shader，
                // 单独的 TaskConfigPayload / ShaderConfigPayload 广播冗余，去掉（P1-16）。
                FullConfigSyncPayload.broadcastToAll(context.server());
```

- [ ] **Step 4: 确认 C2SReceiverRegistrar 无需改 import**

`ConfigManager` 已在该文件 import。`FullConfigSyncPayload` 已用于 :45。去掉 `TaskConfigPayload`/`ShaderConfigPayload` 的调用后，若它们在该文件无其它引用，import 可留可删（编译器对未用 import 不报错，保留无害）。

---

### Task 1.7: 批次 1 构建 + 拷 jar

- [ ] **Step 1: 构建**

在 `D:\Backup\mc mod\哈比列车api\` 运行：
```powershell
.\gradlew.bat clean build
```
Expected: BUILD SUCCESSFUL。若编译失败，按报错定位（多半是 Task 1.3 LifecycleEventsRegistrar 参数名、或 Task 1.6 import 缺失）。

- [ ] **Step 2: 拷 jar**

```powershell
Get-ChildItem "D:\Backup\mc mod\哈比列车api\build\libs\*.jar" |
  Where-Object { $_.Name -notmatch '-sources|-dev|-javadoc' } |
  Copy-Item -Destination "D:\Backup\mc mod\临时\" -Force
```
（过滤掉 `-sources`/`-dev` 等，只拷主 jar。）

- [ ] **Step 3: 提交批次 1（可选，看 Mike 要求）**

```bash
git add -A && git commit -m "batch-fix1: config persistence (P0-8/9, P1-12, P2-17, P2-19, P1-14, P5-36, P1-16)"
```

---

# 批次 2 — blackout 正确性

### Task 2.1: RestorePowerHandler 离线 NPE 守卫（P0-1）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RestorePowerHandler.java:65-69`

**背景：** END_SERVER_TICK 循环 `:65` `restoreCompleted.getOrDefault(sp.serverLevel().dimension(), false)` 在 `sp` 为 null（玩家离线）时 NPE。前面分支（:52/:59）都守了 null，唯独此处没守。

- [ ] **Step 1: 加 null 守卫**

把 `RestorePowerHandler.java:65-69`：
```java
                if (restoreCompleted.getOrDefault(sp.serverLevel().dimension(), false)) {
                    if (sp != null) sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    it.remove();
                    continue;
                }
```
改为：
```java
                // sp 可能为 null（玩家在 onUseBlock 注册 slow 后、本 tick 前离线）；
                // restoreCompleted 按 level 维度记录，玩家离线时无法取其维度 → 直接清理本条目。
                if (sp == null) {
                    it.remove();
                    continue;
                }
                if (restoreCompleted.getOrDefault(sp.serverLevel().dimension(), false)) {
                    sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    it.remove();
                    continue;
                }
```

- [ ] **Step 2: 确认 :71-80 分支不受影响**

:71-80 的 `if (state.slowUntilTick <= tick)` 分支已用 `if (sp != null)` 守卫（:72/:75），无需改。Read 确认即可。

---

### Task 2.2: FurnaceExplosionHandler 爆炸用玩家所在维度（P0-2）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionHandler.java:76-95, 191-192, 220-228`

**背景：** `pos` 从玩家当前 level 的 hitResult 捕获，但 `explode`/`destroyBlock` 在 `server.overworld()`（:77）执行。非主世界维度 blackout 下 TNT 炸错世界、发电机毫发无损。改 `PendingExplosion` 记录玩家当时所在的 level，tick 时按该 level 执行。

- [ ] **Step 1: PendingExplosion 记录 dimension**

把 `FurnaceExplosionHandler.java:220-228`：
```java
    private static final class PendingExplosion {
        final BlockPos targetPos;
        final long triggerTick;

        PendingExplosion(BlockPos targetPos, long triggerTick) {
            this.targetPos = targetPos;
            this.triggerTick = triggerTick;
        }
    }
```
改为：
```java
    private static final class PendingExplosion {
        final BlockPos targetPos;
        final long triggerTick;
        final ResourceKey<Level> dimension;

        PendingExplosion(BlockPos targetPos, long triggerTick, ResourceKey<Level> dimension) {
            this.targetPos = targetPos;
            this.triggerTick = triggerTick;
            this.dimension = dimension;
        }
    }
```

- [ ] **Step 2: 入队时传入玩家维度**

把 `:191-192`：
```java
            long triggerTick = serverPlayer.serverLevel().getServer().overworld().getGameTime() + FUSE_DELAY_TICKS;
            pendingExplosions.put(uuid, new PendingExplosion(pos, triggerTick));
```
改为：
```java
            long triggerTick = serverPlayer.serverLevel().getServer().overworld().getGameTime() + FUSE_DELAY_TICKS;
            pendingExplosions.put(uuid, new PendingExplosion(pos, triggerTick, serverPlayer.serverLevel().dimension()));
```

- [ ] **Step 3: tick 处理按 dimension 取 level**

把 `:76-95`：
```java
            if (!pendingExplosions.isEmpty()) {
                ServerLevel overworld = server.overworld();
                for (Iterator<Map.Entry<UUID, PendingExplosion>> it =
                     pendingExplosions.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    PendingExplosion pe = entry.getValue();
                    if (tick >= pe.triggerTick) {
                        // 执行爆炸
                        BlockPos pos = pe.targetPos;
                        if (overworld.getBlockState(pos).is(Blocks.TNT)) {
                            overworld.destroyBlock(pos, false);
                        }
                        overworld.explode(null,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                4.0f, Level.ExplosionInteraction.BLOCK);
                        BlackoutMode.broadcast(overworld, "§c⚡ 发电机被摧毁！");
                        it.remove();
                    }
                }
            }
```
改为：
```java
            if (!pendingExplosions.isEmpty()) {
                for (Iterator<Map.Entry<UUID, PendingExplosion>> it =
                     pendingExplosions.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    PendingExplosion pe = entry.getValue();
                    if (tick >= pe.triggerTick) {
                        // 按玩家点燃 TNT 时所在维度执行爆炸，避免炸错世界（P0-2）
                        ServerLevel level = server.getLevel(pe.dimension);
                        if (level == null) {
                            // 维度已卸载：放弃本次爆炸，清理条目
                            it.remove();
                            continue;
                        }
                        BlockPos pos = pe.targetPos;
                        if (level.getBlockState(pos).is(Blocks.TNT)) {
                            level.destroyBlock(pos, false);
                        }
                        level.explode(null,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                4.0f, Level.ExplosionInteraction.BLOCK);
                        BlackoutMode.broadcast(level, "§c⚡ 发电机被摧毁！");
                        it.remove();
                    }
                }
            }
```

- [ ] **Step 4: 确认 import**

`ResourceKey` 与 `Level` 已在 :25-26 import，`ServerLevel` 在 :15 import。无需新增。

---

### Task 2.3: BackpackSearchHandler 超时清客户端 HUD + 走标准 onRemove（P1-7）

**Files:**
- Modify: `src/main/java/com/habitrain/core/task/BackpackSearchHandler.java:78-92`

**背景：** 超时路径调了 `reclaimForTask` + `markFailed` + `removeActiveTask`，但没发 `ActiveTaskPayload.clearForPlayer(sp)` 也没调 `definition.onRemove`。客户端 `ActiveTaskCache` 不清空 → HUD/方块高亮残留陈旧活动任务直到下局。`ActiveTaskPayload.clearForPlayer(ServerPlayer)` 在 :139 存在；`TaskDefinition.onRemove(Player, TaskInstance)` 在 :136 存在。

- [ ] **Step 1: 补 onRemove + clearForPlayer**

把 `BackpackSearchHandler.java:78-92`：
```java
                    TaskManager mgr = TaskManager.getInstance();
                    TaskInstance stuckTask = mgr.getActiveTask(uuid);
                    if (stuckTask != null
                            && (TASK_SEARCH_BACKPACK.equals(stuckTask.getFullId())
                                    || TASK_BLACKOUT_SEARCH_BACKPACK.equals(stuckTask.getFullId()))
                            && !stuckTask.isFulfilled()) {
                        // 任务超时前回收发放的道具（虽然翻背包通常 onComplete 才发放，
                        // 但若任务以某种方式提前发放了道具，这里回收保证安全）
                        ServerPlayer stuckPlayer = server.getPlayerList().getPlayer(uuid);
                        if (stuckPlayer != null) {
                            com.habitrain.core.api.ItemReclaimHelper.reclaimForTask(stuckPlayer, stuckTask);
                        }
                        stuckTask.markFailed();
                        mgr.removeActiveTask(uuid);
                    }
                    continue;
```
改为：
```java
                    TaskManager mgr = TaskManager.getInstance();
                    TaskInstance stuckTask = mgr.getActiveTask(uuid);
                    if (stuckTask != null
                            && (TASK_SEARCH_BACKPACK.equals(stuckTask.getFullId())
                                    || TASK_BLACKOUT_SEARCH_BACKPACK.equals(stuckTask.getFullId()))
                            && !stuckTask.isFulfilled()) {
                        // 任务超时前回收发放的道具（虽然翻背包通常 onComplete 才发放，
                        // 但若任务以某种方式提前发放了道具，这里回收保证安全）
                        ServerPlayer stuckPlayer = server.getPlayerList().getPlayer(uuid);
                        if (stuckPlayer != null) {
                            com.habitrain.core.api.ItemReclaimHelper.reclaimForTask(stuckPlayer, stuckTask);
                        }
                        // 走标准 onRemove（与 PerPlayerTaskTicker.handleMainTaskDone 失败分支一致），
                        // 让任务定义有机会做自身清理。
                        try {
                            stuckTask.getDefinition().onRemove(stuckPlayer, stuckTask);
                        } catch (Exception ex) {
                            HabiTrainCore.LOGGER.error("search_backpack 超时 onRemove 失败", ex);
                        }
                        stuckTask.markFailed();
                        mgr.removeActiveTask(uuid);
                        // 通知客户端清除活动任务 HUD/方块高亮，避免陈旧活动任务残留到下局
                        if (stuckPlayer != null) {
                            com.habitrain.core.network.ActiveTaskPayload.clearForPlayer(stuckPlayer);
                        }
                    }
                    continue;
```
（`onRemove(Player, TaskInstance)` 第一参允许 null——`stuckPlayer` 离线时为 null 传进去，定义内若用到 player 需自负，但标准路径 `PerPlayerTaskTicker` 同样可能传 null。若担心，可加 `if (stuckPlayer != null) onRemove(...)`——采用此更稳写法：把 onRemove 调用包在 `if (stuckPlayer != null)` 内。）

更稳版本（采用）——把 onRemove 也放进 `if (stuckPlayer != null)`：
```java
                        if (stuckPlayer != null) {
                            com.habitrain.core.api.ItemReclaimHelper.reclaimForTask(stuckPlayer, stuckTask);
                            try {
                                stuckTask.getDefinition().onRemove(stuckPlayer, stuckTask);
                            } catch (Exception ex) {
                                HabiTrainCore.LOGGER.error("search_backpack 超时 onRemove 失败", ex);
                            }
                        }
                        stuckTask.markFailed();
                        mgr.removeActiveTask(uuid);
                        if (stuckPlayer != null) {
                            com.habitrain.core.network.ActiveTaskPayload.clearForPlayer(stuckPlayer);
                        }
```

- [ ] **Step 2: 确认 import**

`HabiTrainCore` 已在 :3 import。`ActiveTaskPayload` 与 `ItemReclaimHelper` 用全限定调用（如上），无需新增 import。

---

### Task 2.4: 批次 2 构建 + 拷 jar

- [ ] **Step 1: 构建** `.\gradlew.bat clean build`，Expected BUILD SUCCESSFUL
- [ ] **Step 2: 拷 jar**（同 Task 1.7 Step 2 命令）
- [ ] **Step 3: 提交**（可选）

---

# 批次 3 — 客户端 GUI

### Task 3.1: resetState 清活动任务缓存与商店状态（P1-22 + P1-23）

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/BlackoutTaskShopState.java:32`
- Modify: `src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java:86-93`

**背景：** `resetState()`（:86-93）清了 HUD 状态但没清 `ActiveTaskCache`/`CustomTaskBlockCache`，换世界后陈旧任务 ID 与方块位置残留 → 新世界幽灵 ESP。`BlackoutTaskShopState` 静态字段也无 clear 路径。复核代理确认 `ActiveTaskCache.java:108` 与 `CustomTaskBlockCache.java:43` 有 `clear()`。

- [ ] **Step 1: BlackoutTaskShopState 加 clear()**

在 `BlackoutTaskShopState.java:32`（`getLastUpdate()` 之后）加：
```java

    /** 客户端换世界/disconnect 时重置，避免旧余额/条目跨世界残留（P1-23）。 */
    public static void clear() {
        balance = 0;
        generatorDestroyed = false;
        restoreUsed = false;
        entries = new ArrayList<>();
        lastUpdate = 0;
    }
```
（`ArrayList` 已在 :5 import。）

- [ ] **Step 2: 确认 ActiveTaskCache 与 CustomTaskBlockCache 的包路径与 clear 方法**

Run: Grep `public static void clear` in `src/main/java/com/habitrain/core/`

记录两个类的全限定名（用于 import）。

- [ ] **Step 3: resetState 调用三个 clear**

把 `ClientLifecycleHandler.java:86-93`：
```java
    private static void resetState() {
        GameRunningCache.invalidate();
        BlackoutHudOverlay.reset();
        BlackoutWelcomeRenderer.reset();
        BlackoutSheriffVoteState.clear();
        BlackoutVoteState.clear();
        ClientBlackoutState.setBlackoutModeActive(false);
    }
```
改为：
```java
    private static void resetState() {
        GameRunningCache.invalidate();
        BlackoutHudOverlay.reset();
        BlackoutWelcomeRenderer.reset();
        BlackoutSheriffVoteState.clear();
        BlackoutVoteState.clear();
        ClientBlackoutState.setBlackoutModeActive(false);
        // 清活动任务/扫描方块缓存与商店状态，避免换世界后陈旧 ESP 轮廓与商店状态残留（P1-22/P1-23）
        ActiveTaskCache.clear();
        CustomTaskBlockCache.clear();
        BlackoutTaskShopState.clear();
    }
```

- [ ] **Step 4: 加 import**

在 `ClientLifecycleHandler.java` import 区加（包路径以 Step 2 Grep 结果为准，预期）：
```java
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.client.gui.BlackoutTaskShopState;
```
（`BlackoutTaskShopState` 与 `BlackoutHudOverlay` 等同在 `client.gui` 包，该文件 :3-7 已 import 多个 `client.gui.*`，加 `BlackoutTaskShopState` 同理。`ActiveTaskCache` 若实际在 `client.cache` 包则如上；若在 `client` 包则改为 `import com.habitrain.core.client.ActiveTaskCache;`——以 Grep 为准。）

---

### Task 3.2: BlackoutSheriffVoteScreen 替换分支发撤回 payload（P4-6）

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/BlackoutSheriffVoteScreen.java:145-156`

**背景：** `toggleSelection` 返回被替换 UUID `replaced`，但 `if (replaced != null)` 分支是空块只带注释，**从不发撤回 payload**。填满 sheriffCount 后再点新候选，旧目标票静默丢弃、新目标票发出，服务端仍计旧票 → 超计/desync。需在 `replaced != null` 时对旧目标发 `sendSheriffVoteCast(replaced, -1)`。

- [ ] **Step 1: 替换分支发撤回 payload**

把 `BlackoutSheriffVoteScreen.java:145-156`：
```java
                UUID replaced = BlackoutSheriffVoteState.toggleSelection(targetId);
                if (replaced != null) {
                    // 替换场景：对旧目标发送撤回 payload，对新目标发送投票
                }
                int slotIndex = BlackoutSheriffVoteState.getSelectedTargetIds().indexOf(targetId);
                if (slotIndex >= 0) {
                    PayloadSenders.sendSheriffVoteCast(targetId, slotIndex);
                } else if (wasSelected && replaced == null) {
                    // 取消投票：发送 slotIndex = -1 表示撤回（服务端按 slotIndex 移除该目标）
                    // 注意：如果 replaced != null，已在上方发送了旧目标的撤回，这里不重复发送
                    PayloadSenders.sendSheriffVoteCast(targetId, -1);
                }
```
改为：
```java
                UUID replaced = BlackoutSheriffVoteState.toggleSelection(targetId);
                if (replaced != null) {
                    // 替换场景：先撤回旧目标的票（slotIndex=-1），再发新目标的票（P4-6）
                    PayloadSenders.sendSheriffVoteCast(replaced, -1);
                }
                int slotIndex = BlackoutSheriffVoteState.getSelectedTargetIds().indexOf(targetId);
                if (slotIndex >= 0) {
                    PayloadSenders.sendSheriffVoteCast(targetId, slotIndex);
                } else if (wasSelected && replaced == null) {
                    // 纯取消投票（非替换）：撤回本目标
                    PayloadSenders.sendSheriffVoteCast(targetId, -1);
                }
```

---

### Task 3.3: 批次 3 构建 + 拷 jar

- [ ] **Step 1: 构建** `.\gradlew.bat clean build`
- [ ] **Step 2: 拷 jar**（同前）
- [ ] **Step 3: 提交**（可选）

---

# 批次 4 — sre mixin 与 betel 逻辑 + 网络加固

### Task 4.1: RoleMethodDispatcherMixin progression==null 不双发 emotion（P2-28）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/RoleMethodDispatcherMixin.java:24, 55-94, 101-123`

**背景：** HEAD inject 在 `hasGoldReward && progression!=null` 时 cancel 原方法；`progression==null` 时 :67 早返不 cancel → SRE 原版继续跑（发其自带情绪）。TAIL inject 不区分 HEAD 是否 cancel，仍发 `config.emotionReward` → emotion 双发（gold 不会双发：HEAD 在 cancel 前 return，自定义金跳过）。修法：用 ThreadLocal 标志记录 HEAD 是否接管，TAIL 仅在 HEAD 接管时发自定义情绪。

- [ ] **Step 1: 加 ThreadLocal 标志**

在 `RoleMethodDispatcherMixin.java:24`（LOGGER 之后）加：
```java

    /** 记录本次 callOnFinishQuest 是否被 HEAD 接管（已发自定义金 + cancel 原方法）。
     *  TAIL 据此决定是否发自定义情绪：仅 HEAD 接管时才发，避免 progression==null 路径
     *  与 SRE 原版情绪双发（P2-28）。 */
    private static final ThreadLocal<Boolean> headHandled = ThreadLocal.withInitial(() -> false);
```

- [ ] **Step 2: HEAD 接管时置标志**

把 `RoleMethodDispatcherMixin.java:55-94`（HEAD inject 方法 `habintrain$beforeCallOnFinishQuest`）：
```java
    private static void habitrain$beforeCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                          boolean isParallelTask, CallbackInfo ci) {
        if (player == null || player.level() == null || player.level().isClientSide) return;

        TaskConfigEntry config = findConfigForQuest(quest);
        if (config == null) return;

        if (config.hasGoldReward) {
            // 先确认必要组件已附加，再 cancel 原 SRE 逻辑；否则放行原逻辑，
            // 避免 cancel 后 NPE 导致玩家既拿不到自定义奖励也拿不到 SRE 基础奖励。
            SREPlayerProgressionComponent progression = SREPlayerProgressionComponent.KEY.get(player);
            if (progression == null) {
                return;
            }
            try {
                ci.cancel();

                progression.onRoundQuestFinished(quest);
                SRERole role = getCurrentRole(player);

                if (role != null) {
                    int actualReward = isParallelTask
                            ? Math.max(1, config.goldReward / 2)
                            : config.goldReward;

                    var shop = SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) {
                        shop.addToBalance(actualReward);
                        LOGGER.info("[Reward] 自定义金币奖励 (替换SRE基础): {} (并列={}) 给 {}",
                                actualReward, isParallelTask, player.getName().getString());
                    }

                    role.onFinishQuest(player, quest);
                }
            } catch (Exception e) {
                LOGGER.error("[Reward] 发放自定义金币奖励失败", e);
            }
            return;
        }
    }
```
改为：
```java
    private static void habitrain$beforeCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                          boolean isParallelTask, CallbackInfo ci) {
        headHandled.set(false);
        if (player == null || player.level() == null || player.level().isClientSide) return;

        TaskConfigEntry config = findConfigForQuest(quest);
        if (config == null) return;

        if (config.hasGoldReward) {
            // 先确认必要组件已附加，再 cancel 原 SRE 逻辑；否则放行原逻辑，
            // 避免 cancel 后 NPE 导致玩家既拿不到自定义奖励也拿不到 SRE 基础奖励。
            SREPlayerProgressionComponent progression = SREPlayerProgressionComponent.KEY.get(player);
            if (progression == null) {
                return;
            }
            try {
                ci.cancel();
                headHandled.set(true);

                progression.onRoundQuestFinished(quest);
                SRERole role = getCurrentRole(player);

                if (role != null) {
                    int actualReward = isParallelTask
                            ? Math.max(1, config.goldReward / 2)
                            : config.goldReward;

                    var shop = SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) {
                        shop.addToBalance(actualReward);
                        LOGGER.info("[Reward] 自定义金币奖励 (替换SRE基础): {} (并列={}) 给 {}",
                                actualReward, isParallelTask, player.getName().getString());
                    }

                    role.onFinishQuest(player, quest);
                }
            } catch (Exception e) {
                LOGGER.error("[Reward] 发放自定义金币奖励失败", e);
            }
            return;
        }
    }
```

- [ ] **Step 3: TAIL 仅在 HEAD 接管时发情绪 + 清理 ThreadLocal**

把 `:101-123`（TAIL inject `habintrain$afterCallOnFinishQuest`）：
```java
    private static void habitrain$afterCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                         boolean isParallelTask, CallbackInfo ci) {
        if (player == null || player.level() == null || player.level().isClientSide) return;

        TaskConfigEntry config = findConfigForQuest(quest);
        if (config == null) return;

        try {
            if (config.hasEmotionReward) {
                var mood = SREPlayerMoodComponent.KEY.get(player);
                if (mood != null) {
                    float actualReward = isParallelTask
                            ? config.emotionReward * 1.5f
                            : config.emotionReward;
                    mood.addMood(actualReward);
                    LOGGER.info("[Reward] 发放配置情绪奖励: {} (并列={}) 给 {}",
                            String.format("%.2f", actualReward), isParallelTask, player.getName().getString());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[Reward] 发放自定义情绪奖励失败", e);
        }
    }
```
改为：
```java
    private static void habitrain$afterCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                         boolean isParallelTask, CallbackInfo ci) {
        try {
            if (player == null || player.level() == null || player.level().isClientSide) return;

            TaskConfigEntry config = findConfigForQuest(quest);
            if (config == null) return;

            // 仅当 HEAD 已接管（cancel 了原方法、发了自定义金）时才发自定义情绪，
            // 避免 progression==null 路径下 SRE 原版情绪 + 自定义情绪双发（P2-28）。
            if (headHandled.get() && config.hasEmotionReward) {
                var mood = SREPlayerMoodComponent.KEY.get(player);
                if (mood != null) {
                    float actualReward = isParallelTask
                            ? config.emotionReward * 1.5f
                            : config.emotionReward;
                    mood.addMood(actualReward);
                    LOGGER.info("[Reward] 发放配置情绪奖励: {} (并列={}) 给 {}",
                            String.format("%.2f", actualReward), isParallelTask, player.getName().getString());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[Reward] 发放自定义情绪奖励失败", e);
        } finally {
            headHandled.remove();
        }
    }
```

- [ ] **Step 4: 确认无需新增 import**

ThreadLocal 是 java.lang，无需 import。

---

### Task 4.2: NunchuckCooldownMixin 加 null 守卫 + ThreadLocal remove（P5-19）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/NunchuckCooldownMixin.java:28-31, 51-74`

**背景：** `:59` `SREGameWorldComponent.KEY.get(attacker.level())` 可能 null，`:60` `game.getRole` 直接 NPE 被 catch 吞 → 双节棍冷却静默失效。ThreadLocal 仅 set(false) 不 remove → 线程生命周期泄漏。

- [ ] **Step 1: onHurt HEAD reset 改 remove**

把 `NunchuckCooldownMixin.java:28-31`：
```java
    private static void habitrain$resetKillFlag(ServerPlayer attacker, Player target, int direction_,
                                                CallbackInfo ci) {
        killHappened.set(false);
    }
```
改为：
```java
    private static void habitrain$resetKillFlag(ServerPlayer attacker, Player target, int direction_,
                                                CallbackInfo ci) {
        killHappened.remove();
    }
```

- [ ] **Step 2: TAIL 加 game null 守卫 + ThreadLocal remove**

把 `:51-74`：
```java
    private static void habitrain$applyKillerCooldown(ServerPlayer attacker, Player target, int direction_,
                                                      CallbackInfo ci) {
        if (!Boolean.TRUE.equals(killHappened.get())) {
            return;
        }
        killHappened.set(false);

        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(attacker.level());
            var role = game.getRole(attacker);
            if (role == null || role.getRoleType() != KILLER_ROLE_TYPE) {
                return;
            }

            attacker.getCooldowns().addCooldown(TMMItems.NUNCHUCK, NUNCHUCK_COOLDOWN_TICKS);

            LOGGER.debug(
                    "[NunchuckCD] 杀手 {} 使用双节棍击杀，设置冷却=1000 ticks (50秒)",
                    attacker.getName().getString());

        } catch (Exception e) {
            LOGGER.warn("[NunchuckCD] 设置冷却时出错", e);
        }
    }
```
改为：
```java
    private static void habitrain$applyKillerCooldown(ServerPlayer attacker, Player target, int direction_,
                                                      CallbackInfo ci) {
        if (!Boolean.TRUE.equals(killHappened.get())) {
            return;
        }
        killHappened.remove();

        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(attacker.level());
            if (game == null) {
                // 非 SRE 对局或 component 未附加：不设冷却（避免 NPE 被吞后冷却静默失效，P5-19）
                return;
            }
            var role = game.getRole(attacker);
            if (role == null || role.getRoleType() != KILLER_ROLE_TYPE) {
                return;
            }

            attacker.getCooldowns().addCooldown(TMMItems.NUNCHUCK, NUNCHUCK_COOLDOWN_TICKS);

            LOGGER.debug(
                    "[NunchuckCD] 杀手 {} 使用双节棍击杀，设置冷却=1000 ticks (50秒)",
                    attacker.getName().getString());

        } catch (Exception e) {
            LOGGER.warn("[NunchuckCD] 设置冷却时出错", e);
        }
    }
```

---

### Task 4.3: MinigameTaskAssignmentMixin 补 @Pseudo + @Mutable（P5-20）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/MinigameTaskAssignmentMixin.java:9-10, 18-19, 23`

**背景：** 该 mixin `@Mixin(targets=...)` 无 `@Pseudo`，而 sibling `MinigameRewardMixin` 对同 target 用了 `@Pseudo`。`@Shadow targetMinigameId` 在 inject 内被写入（:56, :58）却无 `@Mutable`，若 SRE 声明该字段 final 则 InjectionError。

- [ ] **Step 1: 加 import**

把 `MinigameTaskAssignmentMixin.java:9-10`：
```java
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
```
改为：
```java
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
```

- [ ] **Step 2: 加 @Pseudo 注解**

把 `:18-19`：
```java
@Mixin(targets = "io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent", remap = false)
public abstract class MinigameTaskAssignmentMixin {
```
改为：
```java
@Mixin(targets = "io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent", remap = false)
@Pseudo
public abstract class MinigameTaskAssignmentMixin {
```

- [ ] **Step 3: 给 targetMinigameId @Shadow 加 @Mutable**

把 `:23`：
```java
    @Shadow(remap = false) public String targetMinigameId;
```
改为：
```java
    @Shadow @Mutable(remap = false) public String targetMinigameId;
```

---

### Task 4.4: BetelWithdrawal 戒断缓解窗口后重应用（P2-9）

**Files:**
- Modify: `src/main/java/com/habitrain/core/betel/BetelQuestState.java`（PlayerBetelData 加字段）
- Modify: `src/main/java/com/habitrain/core/betel/BetelWithdrawal.java:12-20`（加 enterWithdrawalRelief）
- Modify: `src/main/java/com/habitrain/core/betel/BetelTickEngine.java`（吃槟榔设 WITHDRAWAL_ACTIVE 处 + :200-235 缓解窗口过期检测）

**背景：** 吃槟榔后 `effectState=WITHDRAWAL_ACTIVE`，`applyHeavyAddictionEffects` 用 `effectState != WITHDRAWAL_ACTIVE` 守卫阻止重应用。效果 100 tick 过期后 stage≥3 玩家既无 slowness 也无 darkness，直到成瘾降到 <3 再升回。修法：给 WITHDRAWAL_ACTIVE 加"缓解窗口到期时间"，tick 检测过期则置 effectState=NONE，使 `applyHeavyAddictionEffects` 重新生效。

- [ ] **Step 1: Read PlayerBetelData 确认结构**

Read `betel/BetelQuestState.java`，定位 `PlayerBetelData` 内部类字段定义区与 `EffectState` 枚举（复核代理提到 `effectState` 字段在 PlayerBetelData 内，:202 引用 `data.effectState`）。

- [ ] **Step 2: PlayerBetelData 加 withdrawalReliefUntilTick**

在 `PlayerBetelData` 字段区加：
```java
        /** 戒断缓解窗口到期 tick：effectState=WITHDRAWAL_ACTIVE 期间，超过此 tick 则重置为 NONE 以重新应用戒断效果。 */
        public long withdrawalReliefUntilTick = 0;
```

- [ ] **Step 3: BetelWithdrawal 加 enterWithdrawalRelief**

把 `BetelWithdrawal.java:12-20`：
```java
    public static void applyHeavyAddictionEffects(ServerPlayer player, BetelQuestState.PlayerBetelData data) {
        data.addictionStage = BetelQuestState.AddictionStage.SEVERE;
        if (data.effectState != BetelQuestState.EffectState.WITHDRAWAL_ACTIVE) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1, false, true, true));
            EffectOwnershipTracker.claim(player.getUUID(), MobEffects.MOVEMENT_SLOWDOWN, "betel_quest");
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, true, true));
            EffectOwnershipTracker.claim(player.getUUID(), MobEffects.DARKNESS, "betel_quest");
        }
    }
```
改为：
```java
    public static void applyHeavyAddictionEffects(ServerPlayer player, BetelQuestState.PlayerBetelData data) {
        data.addictionStage = BetelQuestState.AddictionStage.SEVERE;
        if (data.effectState != BetelQuestState.EffectState.WITHDRAWAL_ACTIVE) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1, false, true, true));
            EffectOwnershipTracker.claim(player.getUUID(), MobEffects.MOVEMENT_SLOWDOWN, "betel_quest");
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, true, true));
            EffectOwnershipTracker.claim(player.getUUID(), MobEffects.DARKNESS, "betel_quest");
        }
    }

    /** 吃槟榔缓解戒断：记录缓解窗口，窗口到期后 effectState 自动回 NONE 以重新应用戒断效果（P2-9）。 */
    public static void enterWithdrawalRelief(ServerPlayer player, BetelQuestState.PlayerBetelData data, long reliefTicks) {
        data.effectState = BetelQuestState.EffectState.WITHDRAWAL_ACTIVE;
        data.withdrawalReliefUntilTick = player.serverLevel().getGameTime() + reliefTicks;
    }
```

- [ ] **Step 4: 吃槟榔设 WITHDRAWAL_ACTIVE 处改调 enterWithdrawalRelief**

Run: Grep `WITHDRAWAL_ACTIVE` in `src/main/java/com/habitrain/core/betel/`，定位"吃槟榔"设 `data.effectState = BetelQuestState.EffectState.WITHDRAWAL_ACTIVE;` 的代码（复核代理指出在 `BetelTickEngine.java:129` 附近，与 `removeHeavyAddictionEffects` 调用相邻）。

Read 该处，把形如：
```java
            data.effectState = BetelQuestState.EffectState.WITHDRAWAL_ACTIVE;
```
改为：
```java
            BetelWithdrawal.enterWithdrawalRelief(player, data, 100);
```
（100 = 与效果时长一致的缓解窗口 tick 数。若该处 `player` 变量名不同或 `data` 来源不同，按实际改；若该处已有 `BetelWithdrawal.removeHeavyAddictionEffects(player)` 调用，保留它。）

- [ ] **Step 5: BetelTickEngine 加缓解窗口过期检测**

把 `BetelTickEngine.java:227-235`：
```java
        if (betelAddictionStage >= 3) {
            data.addictionStage = BetelQuestState.AddictionStage.SEVERE;
            BetelWithdrawal.applyHeavyAddictionEffects(player, data);
        } else {
            if (data.addictionStage != BetelQuestState.AddictionStage.NONE && betelAddictionStage < 3) {
                data.addictionStage = BetelQuestState.AddictionStage.NONE;
                data.effectState = BetelQuestState.EffectState.NONE;
            }
        }
```
改为：
```java
        // 戒断缓解窗口到期：重置 effectState 为 NONE，使后续 applyHeavyAddictionEffects 重新生效，
        // 避免 stage>=3 玩家在缓解窗口后既无 slowness 也无 darkness 的空窗（P2-9）。
        if (data.effectState == BetelQuestState.EffectState.WITHDRAWAL_ACTIVE
                && player.serverLevel().getGameTime() >= data.withdrawalReliefUntilTick) {
            data.effectState = BetelQuestState.EffectState.NONE;
        }

        if (betelAddictionStage >= 3) {
            data.addictionStage = BetelQuestState.AddictionStage.SEVERE;
            BetelWithdrawal.applyHeavyAddictionEffects(player, data);
        } else {
            if (data.addictionStage != BetelQuestState.AddictionStage.NONE && betelAddictionStage < 3) {
                data.addictionStage = BetelQuestState.AddictionStage.NONE;
                data.effectState = BetelQuestState.EffectState.NONE;
            }
        }
```

- [ ] **Step 6: 确认 BetelTickEngine import BetelWithdrawal**

确认 `BetelTickEngine.java` 已 import `BetelWithdrawal`（同包，无需 import——`betel` 包内类互访无需 import 语句）。Run: Grep `import.*BetelWithdrawal` in `BetelTickEngine.java` 应无结果（同包），无需改。

---

### Task 4.5: C2S payload 截断超大包改拒绝（P2-34）

**Files:**
- Modify: `src/main/java/com/habitrain/core/network/ConfigUpdatePayload.java`（codec）
- Modify: `src/main/java/com/habitrain/core/network/ShaderInfoPayload.java`（codec）
- Modify: `src/main/java/com/habitrain/core/network/FullConfigSyncPayload.java`（codec）
- Reference: `src/main/java/com/habitrain/core/network/ActiveTaskPayload.java:74-83`（范例）

**背景：** 三个 payload `len = Math.min(len, MAX)` 后只读截断字节 → 超大 config 被静默截成无效 JSON 喂给 `loadFromJsonString`。应像 `ActiveTaskPayload.java:74-83` 那样 `len > MAX` 抛 `DecoderException` 拒绝。

- [ ] **Step 1: Read 四个 payload 的 codec**

Read `ConfigUpdatePayload.java`、`ShaderInfoPayload.java`、`FullConfigSyncPayload.java` 的 `STREAM_CODEC` 定义处，以及 `ActiveTaskPayload.java:74-83` 确认 MAX 常量名与抛法。

- [ ] **Step 2: ConfigUpdatePayload 改抛 DecoderException**

定位 `ConfigUpdatePayload.java` 的 `Math.min(len, MAX_*)` 截断模式（约 :42-48），改为：
```java
            int len = buf.readInt();
            if (len > MAX_JSON_LENGTH) {
                throw new DecoderException("ConfigUpdate payload 过大: " + len + " > " + MAX_JSON_LENGTH);
            }
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
```
（MAX 常量名按该文件实际，`DecoderException`/`StandardCharsets` import 按 ActiveTaskPayload 范式。）

- [ ] **Step 3: ShaderInfoPayload 同改**

同 Step 2 范式改 `ShaderInfoPayload.java` 的截断处（约 :44-50）。注意 ShaderInfo 可能是 shaderPackName 字符串，MAX 常量名可能不同（如 MAX_STRING_LENGTH）。

- [ ] **Step 4: FullConfigSyncPayload 同改**

同 Step 2 范式改 `FullConfigSyncPayload.java` 的截断处（约 :41-48）。

- [ ] **Step 5: 确认三个文件 import DecoderException**

每个文件加 `import io.netty.handler.codec.DecoderException;`（若已有则跳过）。Run: Grep `DecoderException` in `network/ActiveTaskPayload.java` 确认 import 路径，照抄到三个文件。

---

### Task 4.6: 批次 4 构建 + 拷 jar

- [ ] **Step 1: 构建**

```powershell
.\gradlew.bat clean build
```
Expected: BUILD SUCCESSFUL。mixin 改动（@Pseudo/@Mutable）若 mixin 编译期校验报错，据报错修。若 `@Mutable` 用法报错（`@Mutable` 应放在 `@Shadow` 字段上），调整为 `@Shadow @Mutable(remap=false)` 或 `@Mutable @Shadow(remap=false)` 顺序，以 Mixin 文档为准。

- [ ] **Step 2: 拷 jar**（同前命令）
- [ ] **Step 3: 提交**（可选）

```bash
git add -A && git commit -m "batch-fix4: sre mixin + betel withdrawal + network hardening (P2-28, P5-19, P5-20, P2-9, P2-34)"
```

---

## 自检（Self-Review）

**1. Spec coverage（明确修复项核对）：**
- P0-8/9 → Task 1.1 ✓
- P1-12 → Task 1.2 ✓
- P2-17 → Task 1.3 ✓
- P2-19 → Task 1.4 ✓
- P1-14 → Task 1.5 ✓
- P5-36 + P1-16 → Task 1.6 ✓
- P0-1 → Task 2.1 ✓
- P0-2 → Task 2.2 ✓
- P1-7 → Task 2.3 ✓
- P1-22 + P1-23 → Task 3.1 ✓
- P4-6 → Task 3.2 ✓
- P2-28 → Task 4.1 ✓
- P5-19 → Task 4.2 ✓
- P5-20 → Task 4.3 ✓
- P2-9 → Task 4.4 ✓
- P2-34 → Task 4.5 ✓

共 16 个修复点覆盖 19 条 findings（部分 finding 如 P1-16 合并进 P5-36 任务，P1-22/P1-23 合并进 Task 3.1）。

**2. Placeholder scan:** 无 TBD/TODO/"add appropriate"——每步都有具体代码或具体 Grep 命令。Task 4.4 Step 4 的"按实际改"是因吃槟榔处代码需 Grep 后定位，已给出定位命令与替换模板，非占位。

**3. Type consistency:**
- `save()` 返回 boolean 在 Task 1.1 定义，Task 1.1 Step 3 已确认调用点兼容 ✓
- `enterWithdrawalRelief(player, data, reliefTicks)` 在 Task 4.4 Step 3 定义、Step 4 调用，签名一致 ✓
- `mergeFromJsonString(repo, json)` 在 Task 1.6 Step 1 定义、Step 2 入口、Step 3 调用，一致 ✓
- `BlackoutTaskShopState.clear()` 在 Task 3.1 Step 1 定义、Step 3 调用 ✓
- `ActiveTaskPayload.clearForPlayer(ServerPlayer)` 已存在于源码 :139 ✓
- `TaskDefinition.onRemove(Player, TaskInstance)` 已存在于源码 :136 ✓

## 待 Mike 拍板的 10 条（本计划不含）

P1-1（死玩家复活是否加 isGameEnded 守卫）、P1-2（killer-sheriff 是否可再被雇）、P1-3（SRE 原生警长商店是否卖 revolver）、P1-9（是否支持多维度并发对局）、P1-11（进食是否限 blackout）、P1-20（成瘾降回<3 是否解食物限制）、P1-21（reveal 全局 vs per-player）、P2-1（二停电是否允许恢复）、P2-6（killer 精神免疫是否 by design）、P2-24（需确认 SRE getAvailableTasksList 是否含 scene task）。

## 划掉的假阳性（不用修）

P0-3、P0-4、P2-5、P2-23、P2-25、P2-26、P2-30、P4-10、P5-12，以及 P1-13/P2-11/P2-22/P2-38/P5-15/P5-16/P2-8/P2-37/P5-27（经源码核对非 bug）。