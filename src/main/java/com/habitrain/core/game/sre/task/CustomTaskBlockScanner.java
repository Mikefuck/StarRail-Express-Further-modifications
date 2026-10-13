package com.habitrain.core.game.sre.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.network.CustomTaskBlockPayload;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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
    private static final String SESSION_ID = java.util.UUID.randomUUID().toString();
    private static final Map<String, Long> LOAD_REVISIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_REVISION = new java.util.concurrent.atomic.AtomicLong();
    public enum ScanStatus { COMPLETE, INCOMPLETE, FAILED }
    private static ScanStatus lastStatus = ScanStatus.INCOMPLETE;

    /** Upstream map loading/edit rescans invalidate same-name, same-bounds snapshots. */
    public static void beginMapLoad(ServerLevel level) {
        if (level != null) LOAD_REVISIONS.put(worldIdentity(level) + "|" + level.dimension().location(), NEXT_REVISION.incrementAndGet());
    }

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
            var diskHit = CustomTaskBlockDiskCache.loadValidated(serverLevel, scanKey);
            if (diskHit.isPresent()) {
                Map<BlockPos, Set<Integer>> diskData = diskHit.get();
                CustomTaskBlockCache.loadFromSnapshot(diskData);
                SCAN_TRACKER.markCompleted(scanKey, System.currentTimeMillis(), diskData.isEmpty());
                lastStatus = ScanStatus.COMPLETE;
                CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
                LOGGER.info("[CustomTaskBlockScanner] 命中磁盘缓存，已恢复地图 {} 的 {} 个自定义任务方块并广播",
                        areas.mapName, diskData.size());
                return;
            }
        }

        scan(serverLevel, areas, areaBox, scanKey);
    }

    public static void scan(ServerLevel serverLevel, AreasWorldComponent areas) {
        if (serverLevel == null || areas == null) {
            lastStatus = ScanStatus.INCOMPLETE;
            return;
        }

        BoundingBox areaBox = resolveScanBox(areas);
        if (areaBox == null) {
            lastStatus = ScanStatus.INCOMPLETE;
            return;
        }
        // Explicit editor/scan requests must observe content changes immediately.
        beginMapLoad(serverLevel);
        scan(serverLevel, areas, areaBox, createScanKey(serverLevel, areas, areaBox));
    }

    private static void scan(ServerLevel serverLevel, AreasWorldComponent areas,
                             BoundingBox areaBox, CustomTaskBlockScanKey scanKey) {
        lastStatus = ScanStatus.INCOMPLETE;
        try {
            scanComplete(serverLevel, areas, areaBox, scanKey);
        } catch (Throwable t) {
            lastStatus = ScanStatus.FAILED;
            LOGGER.error("Custom task block scan failed; previous snapshot retained map={}", scanKey.mapName(), t);
        }
    }

    private static void scanComplete(ServerLevel serverLevel, AreasWorldComponent areas,
                             BoundingBox areaBox, CustomTaskBlockScanKey scanKey) {
        long now = System.currentTimeMillis();
        if (SCAN_TRACKER.shouldSkipScan(scanKey, CustomTaskBlockCache.isEmpty(), now)) {
            lastStatus = ScanStatus.COMPLETE;
            LOGGER.info("[MapScannerMixin] skip duplicate scan for map {} ({} entries)",
                    scanKey.mapName(), CustomTaskBlockCache.size());
            CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
            return;
        }

        int totalAddedCount = 0;

        Map<Block, Set<Integer>> blockToTypeIds = new HashMap<>();

        for (TaskDefinition def : TaskRegistry.getAll()) {
            int blockTypeId = def.getBlockTypeId();
            // 原版任务镜像（sleep/bathe/eat/drink/… 的 typeId 1–24）由上游 MapScanner
            // 自己扫描并作为 SRE 原生 taskBlocks 下发；本 mod 只扫描 <b>自定义</b> overlay 类型。
            if (blockTypeId < com.habitrain.core.game.sre.task.CustomTaskBlockIndexLimits.CUSTOM_OVERLAY_MIN_TYPE_ID) continue;

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

        if (blockToTypeIds.isEmpty()) {
            CustomTaskBlockCache.clear();
            CustomTaskBlockDiskCache.save(serverLevel, scanKey, Map.of());
            SCAN_TRACKER.markCompleted(scanKey, System.currentTimeMillis(), true);
            lastStatus = ScanStatus.COMPLETE;
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
                } catch (Exception failure) {
                    LOGGER.warn("Chunk unavailable during custom task scan; no snapshot committed", failure);
                    lastStatus = ScanStatus.INCOMPLETE;
                    return;
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
            LOGGER.info("[CustomTaskBlockScanner] 完整扫描地图 {} 得到有效空结果", scanKey.mapName());
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
        }
        // Complete empty scans overwrite previous nonempty data, too.
        CustomTaskBlockDiskCache.save(serverLevel, scanKey, scannedBlocks);
        SCAN_TRACKER.markCompleted(scanKey, System.currentTimeMillis(), scannedBlocks.isEmpty());
        lastStatus = ScanStatus.COMPLETE;
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
                areaBox.maxX(), areaBox.maxY(), areaBox.maxZ(),
                worldIdentity(serverLevel), definitionFingerprint(),
                SESSION_ID + ":" + LOAD_REVISIONS.getOrDefault(worldIdentity(serverLevel) + "|" + serverLevel.dimension().location(), 0L));
    }

    static CustomTaskBlockScanKey currentContext(ServerLevel level) {
        if (level == null) return null;
        AreasWorldComponent areas = AreasWorldComponent.KEY.get(level);
        BoundingBox box = areas == null ? null : resolveScanBox(areas);
        return box == null ? null : createScanKey(level, areas, box);
    }

    private static String worldIdentity(ServerLevel level) {
        return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize().toString();
    }

    private static String definitionFingerprint() {
        java.util.List<String> definitions = new java.util.ArrayList<>();
        for (TaskDefinition definition : TaskRegistry.getAll()) {
            if (definition.getBlockTypeId() < CustomTaskBlockIndexLimits.CUSTOM_OVERLAY_MIN_TYPE_ID) continue;
            java.util.List<String> blocks = new java.util.ArrayList<>(definition.getScanBlockIds());
            for (Block block : definition.getScanBlocks()) blocks.add(BuiltInRegistries.BLOCK.getKey(block).toString());
            java.util.Collections.sort(blocks);
            definitions.add(definition.getFullId() + ":" + definition.getBlockTypeId() + ":" + String.join(",", blocks));
        }
        java.util.Collections.sort(definitions);
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", definitions).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
