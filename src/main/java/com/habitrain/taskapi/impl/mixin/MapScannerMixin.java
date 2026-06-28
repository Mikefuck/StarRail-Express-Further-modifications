package com.habitrain.taskapi.impl.mixin;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskRegistry;
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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Mixin - 注入 {@link MapScannerManager#loadOrScanAndSaveScannerArea(ServerLevel, AreasWorldComponent)} 方法
 *
 * 这是地图扫描的唯一入口点（无论从缓存加载还是重新扫描都会经过此方法）。
 * 在原始扫描/加载完成后，额外扫描所有通过API注册的自定义任务的方块，
 * 并将其添加到 {@link GameUtils#taskBlocks} 中，
 * 使它们在开启任务点透视时显示高亮边框。
 *
 * 支持两种方块指定方式：
 * 1. scanBlocks: 直接指定 Block 对象（需在注册时已加载）
 * 2. scanBlockIds: 指定方块 ID 字符串（在扫描时延迟解析，推荐）
 */
@Mixin(MapScannerManager.class)
public class MapScannerMixin {

    @Inject(
            method = "loadOrScanAndSaveScannerArea",
            at = @At("RETURN"),
            remap = false
    )
    private static void afterLoadOrScanAndSaveScannerArea(ServerLevel serverLevel, AreasWorldComponent areas, CallbackInfo ci) {
        if (areas == null) return;
        // ★ 移除 areas.noReset 检查：
        //    noReset = true 表示地图从缓存加载（而非重新扫描），
        //    此时 MapScannerManager 不会更新 GameUtils.taskBlocks，
        //    自定义任务方块会丢失透视标记。
        //    ★★★ 修复：不论是否 noReset，都执行自定义方块扫描 ★★★
        //    这样即使地图从缓存加载，自定义任务的方块也能正确注册到透视系统。

        AABB resetPasteArea = areas.getResetPasteArea();
        if (resetPasteArea == null) return;

        BoundingBox areaBox = BoundingBox.fromCorners(
                BlockPos.containing(resetPasteArea.getMinPosition()),
                BlockPos.containing(resetPasteArea.getMaxPosition())
        );

        int totalAddedCount = 0;

        // 遍历所有API注册的自定义任务，检查是否有需要扫描的方块
        for (HabiTaskDefinition def : HabiTaskRegistry.getAll()) {
            int blockTypeId = def.getBlockTypeId();
            if (blockTypeId < 12) continue; // 只处理自定义类型 (12+)

            // 构建需要扫描的方块集合：scanBlocks + scanBlockIds（延迟解析）
            Set<Block> scanBlocks = new HashSet<>();
            if (def.getScanBlocks() != null) {
                scanBlocks.addAll(def.getScanBlocks());
            }
            // 延迟解析 scanBlockIds —— 在 MapScanner 运行时（所有模组已加载完毕后）才解析
            if (def.getScanBlockIds() != null) {
                for (String blockId : def.getScanBlockIds()) {
                    Block resolved = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
                    if (resolved != null && resolved != Blocks.AIR) {
                        scanBlocks.add(resolved);
                    } else {
                        HabiTrainTaskAPI.LOGGER.warn("[MapScannerMixin] 无法解析方块ID: {} (任务: {})",
                                blockId, def.getFullId());
                    }
                }
            }

            if (scanBlocks.isEmpty()) continue;

            int taskAddedCount = 0;

            // 扫描地图区域，找到所有匹配的方块并添加到 taskBlocks
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
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug] MapScannerMixin: added {} blocks (type {}) for task {}",
                        taskAddedCount, blockTypeId, def.getFullId());
                totalAddedCount += taskAddedCount;
            }
        }

        // 如果有新增的方块，更新缓存确保持久化
        if (totalAddedCount > 0) {
            MapScannerManager.saveArea(serverLevel);
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] MapScannerMixin: updated scanner cache with {} custom task blocks",
                    totalAddedCount);
        }
    }
}
