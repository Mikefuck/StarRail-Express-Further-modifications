package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
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

import java.util.HashSet;
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

        for (TaskDefinition def : TaskRegistry.getAll()) {
            int blockTypeId = def.getBlockTypeId();
            if (blockTypeId < 12) continue;

            Set<Block> scanBlocks = new HashSet<>();
            if (def.getScanBlocks() != null) {
                scanBlocks.addAll(def.getScanBlocks());
            }
            if (def.getScanBlockIds() != null) {
                for (String blockId : def.getScanBlockIds()) {
                    Block resolved = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
                    if (resolved != null && resolved != Blocks.AIR) {
                        scanBlocks.add(resolved);
                    } else {
                        LOGGER.warn("[MapScannerMixin] 无法解析方块ID: {} (任务: {})",
                                blockId, def.getFullId());
                    }
                }
            }

            if (scanBlocks.isEmpty()) continue;

            int taskAddedCount = 0;
            for (int x = areaBox.minX(); x <= areaBox.maxX(); x++) {
                for (int y = areaBox.minY(); y <= areaBox.maxY(); y++) {
                    for (int z = areaBox.minZ(); z <= areaBox.maxZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = serverLevel.getBlockState(pos);
                        Block block = state.getBlock();

                        for (Block targetBlock : scanBlocks) {
                            if (block.equals(targetBlock)) {
                                GameUtils.taskBlocks.put(pos, blockTypeId);
                                taskAddedCount++;
                                break;
                            }
                        }
                    }
                }
            }

            if (taskAddedCount > 0) {
                LOGGER.info("[MapScannerMixin] added {} blocks (type {}) for task {}",
                        taskAddedCount, blockTypeId, def.getFullId());
                totalAddedCount += taskAddedCount;
            }
        }

        if (totalAddedCount > 0) {
            MapScannerManager.saveArea(serverLevel);
            LOGGER.info("[MapScannerMixin] updated scanner cache with {} custom task blocks",
                    totalAddedCount);
        }
    }
}
