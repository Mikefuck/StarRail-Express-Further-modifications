package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.network.CustomTaskBlockPayload;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.content.block_entity.BeveragePlateBlockEntity;
import io.wifi.starrailexpress.content.block.FoodPlatterBlock;
import io.wifi.starrailexpress.content.item.CocktailItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.food.FoodProperties;
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
        int foodPlatterEatTypeId = -1;
        int foodPlatterDrinkTypeId = -1;

        for (TaskDefinition def : TaskRegistry.getAll()) {
            int blockTypeId = def.getBlockTypeId();
            // 同时记录停电模式吃/喝的 typeId（盘子内容检查用），
            // 与首循环合并以消除二次遍历。
            if (HabiTrainCore.TASK_BLACKOUT_EAT.equals(def.getFullId())) foodPlatterEatTypeId = blockTypeId;
            else if (HabiTrainCore.TASK_BLACKOUT_DRINK.equals(def.getFullId())) foodPlatterDrinkTypeId = blockTypeId;
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
            // 仍可能包含常量透视方块（如电话），继续扫描
        }

        // 常量透视方块（非任务但在停电模式中需高亮）
        // ★ 始终加入扫描表，是否显示由客户端渲染 gate（isBlackoutModeActive）控制。
        //   不能依赖扫描时的 active mode（扫描可能发生在大厅阶段或停电模式激活前）。
        Block phoneBlock = BlackoutOverlayTypes.getStreetPhoneBlock();
        if (phoneBlock != null && phoneBlock != Blocks.AIR) {
            blockToTypeIds.computeIfAbsent(phoneBlock, k -> new HashSet<>()).add(BlackoutOverlayTypes.STREET_PHONE);
        }

        // 红色旋转电话（任务商店），停电模式常驻透视，与 street_phone 同管道
        Block rotaryPhoneBlock = BlackoutOverlayTypes.getRotaryPhoneRedBlock();
        if (rotaryPhoneBlock != null && rotaryPhoneBlock != Blocks.AIR) {
            blockToTypeIds.computeIfAbsent(rotaryPhoneBlock, k -> new HashSet<>())
                    .add(BlackoutOverlayTypes.ROTARY_PHONE_RED);
        }

        // 如果 blockToTypeIds 仍然为空：清空并广播空快照，避免客户端残留旧坐标
        if (blockToTypeIds.isEmpty()) {
            CustomTaskBlockCache.clear();
            CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
            LOGGER.info("[MapScannerMixin] 没有可扫描的方块，已广播空快照");
            return;
        }

        CustomTaskBlockCache.clear();

for (int x = areaBox.minX(); x <= areaBox.maxX(); x++) {
            for (int y = areaBox.minY(); y <= areaBox.maxY(); y++) {
                for (int z = areaBox.minZ(); z <= areaBox.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = serverLevel.getBlockState(pos);
                    Block block = state.getBlock();

                    // === 盘子内容检查（镜像 SRE 原版 MapScanner.java:108-125）===
                    // FoodPlatterBlock（含 DrinkTrayBlock 子类）需检查内容物决定 type 39/40。
                    // 空盘子不加入缓存（避免误导玩家到空盘子前无食物可吃）。
                    if (block instanceof FoodPlatterBlock) {
                        if (serverLevel.getBlockEntity(pos) instanceof BeveragePlateBlockEntity entity) {
                            var items = entity.getStoredItems();
                            if (items.isEmpty()) continue;  // 空盘子跳过
                            ItemStack item0 = items.get(0);
                            Item item = item0.getItem();
                            if (item instanceof CocktailItem || item instanceof PotionItem || item instanceof HoneyBottleItem) {
                                // 饮品 → typeId 40（drink）
                                if (foodPlatterDrinkTypeId > 0) {
                                    CustomTaskBlockCache.put(pos, foodPlatterDrinkTypeId);
                                    totalAddedCount++;
                                }
                            } else {
                                // 食物（需有 FOOD 组件）→ typeId 39（eat）
                                FoodProperties foodPro = item0.get(net.minecraft.core.component.DataComponents.FOOD);
                                if (foodPro != null && foodPlatterEatTypeId > 0) {
                                    CustomTaskBlockCache.put(pos, foodPlatterEatTypeId);
                                    totalAddedCount++;
                                }
                                // 其它物品（如非食物）跳过
                            }
                        }
                        continue;  // 盘子类方块不走下方通用 blockToTypeIds 路径
                    }

                    Set<Integer> typeIds = blockToTypeIds.get(block);
                    if (typeIds != null) {
                        for (int typeId : typeIds) {
                            // 性能优化：同时缓存 Block 实例，渲染时免查 getBlockState
                            CustomTaskBlockCache.put(pos, typeId, block);
                        }
                        totalAddedCount++;
                    }
                }
            }
        }

        // Always broadcast snapshot (including empty) so clients drop stale positions after a rescan.
        if (totalAddedCount > 0) {
            MapScannerManager.saveArea(serverLevel);
        }
        CustomTaskBlockPayload.broadcastToAll(serverLevel.getServer());
        LOGGER.info("[MapScannerMixin] updated custom task block cache with {} entries (multi-typeId)",
                totalAddedCount);
    }
}
