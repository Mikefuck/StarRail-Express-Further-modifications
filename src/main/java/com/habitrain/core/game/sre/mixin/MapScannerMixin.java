package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.agmas.noellesroles.utils.MapScannerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mixin(MapScannerManager.class)
public class MapScannerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MapScannerMixin");

    @Inject(
            method = "loadOrScanAndSaveScannerArea",
            at = @At("RETURN"),
            remap = false
    )
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
}
