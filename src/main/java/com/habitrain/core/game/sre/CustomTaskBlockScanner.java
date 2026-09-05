package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.network.CustomTaskBlockPayload;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.content.block.FoodPlatterBlock;
import io.wifi.starrailexpress.content.block_entity.BeveragePlateBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-side scan of custom task / overlay blocks into {@link CustomTaskBlockCache}.
 * Lives outside mixins so {@code MapScannerMixin} can stay private (Mixin rejects
 * package-visible static helpers) and {@code MapScannerCacheBroadcastMixin} can
 * still fill the cache on JSON {@code loadOrScan} hits.
 */
public final class CustomTaskBlockScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("MapScannerMixin");
    private static final long DEDUP_WINDOW_MS = 5_000L;
    private static final CustomTaskBlockScanTracker SCAN_TRACKER =
            new CustomTaskBlockScanTracker(DEDUP_WINDOW_MS);

    private CustomTaskBlockScanner() {
    }

    /**
     * DLC {@code loadOrScan} JSON hits never call {@code scanAllTaskBlocks}. Reuse
     * only a snapshot owned by the same dimension, map and scan bounds; otherwise
     * rebuild it before broadcasting to clients.
     */
    public static void ensureCurrent(ServerLevel serverLevel, AreasWorldComponent areas) {
        if (serverLevel == null || areas == null) {
            return;
        }

        BoundingBox areaBox = resolveScanBox(areas);
        if (areaBox == null) {
            return;
        }
        CustomTaskBlockScanKey scanKey = createScanKey(serverLevel, areas, areaBox);
        if (SCAN_TRACKER.isCurrent(scanKey, CustomTaskBlockCache.isEmpty())) {
            CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
            return;
        }

        // 优先从磁盘加载缓存，避免因区块未就绪扫出空条目
        if (areas.mapName != null && !areas.mapName.isBlank()) {
            Map<BlockPos, Set<Integer>> diskData = CustomTaskBlockDiskCache.load(serverLevel, areas.mapName);
            if (!diskData.isEmpty()) {
                CustomTaskBlockCache.loadFromSnapshot(diskData);
                SCAN_TRACKER.markCompleted(scanKey, System.currentTimeMillis());
                CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
                LOGGER.info("[CustomTaskBlockScanner] 命中磁盘缓存，已恢复地图 {} 的 {} 个自定义任务方块并广播",
                        areas.mapName, diskData.size());
                return;
            }
        }

        scan(serverLevel, areas, areaBox, scanKey);
    }

    public static void scan(ServerLevel serverLevel, AreasWorldComponent areas) {
        if (areas == null) {
            return;
        }

        BoundingBox areaBox = resolveScanBox(areas);
        if (areaBox == null) {
            return;
        }
        scan(serverLevel, areas, areaBox, createScanKey(serverLevel, areas, areaBox));
    }

    private static void scan(ServerLevel serverLevel, AreasWorldComponent areas,
                             BoundingBox areaBox, CustomTaskBlockScanKey scanKey) {
        long now = System.currentTimeMillis();
        if (SCAN_TRACKER.shouldSkipScan(scanKey, CustomTaskBlockCache.isEmpty(), now)) {
            LOGGER.info("[MapScannerMixin] skip duplicate scan for map {} ({} entries)",
                    scanKey.mapName(), CustomTaskBlockCache.size());
            CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
            return;
        }

        int totalAddedCount = 0;

        Map<Block, Set<Integer>> blockToTypeIds = new HashMap<>();
        int foodPlatterEatTypeId = -1;
        int foodPlatterDrinkTypeId = -1;

        for (TaskDefinition def : TaskRegistry.getAll()) {
            int blockTypeId = def.getBlockTypeId();
            if (HabiTrainCore.TASK_EAT.equals(def.getFullId())) foodPlatterEatTypeId = blockTypeId;
            else if (HabiTrainCore.TASK_DRINK.equals(def.getFullId())) foodPlatterDrinkTypeId = blockTypeId;
            if (blockTypeId < BlackoutOverlayTypes.CUSTOM_OVERLAY_MIN_TYPE_ID) continue;

            boolean anyResolved = false;
            if (def.getScanBlocks() != null) {
                for (Block b : def.getScanBlocks()) {
                    blockToTypeIds.computeIfAbsent(b, k -> new HashSet<>()).add(blockTypeId);
                    anyResolved = true;
                }
            }
            if (def.getScanBlockIds() != null) {
                for (String blockId : def.getScanBlockIds()) {
                    ResourceLocation blockLoc = ResourceLocation.tryParse(blockId);
                    if (blockLoc == null) {
                        LOGGER.warn("[MapScannerMixin] 非法方块ID: {} (任务: {})",
                                blockId, def.getFullId());
                        continue;
                    }
                    Block resolved = BuiltInRegistries.BLOCK.get(blockLoc);
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
        }

        Block phoneBlock = BlackoutOverlayTypes.getStreetPhoneBlock();
        if (phoneBlock != null && phoneBlock != Blocks.AIR) {
            blockToTypeIds.computeIfAbsent(phoneBlock, k -> new HashSet<>()).add(BlackoutOverlayTypes.STREET_PHONE);
        }

        Block rotaryPhoneBlock = BlackoutOverlayTypes.getRotaryPhoneRedBlock();
        if (rotaryPhoneBlock != null && rotaryPhoneBlock != Blocks.AIR) {
            blockToTypeIds.computeIfAbsent(rotaryPhoneBlock, k -> new HashSet<>())
                    .add(BlackoutOverlayTypes.ROTARY_PHONE_RED);
        }

        if (blockToTypeIds.isEmpty()) {
            CustomTaskBlockCache.clear();
            SCAN_TRACKER.markCompleted(scanKey, System.currentTimeMillis());
            CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
            LOGGER.info("[MapScannerMixin] 没有可扫描的方块，已广播空快照");
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = areaBox.minX();
        int minY = areaBox.minY();
        int minZ = areaBox.minZ();
        int maxX = areaBox.maxX();
        int maxY = areaBox.maxY();
        int maxZ = areaBox.maxZ();

        // 确保所有扫描范围内的区块处于加载状态，避免开局无玩家时被跳过
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                try {
                    serverLevel.getChunk(chunkX, chunkZ);
                } catch (Exception ignored) {
                }
            }
        }

        Map<BlockPos, Set<Integer>> scannedBlocks = new HashMap<>();
        Map<BlockPos, Block> scannedBlockTypes = new HashMap<>();

        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int x0 = Math.max(minX, chunkX << 4);
                int x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4);
                int z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            cursor.set(x, y, z);
                            BlockState state = serverLevel.getBlockState(cursor);
                            if (state.isAir()) {
                                continue;
                            }
                            Block block = state.getBlock();

                            if (block instanceof FoodPlatterBlock) {
                                if (serverLevel.getBlockEntity(cursor) instanceof BeveragePlateBlockEntity entity) {
                                    var items = entity.getStoredItems();
                                    if (items.isEmpty()) continue;
                                    ItemStack item0 = items.get(0);
                                    ConsumableClassificationPolicy.Kind kind =
                                            FoodDrinkConsumableClassifier.classify(item0);
                                    if (kind == ConsumableClassificationPolicy.Kind.DRINK) {
                                        if (foodPlatterDrinkTypeId > 0) {
                                            BlockPos immutable = cursor.immutable();
                                            if (scannedBlocks.computeIfAbsent(immutable, k -> new HashSet<>()).add(foodPlatterDrinkTypeId)) {
                                                scannedBlockTypes.put(immutable, block);
                                                totalAddedCount++;
                                            }
                                        }
                                    } else if (kind == ConsumableClassificationPolicy.Kind.EAT) {
                                        if (foodPlatterEatTypeId > 0) {
                                            BlockPos immutable = cursor.immutable();
                                            if (scannedBlocks.computeIfAbsent(immutable, k -> new HashSet<>()).add(foodPlatterEatTypeId)) {
                                                scannedBlockTypes.put(immutable, block);
                                                totalAddedCount++;
                                            }
                                        }
                                    }
                                }
                                continue;
                            }

                            Set<Integer> typeIds = blockToTypeIds.get(block);
                            if (typeIds != null) {
                                BlockPos immutable = cursor.immutable();
                                boolean anyAccepted = false;
                                for (int typeId : typeIds) {
                                    if (scannedBlocks.computeIfAbsent(immutable, k -> new HashSet<>()).add(typeId)) {
                                        anyAccepted = true;
                                    }
                                }
                                if (anyAccepted) {
                                    scannedBlockTypes.put(immutable, block);
                                    totalAddedCount++;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (totalAddedCount == 0) {
            // 防空快照保护：如果扫出来是 0，先尝试读取磁盘缓存
            if (areas.mapName != null && !areas.mapName.isBlank()) {
                Map<BlockPos, Set<Integer>> diskData = CustomTaskBlockDiskCache.load(serverLevel, areas.mapName);
                if (!diskData.isEmpty()) {
                    CustomTaskBlockCache.loadFromSnapshot(diskData);
                    SCAN_TRACKER.markCompleted(scanKey, System.currentTimeMillis());
                    CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
                    LOGGER.warn("[CustomTaskBlockScanner] 扫描地图 {} 得到 0 条目，已安全回退至磁盘缓存 ({} 条目)",
                            areas.mapName, diskData.size());
                    return;
                }
            }
            LOGGER.warn("[CustomTaskBlockScanner] 扫描地图 {} 得到 0 条目 (未找到匹配的任务方块)", scanKey.mapName());
            CustomTaskBlockCache.clear();
        } else {
            // 扫描成功，装填入 CustomTaskBlockCache
            CustomTaskBlockCache.clear();
            for (var entry : scannedBlocks.entrySet()) {
                Block block = scannedBlockTypes.get(entry.getKey());
                for (int typeId : entry.getValue()) {
                    CustomTaskBlockCache.put(entry.getKey(), typeId, block);
                }
            }
            // 保存至磁盘持久化缓存
            if (areas.mapName != null && !areas.mapName.isBlank()) {
                CustomTaskBlockDiskCache.save(serverLevel, areas.mapName, scannedBlocks);
            }
        }

        SCAN_TRACKER.markCompleted(scanKey, System.currentTimeMillis());
        CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
        LOGGER.info("[MapScannerMixin] updated custom task block cache for map {} with {} entries (multi-typeId)",
                scanKey.mapName(), totalAddedCount);
    }

    private static CustomTaskBlockScanKey createScanKey(
            ServerLevel serverLevel, AreasWorldComponent areas, BoundingBox areaBox) {
        return new CustomTaskBlockScanKey(
                serverLevel.dimension().location().toString(),
                areas.mapName,
                areaBox.minX(), areaBox.minY(), areaBox.minZ(),
                areaBox.maxX(), areaBox.maxY(), areaBox.maxZ());
    }

    /**
     * Same box as DLC {@code MapScanner.scanAllTaskBlocks}: template length offset onto paste min.
     * Falls back to the paste AABB when the template area is missing.
     */
    private static BoundingBox resolveScanBox(AreasWorldComponent areas) {
        AABB paste = areas.getResetPasteArea();
        if (paste == null) {
            return null;
        }
        AABB template = areas.getResetTemplateArea();
        if (template == null) {
            return BoundingBox.fromCorners(
                    BlockPos.containing(paste.getMinPosition()),
                    BlockPos.containing(paste.getMaxPosition()));
        }
        BlockPos backupMinPos = BlockPos.containing(template.getMinPosition());
        BlockPos backupMaxPos = BlockPos.containing(template.getMaxPosition());
        BoundingBox backupTrainBox = BoundingBox.fromCorners(backupMinPos, backupMaxPos);
        BlockPos trainMinPos = BlockPos.containing(paste.getMinPosition());
        BlockPos trainMaxPos = trainMinPos.offset(backupTrainBox.getLength());
        return BoundingBox.fromCorners(trainMinPos, trainMaxPos);
    }
}
