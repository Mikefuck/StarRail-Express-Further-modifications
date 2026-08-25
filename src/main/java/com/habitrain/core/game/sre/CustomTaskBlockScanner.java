package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.network.CustomTaskBlockPayload;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.content.block.FoodPlatterBlock;
import io.wifi.starrailexpress.content.block_entity.BeveragePlateBlockEntity;
import io.wifi.starrailexpress.content.item.CocktailItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
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
            if (HabiTrainCore.TASK_BLACKOUT_EAT.equals(def.getFullId())) foodPlatterEatTypeId = blockTypeId;
            else if (HabiTrainCore.TASK_BLACKOUT_DRINK.equals(def.getFullId())) foodPlatterDrinkTypeId = blockTypeId;
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

        CustomTaskBlockCache.clear();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = areaBox.minX();
        int minY = areaBox.minY();
        int minZ = areaBox.minZ();
        int maxX = areaBox.maxX();
        int maxY = areaBox.maxY();
        int maxZ = areaBox.maxZ();

        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!serverLevel.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
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
                                    Item item = item0.getItem();
                                    if (item instanceof CocktailItem || item instanceof PotionItem || item instanceof HoneyBottleItem) {
                                        if (foodPlatterDrinkTypeId > 0
                                                && CustomTaskBlockCache.put(cursor, foodPlatterDrinkTypeId, block)) {
                                            totalAddedCount++;
                                        }
                                    } else {
                                        FoodProperties foodPro = item0.get(DataComponents.FOOD);
                                        if (foodPro != null && foodPlatterEatTypeId > 0
                                                && CustomTaskBlockCache.put(cursor, foodPlatterEatTypeId, block)) {
                                            totalAddedCount++;
                                        }
                                    }
                                }
                                continue;
                            }

                            Set<Integer> typeIds = blockToTypeIds.get(block);
                            if (typeIds != null) {
                                boolean anyAccepted = false;
                                for (int typeId : typeIds) {
                                    if (CustomTaskBlockCache.put(cursor, typeId, block)) {
                                        anyAccepted = true;
                                    }
                                }
                                if (anyAccepted) {
                                    totalAddedCount++;
                                }
                            }
                        }
                    }
                }
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
