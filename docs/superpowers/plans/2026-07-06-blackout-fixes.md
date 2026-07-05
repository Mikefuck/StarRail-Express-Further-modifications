# 停电模式修复与增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复停电模式的5个问题：移除草方块/camera透视、移除非关键任务完成弹窗和时间奖励、修复槟榔树叶透视、提高停电任务刷新概率、修复吃饭/喝水任务无法完成。

**Architecture:** 通过Mixin注入修改渲染逻辑和检测机制；新建CustomTaskBlockCache多typeId索引结构解耦SRE的taskBlocks；改用SRE原版的Player.eat() Mixin模式替换脆弱的UseItemCallback+isUsingItem轮询。

**Tech Stack:** Fabric 1.21.1, Java 21, Mixin, Fabric API (CustomPacketPayload, StreamCodec), SRE/noellesroles

## Global Constraints

- Java 21 (`options.release = 21`), Fabric Loom 1.16.3
- 一个 mod ID: `habitrain_core`
- Mixin 包: `game.sre.mixin` (服务端) / `client.mixin` (客户端)
- 网络payload用 Fabric API `CustomPacketPayload` + `StreamCodec` 模式，UTF-8 charset
- 颜色格式: `int ARGB`
- 无任何测试：验证靠 `./gradlew build`（编译 + processResources）和游戏内运行
- 构建: `./gradlew build`（在项目根目录 `D:\Backup\mc mod\哈比列车api`）
- 不要添加注释除非明确要求
- 不要commit除非明确要求

---

## File Structure

### 新建文件
| 文件 | 责任 |
|---|---|
| `src/main/java/com/habitrain/core/game/sre/CustomTaskBlockCache.java` | 自定义任务方块多typeId索引缓存 |
| `src/main/java/com/habitrain/core/network/CustomTaskBlockPayload.java` | S2C同步CustomTaskBlockCache |
| `src/main/java/com/habitrain/core/client/mixin/CameraBlockOverlayMixin.java` | 移除camera方块透视 |
| `src/main/java/com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java` | 吃饭/喝水任务完成检测 |

### 修改文件
| 文件 | 修改内容 |
|---|---|
| `src/main/java/com/habitrain/core/game/sre/mixin/MapScannerMixin.java` | Map<Block,Set<Integer>> + 写入CustomTaskBlockCache |
| `src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java` | 改读CustomTaskBlockCache + 跳过typeId=12 |
| `src/main/java/com/habitrain/core/HabiTrainCore.java` | 注册CustomTaskBlockPayload + 发送同步 |
| `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java` | 注册客户端接收器 |
| `src/main/resources/habitrain_core.mixins.json` | 添加BlackoutEatMixin |
| `src/main/resources/habitrain_core.client.mixins.json` | 添加CameraBlockOverlayMixin |
| 7个非关键任务文件 | 删除onComplete中的时间奖励和完成弹窗 + weight 3.0 |
| 5个机制任务文件 | weight 3.0 |
| `src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatHandler.java` | register()改为空方法 |
| `src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkHandler.java` | register()改为空方法 |

---

### Task 1: 创建 CustomTaskBlockCache

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/CustomTaskBlockCache.java`

**Interfaces:**
- Produces: `CustomTaskBlockCache.put(BlockPos, int)`, `get(BlockPos): Set<Integer>`, `clear()`, `isEmpty()`, `keySet()`, `snapshot()`, `loadFromSnapshot(Map)`

- [ ] **Step 1: 创建 CustomTaskBlockCache.java**

```java
package com.habitrain.core.game.sre;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CustomTaskBlockCache {

    private static final Map<BlockPos, Set<Integer>> BLOCK_TYPE_IDS = new ConcurrentHashMap<>();

    public static void put(BlockPos pos, int typeId) {
        BLOCK_TYPE_IDS.computeIfAbsent(pos.immutable(), k -> new HashSet<>()).add(typeId);
    }

    public static Set<Integer> get(BlockPos pos) {
        return BLOCK_TYPE_IDS.get(pos);
    }

    public static void clear() {
        BLOCK_TYPE_IDS.clear();
    }

    public static boolean isEmpty() {
        return BLOCK_TYPE_IDS.isEmpty();
    }

    public static Set<BlockPos> keySet() {
        return BLOCK_TYPE_IDS.keySet();
    }

    public static Map<BlockPos, Set<Integer>> snapshot() {
        Map<BlockPos, Set<Integer>> copy = new HashMap<>();
        for (var entry : BLOCK_TYPE_IDS.entrySet()) {
            copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return copy;
    }

    public static void loadFromSnapshot(Map<BlockPos, Set<Integer>> data) {
        BLOCK_TYPE_IDS.clear();
        for (var entry : data.entrySet()) {
            BLOCK_TYPE_IDS.put(entry.getKey().immutable(), new HashSet<>(entry.getValue()));
        }
    }
}
```

- [ ] **Step 2: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/CustomTaskBlockCache.java
git commit -m "feat: add CustomTaskBlockCache for multi-typeId block indexing"
```

---

### Task 2: 修改 MapScannerMixin 支持多 typeId

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/MapScannerMixin.java`

**Interfaces:**
- Consumes: `CustomTaskBlockCache.put(BlockPos, int)` from Task 1
- Produces: CustomTaskBlockCache populated with all custom task blocks (typeId>=12)

- [ ] **Step 1: 修改 MapScannerMixin**

将 `Map<Block, Integer> blockToTypeId` 改为 `Map<Block, Set<Integer>>`，Phase 1 用 `computeIfAbsent(...).add(...)`，Phase 2 将自定义类型(>=12)写入 `CustomTaskBlockCache` 而非 `GameUtils.taskBlocks`。

完整替换 `afterLoadOrScanAndSaveScannerArea` 方法体（line 39-116）为：

```java
    private static void afterLoadOrScanAndSaveScannerArea(ServerLevel serverLevel, AreasWorldComponent areas, CallbackInfo ci) {
        if (areas == null) return;

        AABB resetPasteArea = areas.getResetPasteArea();
        if (resetPasteArea == null) return;

        BoundingBox areaBox = BoundingBox.fromCorners(
                BlockPos.containing(resetPasteArea.getMinPosition()),
                BlockPos.containing(resetPasteArea.getMaxPosition())
        );

        int totalAddedCount = 0;

        Map<Block, Set<Integer>> blockToTypeIds = new HashMap<>();

        for (TaskDefinition def : TaskRegistry.getAll()) {
            int blockTypeId = def.getBlockTypeId();
            if (blockTypeId < 12) continue;

            boolean anyResolved = false;
            if (def.getScanBlocks() != null) {
                for (Block b : def.getScanBlocks()) {
                    blockToTypeIds.computeIfAbsent(b, k -> new HashSet<>()).add(blockTypeId);
                    anyResolved = true;
                }
            }
            if (def.getScanBlockIds() != null) {
                for (String blockId : def.getScanBlockIds()) {
                    Block resolved = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
                    if (resolved != null && resolved != Blocks.AIR) {
                        blockToTypeIds.computeIfAbsent(resolved, k -> new HashSet<>()).add(blockTypeId);
                        anyResolved = true;
                    } else {
                        LOGGER.warn("[MapScannerMixin] 无法解析方块ID: {} (任务: {})",
                                blockId, def.getFullId());
                    }
                }
            }
            if (anyResolved) {
                LOGGER.debug("[MapScannerMixin] built lookup entry for task {}, typeId={}",
                        def.getFullId(), blockTypeId);
            }
        }

        if (blockToTypeIds.isEmpty()) {
            LOGGER.info("[MapScannerMixin] 没有可扫描的自定义任务方块");
            return;
        }

        CustomTaskBlockCache.clear();

        for (int x = areaBox.minX(); x <= areaBox.maxX(); x++) {
            for (int y = areaBox.minY(); y <= areaBox.maxY(); y++) {
                for (int z = areaBox.minZ(); z <= areaBox.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = serverLevel.getBlockState(pos);
                    Block block = state.getBlock();

                    Set<Integer> typeIds = blockToTypeIds.get(block);
                    if (typeIds != null) {
                        for (int typeId : typeIds) {
                            CustomTaskBlockCache.put(pos, typeId);
                        }
                        totalAddedCount++;
                    }
                }
            }
        }

        if (totalAddedCount > 0) {
            MapScannerManager.saveArea(serverLevel);
            LOGGER.info("[MapScannerMixin] updated custom task block cache with {} entries (multi-typeId)",
                    totalAddedCount);
        }
    }
```

需要在文件顶部添加 import:
```java
import com.habitrain.core.game.sre.CustomTaskBlockCache;
```

注意：删除 `Map<Block, String> blockToTaskSource` 变量（不再需要）。确保保留 `import java.util.HashSet;`。

- [ ] **Step 2: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/mixin/MapScannerMixin.java
git commit -m "refactor: MapScannerMixin uses multi-typeId Set instead of single Integer"
```

---

### Task 3: 创建 CustomTaskBlockPayload 并集成同步

**Files:**
- Create: `src/main/java/com/habitrain/core/network/CustomTaskBlockPayload.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`
- Modify: `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java`

**Interfaces:**
- Consumes: `CustomTaskBlockCache.snapshot()` from Task 1
- Produces: `CustomTaskBlockPayload.sendToPlayer(ServerPlayer)`, `CustomTaskBlockPayload.broadcastToAll(MinecraftServer)`

- [ ] **Step 1: 创建 CustomTaskBlockPayload.java**

```java
package com.habitrain.core.network;

import com.habitrain.core.game.sre.CustomTaskBlockCache;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CustomTaskBlockPayload implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "custom_task_blocks");
    public static final CustomPacketPayload.Type<CustomTaskBlockPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    private final Map<BlockPos, Set<Integer>> blockTypeIds;

    public CustomTaskBlockPayload(Map<BlockPos, Set<Integer>> blockTypeIds) {
        this.blockTypeIds = blockTypeIds != null ? blockTypeIds : new HashMap<>();
    }

    public Map<BlockPos, Set<Integer>> getBlockTypeIds() {
        return blockTypeIds;
    }

    public static final StreamCodec<ByteBuf, CustomTaskBlockPayload> CODEC = new StreamCodec<>() {
        @Override
        public CustomTaskBlockPayload decode(ByteBuf buf) {
            int entryCount = buf.readInt();
            if (entryCount < 0) entryCount = 0;
            Map<BlockPos, Set<Integer>> data = new HashMap<>();
            for (int i = 0; i < entryCount; i++) {
                int x = buf.readInt();
                int y = buf.readInt();
                int z = buf.readInt();
                BlockPos pos = new BlockPos(x, y, z);
                int setCount = buf.readInt();
                if (setCount < 0) setCount = 0;
                Set<Integer> typeIds = new HashSet<>();
                for (int j = 0; j < setCount; j++) {
                    typeIds.add(buf.readInt());
                }
                data.put(pos, typeIds);
            }
            return new CustomTaskBlockPayload(data);
        }

        @Override
        public void encode(ByteBuf buf, CustomTaskBlockPayload payload) {
            buf.writeInt(payload.blockTypeIds.size());
            for (var entry : payload.blockTypeIds.entrySet()) {
                BlockPos pos = entry.getKey();
                buf.writeInt(pos.getX());
                buf.writeInt(pos.getY());
                buf.writeInt(pos.getZ());
                buf.writeInt(entry.getValue().size());
                for (int typeId : entry.getValue()) {
                    buf.writeInt(typeId);
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendToPlayer(ServerPlayer player) {
        if (player == null) return;
        ServerPlayNetworking.send(player, new CustomTaskBlockPayload(CustomTaskBlockCache.snapshot()));
    }

    public static void broadcastToAll(MinecraftServer server) {
        if (server == null) return;
        var snapshot = CustomTaskBlockCache.snapshot();
        var payload = new CustomTaskBlockPayload(snapshot);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
```

- [ ] **Step 2: 在 HabiTrainCore.java 注册 payload 和发送同步**

在 `onInitialize()` 方法中（line 117-127 的 payload 注册块），添加：
```java
CustomTaskBlockPayload.register();
```
在 `BlackoutSheriffVoteCastPayload.register();` 之后（line 127 之后）。

在 `ServerPlayConnectionEvents.JOIN` 回调中（line 229 `TaskConfigPayload.sendToPlayer(player);` 之后），添加：
```java
CustomTaskBlockPayload.sendToPlayer(player);
```

在文件顶部添加 import：
```java
import com.habitrain.core.network.CustomTaskBlockPayload;
```
（或确认 `import com.habitrain.core.network.*;` 已覆盖，line 15 已有此通配符 import，无需额外添加）

- [ ] **Step 3: 在 HabiTrainCoreClient.java 注册客户端接收器**

在 `onInitializeClient()` 方法中（line 88 ActiveTaskPayload 接收器之后），添加：

```java
        ClientPlayNetworking.registerGlobalReceiver(com.habitrain.core.network.CustomTaskBlockPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                com.habitrain.core.game.sre.CustomTaskBlockCache.loadFromSnapshot(payload.getBlockTypeIds());
            });
        });
```

- [ ] **Step 4: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/network/CustomTaskBlockPayload.java src/main/java/com/habitrain/core/HabiTrainCore.java src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java
git commit -m "feat: add CustomTaskBlockPayload for S2C sync of multi-typeId block cache"
```

---

### Task 4: 修改 CustomTaskBlockRendererMixin 改读 CustomTaskBlockCache + 跳过草方块

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java`

**Interfaces:**
- Consumes: `CustomTaskBlockCache` from Task 1, `CustomTaskBlockPayload` sync from Task 3
- Produces: 渲染从CustomTaskBlockCache读取，跳过typeId=12

- [ ] **Step 1: 修改生存模式渲染逻辑**

将 `habitrain$renderCustomTaskBlocks` 方法（line 193-259）中的渲染循环从读取 `NoellesrolesClient.taskBlocks` 改为读取 `CustomTaskBlockCache`。

替换 line 242-253 的渲染循环为：

```java
        int renderedCount = 0;
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null || !typeIds.contains(blockTypeId)) continue;

            if (level != null && level.getBlockState(pos).getBlock() instanceof TaskInstinctShowableInterface)
                continue;

            renderCustomOverlay(renderContext, pos, taskColor, lineWidth);
            renderedCount++;
        }
```

在 line 218 的 `if (blockTypeId < 12) return;` 之后，添加草方块跳过逻辑：
```java
            if (blockTypeId == 12) return;
```

同样在多人模式路径（line 233）的 `if (blockTypeId < 12) return;` 之后添加：
```java
            if (blockTypeId == 12) return;
```

- [ ] **Step 2: 修改旁观/创造模式渲染逻辑**

将 `renderAllCustomTaskBlocks` 方法（line 269-299）的渲染循环从读取 `NoellesrolesClient.taskBlocks` 改为读取 `CustomTaskBlockCache`，并跳过 typeId=12。

替换 line 279-294 为：

```java
        int renderedCount = 0;
        var level = renderContext.world();
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null) continue;

            if (level != null && level.getBlockState(pos).getBlock() instanceof TaskInstinctShowableInterface)
                continue;

            for (int type : typeIds) {
                if (type == 12) continue;
                Color color = typeColors.get(type);
                if (color != null) {
                    renderCustomOverlay(renderContext, pos, color, 4.0f);
                    renderedCount++;
                    break;
                }
            }
        }
```

- [ ] **Step 3: 更新 imports**

添加：
```java
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import java.util.Set;
```

可删除不再使用的 `import org.agmas.noellesroles.client.NoellesrolesClient;`（若文件中其他地方不再引用）。保留 `TaskBlockOverlayRenderer` import（Mixin目标类仍需要）。

- [ ] **Step 4: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java
git commit -m "feat: renderer reads from CustomTaskBlockCache, skip grass typeId=12"
```

---

### Task 5: 创建 CameraBlockOverlayMixin 移除 camera 透视

**Files:**
- Create: `src/main/java/com/habitrain/core/client/mixin/CameraBlockOverlayMixin.java`
- Modify: `src/main/resources/habitrain_core.client.mixins.json`

**Interfaces:**
- Produces: CameraBlock位置不再被TaskBlockOverlayRenderer渲染为紫色透视框

- [ ] **Step 1: 创建 CameraBlockOverlayMixin.java**

策略：`@Redirect` 拦截 `TaskBlockOverlayRenderer.render` 中对 `renderBlockOverlay` 的所有调用。在 redirect 中检查 pos 处方块是否为 `CameraBlock`，若是则跳过（直接 return），否则调用原方法。

```java
package com.habitrain.core.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.wifi.starrailexpress.content.block.CameraBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.Color;

@Environment(EnvType.CLIENT)
@Mixin(TaskBlockOverlayRenderer.class)
public class CameraBlockOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/agmas/noellesroles/client/TaskBlockOverlayRenderer;renderBlockOverlay(Lnet/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext;Lnet/minecraft/core/BlockPos;Ljava/awt/Color;FZFLnet/minecraft/network/chat/Component;)V",
                    remap = false
            ),
            remap = false
    )
    private static void habitrain$skipCameraOverlay(
            net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context,
            BlockPos pos, Color color, float alpha, boolean throughWalls,
            float lineWidth, Component text) {
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof CameraBlock) {
                return;
            }
        }
        TaskBlockOverlayRenderer.renderBlockOverlay(context, pos, color, alpha, throughWalls, lineWidth, text);
    }
}
```

注：`renderBlockOverlay` 的确切方法签名需要从 SRE 源码确认。根据探索报告，它接受 `(WorldRenderContext, BlockPos, Color, float, boolean, float, Component)` 参数。如果签名不匹配，构建会失败，需要调整 `@At` target 中的方法描述符以匹配实际签名。

- [ ] **Step 2: 注册到 client mixin config**

在 `src/main/resources/habitrain_core.client.mixins.json` 的 `client` 数组中添加 `"CameraBlockOverlayMixin"`：

```json
{
    "required": true,
    "package": "com.habitrain.core.client.mixin",
    "compatibilityLevel": "JAVA_21",
    "client": [
        "HudCustomTaskMixin",
        "CustomTaskBlockRendererMixin",
        "InstinctColorMixin",
        "InstinctCacheFixMixin",
        "StarRailExpressTitleScreenMixin",
        "FixTaskRendererMixin",
        "SubtitleHUDPrefixFixMixin",
        "RoleIntroduceScreenMixin",
        "BlackoutLimitedInventoryScreenMixin",
        "InstinctKillerTeamMixin",
        "InstinctSheriffGateMixin",
        "BlackoutTimeRendererMixin",
        "CameraBlockOverlayMixin"
    ]
}
```

- [ ] **Step 3: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

如果失败，错误信息会显示 `renderBlockOverlay` 的实际签名。根据错误调整 `@At` target 中的方法描述符。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/mixin/CameraBlockOverlayMixin.java src/main/resources/habitrain_core.client.mixins.json
git commit -m "feat: remove camera block透视 via CameraBlockOverlayMixin"
```

---

### Task 6: 移除7个非关键任务的完成弹窗和时间奖励 + 提高权重

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutSearchBackpackTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutPetCatTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutBetelQuestTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutBeAloneTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutLookMyEyesTask.java`

**Interfaces:**
- Produces: 7个任务的onComplete仅保留grantRewards + 其他清理逻辑，删除delayMaintenanceOrCountdown和SubtitleNotifier.sendTop

对每个任务执行以下修改：
1. `.weight(1.0f)` → `.weight(3.0f)`
2. 在 `onComplete` 中删除 `BlackoutTimerSystem.delayMaintenanceOrCountdown(...)` 行
3. 在 `onComplete` 中删除 `SubtitleNotifier.sendTop(...)` 完成消息块

- [ ] **Step 1: 修改 BlackoutSearchBackpackTask.java**

line 29: `.weight(1.0f)` → `.weight(3.0f)`

删除 line 61 `BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), TIME_DELAY);`

删除 line 63-68 的 `SubtitleNotifier.sendTop(...)` 块：
```java
                SubtitleNotifier.sendTop(
                        serverPlayer,
                        Component.translatable("task.blackout_search_backpack"),
                        Component.literal("§a翻找背包完成！供电时间增加 " + TIME_DELAY + " 秒。"),
                        80
                );
```

保留 `BackpackQuestState.markCompleted`、`BackpackSearchHandler.stopSearching`、`serverPlayer.removeEffect`、`BlackoutTaskHelper.grantRewards`。

可删除 `TIME_DELAY` 常量（line 23）和 `BlackoutTimerSystem` import（若文件中无其他引用）。

- [ ] **Step 2: 修改 BlackoutPetCatTask.java**

line 34: `.weight(1.0f)` → `.weight(3.0f)`

删除 line 89 `BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), TIME_DELAY);`

删除 line 91-96 的 `SubtitleNotifier.sendTop(...)` 块。

保留 `BlackoutTaskHelper.grantRewards`。可删除 `TIME_DELAY` 常量和 `BlackoutTimerSystem` import。

- [ ] **Step 3: 修改 BlackoutBetelQuestTask.java**

line 26: `.weight(1.0f)` → `.weight(3.0f)`

删除 line 46 `BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), TIME_DELAY);`

删除 line 48-53 的 `SubtitleNotifier.sendTop(...)` 块。

保留 `BlackoutTaskHelper.grantRewards`。可删除 `TIME_DELAY` 常量和 `BlackoutTimerSystem` import。

- [ ] **Step 4: 修改 BlackoutEatTask.java**

line 25: `.weight(1.0f)` → `.weight(3.0f)`

删除 line 42 `BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), TIME_DELAY);`

删除 line 44-49 的 `SubtitleNotifier.sendTop(...)` 块。

保留 `BlackoutTaskHelper.grantRewards`。可删除 `TIME_DELAY` 常量和 `BlackoutTimerSystem` import。

- [ ] **Step 5: 修改 BlackoutDrinkTask.java**

line 25: `.weight(1.0f)` → `.weight(3.0f)`

删除 line 42 `BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), TIME_DELAY);`

删除 line 44-49 的 `SubtitleNotifier.sendTop(...)` 块。

保留 `BlackoutTaskHelper.grantRewards`。可删除 `TIME_DELAY` 常量和 `BlackoutTimerSystem` import。

- [ ] **Step 6: 修改 BlackoutBeAloneTask.java**

line 34: `.weight(1.0f)` → `.weight(3.0f)`

删除 line 81 `BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), TIME_DELAY);`

删除 line 83-88 的 `SubtitleNotifier.sendTop(...)` 块。

保留 `tickCounters.remove` 和 `BlackoutTaskHelper.grantRewards`。可删除 `TIME_DELAY` 常量和 `BlackoutTimerSystem` import。

- [ ] **Step 7: 修改 BlackoutLookMyEyesTask.java**

line 27: `.weight(1.0f)` → `.weight(3.0f)`

删除 line 83 `BlackoutTimerSystem.delayMaintenanceOrCountdown(serverPlayer.serverLevel(), TIME_DELAY);`

删除 line 85-90 的 `SubtitleNotifier.sendTop(...)` 块。

保留 `BlackoutTaskHelper.grantRewards`。可删除 `TIME_DELAY` 常量和 `BlackoutTimerSystem` import。

- [ ] **Step 8: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

如有未使用的 import 导致警告，清理对应 import。

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/task/BlackoutSearchBackpackTask.java src/main/java/com/habitrain/core/game/blackout/task/BlackoutPetCatTask.java src/main/java/com/habitrain/core/game/blackout/task/BlackoutBetelQuestTask.java src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatTask.java src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkTask.java src/main/java/com/habitrain/core/game/blackout/task/BlackoutBeAloneTask.java src/main/java/com/habitrain/core/game/blackout/task/BlackoutLookMyEyesTask.java
git commit -m "feat: remove completion popups and time rewards from 7 non-critical tasks, increase weight to 3.0"
```

---

### Task 7: 提高5个机制任务的权重

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/AddCoalTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionTask.java`

**Interfaces:**
- Produces: 5个任务的weight从1.0改为3.0

- [ ] **Step 1: 修改5个任务的 weight**

每个文件中将 `.weight(1.0f)` 改为 `.weight(3.0f)`：

- `AddCoalTask.java:36` — `.weight(1.0f)` → `.weight(3.0f)`
- `RepairWiringTask.java:25` — `.weight(1.0f)` → `.weight(3.0f)`
- `MaintainPowerTask.java:22` — `.weight(1.0f)` → `.weight(3.0f)`
- `SabotageWiringTask.java:24` — `.weight(1.0f)` → `.weight(3.0f)`
- `FurnaceExplosionTask.java:29` — `.weight(1.0f)` → `.weight(3.0f)`

不修改这些任务的 onComplete（它们的时间逻辑保留原样）。

- [ ] **Step 2: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/task/AddCoalTask.java src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerTask.java src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringTask.java src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionTask.java
git commit -m "feat: increase blackout mechanism task weight to 3.0"
```

---

### Task 8: 创建 BlackoutEatMixin 修复吃饭/喝水任务

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java`
- Modify: `src/main/resources/habitrain_core.mixins.json`

**Interfaces:**
- Consumes: `TaskManager.getInstance().getActiveTask(UUID)` from existing code
- Produces: 吃饭/喝水任务在Player.eat()时直接完成

- [ ] **Step 1: 创建 BlackoutEatMixin.java**

```java
package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class BlackoutEatMixin {

    @Inject(
            method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD")
    )
    private void habitrain$onEat(Level world, ItemStack stack, FoodProperties food,
                                 CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClientSide()) return;
        if (!((Object) this instanceof ServerPlayer serverPlayer)) return;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null) return;

        if ("habitrain_core:blackout_eat".equals(task.getFullId())) {
            if (!task.isFulfilled() && task.getProgress() < task.getMaxProgress()) {
                task.setProgress(task.getMaxProgress());
            }
            return;
        }

        if ("habitrain_core:blackout_drink".equals(task.getFullId())) {
            if (!task.isFulfilled() && task.getProgress() < task.getMaxProgress()) {
                if (stack.getItem() instanceof PotionItem || stack.getItem() instanceof HoneyBottleItem) {
                    task.setProgress(task.getMaxProgress());
                }
            }
        }
    }
}
```

- [ ] **Step 2: 注册到 mixin config**

在 `src/main/resources/habitrain_core.mixins.json` 的 `mixins` 数组中添加 `"BlackoutEatMixin"`：

```json
{
    "required": true,
    "package": "com.habitrain.core.game.sre.mixin",
    "compatibilityLevel": "JAVA_21",
    "mixins": [
        "BlackoutShopMixin",
        "MapScannerMixin",
        "GenerateTaskMixin",
        "SREPlayerTaskComponentMixin",
        "RoleMethodDispatcherMixin",
        "NunchuckCooldownMixin",
        "MinigameRewardMixin",
        "MinigameTaskAssignmentMixin",
        "BlackoutEatMixin"
    ]
}
```

- [ ] **Step 3: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java src/main/resources/habitrain_core.mixins.json
git commit -m "fix: eat/drink task completion via Player.eat() Mixin (SRE pattern)"
```

---

### Task 9: 清空旧的 Handler register() 方法

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatHandler.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkHandler.java`

**Interfaces:**
- Produces: BlackoutEatHandler.register() 和 BlackoutDrinkHandler.register() 为空方法，保留 clearState/clearAll

- [ ] **Step 1: 修改 BlackoutEatHandler.java**

将 `register()` 方法（line 30-60）改为空方法体：
```java
    public static void register() {
    }
```

保留 `clearState`、`clearAll` 方法。删除不再使用的 import（`UseItemCallback`、`ServerTickEvents`、`InteractionResultHolder`、`InteractionHand`、`DataComponents`、`ItemStack`、`Level`、`TaskInstance`、`TaskManager` 等，如果文件中仅 register 使用它们）。

保留 `eatingTracked` map（clearState 仍操作它）。删除 `onUseItem` 和 END_SERVER_TICK 的匿名 lambda 逻辑。

简化后的文件应包含：
```java
package com.habitrain.core.game.blackout.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlackoutEatHandler {

    private static final Map<UUID, Boolean> eatingTracked = new HashMap<>();

    public static void register() {
    }

    public static void clearState(UUID uuid) {
        eatingTracked.remove(uuid);
    }

    public static void clearAll() {
        eatingTracked.clear();
    }
}
```

- [ ] **Step 2: 修改 BlackoutDrinkHandler.java**

同样将 `register()` 改为空方法体。简化后：
```java
package com.habitrain.core.game.blackout.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlackoutDrinkHandler {

    private static final Map<UUID, Boolean> drinkingTracked = new HashMap<>();

    public static void register() {
    }

    public static void clearState(UUID uuid) {
        drinkingTracked.remove(uuid);
    }

    public static void clearAll() {
        drinkingTracked.clear();
    }
}
```

- [ ] **Step 3: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatHandler.java src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkHandler.java
git commit -m "refactor: empty old eat/drink handler register() methods (replaced by Mixin)"
```

---

### Task 10: 最终构建验证

- [ ] **Step 1: 完整构建**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 检查产物**

确认 `build/libs/` 下生成了 JAR 文件。

- [ ] **Step 3: 游戏内验证清单**

启动客户端后验证：
1. 草方块不再有穿墙透视
2. camera 方块不再有紫色透视框，物资箱仍有
3. 7个非关键任务完成时无顶部弹窗，供电时间不增加，金币/精神奖励正常
4. 槟榔树叶方块在停电模式任务中正确透视
5. 停电模式任务刷新更频繁
6. 吃饭任务：吃任意食物后完成
7. 喝水任务：喝药水/蜂蜜瓶后完成