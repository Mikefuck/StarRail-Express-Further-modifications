package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义任务方块缓存。服务端 MapScannerMixin 扫描时填充，客户端从网络包同步。
 *
 * 性能优化：除 typeId 外，还缓存方块本身的 Block 实例，避免渲染时每位置每帧
 * 调用 level.getBlockState(pos).getBlock()（chunk section 查询）。
 */
public class CustomTaskBlockCache {

    public static final int MAX_ENTRIES = CustomTaskBlockIndexLimits.GLOBAL_MAX_ENTRIES;

    private static final Map<BlockPos, Set<Integer>> BLOCK_TYPE_IDS = new ConcurrentHashMap<>();
    /** 并行缓存：BlockPos → 该位置在扫描时记录的 Block（第一个匹配的 typeId 对应的方块） */
    private static final Map<BlockPos, Block> BLOCK_AT_POS = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<BlockPos>> POS_BY_TYPE = new ConcurrentHashMap<>();
    private static final Map<Block, AtomicInteger> COUNT_BY_BLOCK = new ConcurrentHashMap<>();
    private static final Set<String> CAP_WARNED = ConcurrentHashMap.newKeySet();

    public static boolean put(BlockPos pos, int typeId) {
        return put(pos, typeId, null);
    }

    /** 同时记录 typeId 和方块本身（性能优化：渲染时免查 getBlockState） */
    public static boolean put(BlockPos pos, int typeId, Block block) {
        if (pos == null) {
            return false;
        }
        BlockPos key = pos.immutable();
        Set<Integer> existing = BLOCK_TYPE_IDS.get(key);
        if (existing != null && existing.contains(typeId)) {
            if (block != null) {
                BLOCK_AT_POS.putIfAbsent(key, block);
            }
            return true;
        }

        boolean newPosition = existing == null;
        if (newPosition && !CustomTaskBlockIndexLimits.acceptNewPosition(BLOCK_TYPE_IDS.size(), MAX_ENTRIES)) {
            warnCap("global", "CustomTaskBlockCache global cap {} reached, refusing further positions", MAX_ENTRIES);
            return false;
        }

        int cap = CustomTaskBlockIndexLimits.capFor(block, typeId);
        int groupCount = groupCount(block, typeId);
        if (!CustomTaskBlockIndexLimits.acceptGroup(groupCount, cap)) {
            warnCap(groupWarnKey(block, typeId),
                    "CustomTaskBlockCache type/block cap {} reached (typeId={}, block={})",
                    cap, typeId, block);
            return false;
        }

        Set<Integer> ids = BLOCK_TYPE_IDS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (!ids.add(typeId)) {
            if (block != null) {
                BLOCK_AT_POS.putIfAbsent(key, block);
            }
            return true;
        }
        POS_BY_TYPE.computeIfAbsent(typeId, t -> ConcurrentHashMap.newKeySet()).add(key);
        if (block != null) {
            Block previous = BLOCK_AT_POS.putIfAbsent(key, block);
            if (previous == null) {
                COUNT_BY_BLOCK.computeIfAbsent(block, b -> new AtomicInteger()).incrementAndGet();
            }
        }
        return true;
    }

    public static Set<Integer> get(BlockPos pos) {
        return BLOCK_TYPE_IDS.get(pos);
    }

    /** 读取扫描时缓存的 Block（避免 getBlockState）。可能返回 null（未缓存或已失效） */
    public static Block getBlockAt(BlockPos pos) {
        return BLOCK_AT_POS.get(pos);
    }

    public static Set<BlockPos> positionsForType(int typeId) {
        Set<BlockPos> set = POS_BY_TYPE.get(typeId);
        return set == null || set.isEmpty() ? Set.of() : Collections.unmodifiableSet(set);
    }

    public static Set<BlockPos> positionsForTypes(int... typeIds) {
        if (typeIds == null || typeIds.length == 0) {
            return Set.of();
        }
        if (typeIds.length == 1) {
            return positionsForType(typeIds[0]);
        }
        Set<BlockPos> merged = new HashSet<>();
        for (int typeId : typeIds) {
            Set<BlockPos> set = POS_BY_TYPE.get(typeId);
            if (set != null) {
                merged.addAll(set);
            }
        }
        return merged;
    }

    public static void clear() {
        BLOCK_TYPE_IDS.clear();
        BLOCK_AT_POS.clear();
        POS_BY_TYPE.clear();
        COUNT_BY_BLOCK.clear();
        CAP_WARNED.clear();
    }

    public static boolean isEmpty() {
        return BLOCK_TYPE_IDS.isEmpty();
    }

    public static int size() {
        return BLOCK_TYPE_IDS.size();
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
        clear();
        if (data == null || data.isEmpty()) {
            return;
        }
        Map<Integer, Block> uniqueBlocks = uniqueBlockByTypeOrEmpty();
        for (var entry : data.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            Block restored = resolveBlock(entry.getValue(), uniqueBlocks);
            for (int typeId : entry.getValue()) {
                put(entry.getKey(), typeId, restored);
            }
        }
    }

    private static int groupCount(Block block, int typeId) {
        if (block != null && !CustomTaskBlockIndexLimits.isUncappedType(typeId)) {
            AtomicInteger n = COUNT_BY_BLOCK.get(block);
            return n == null ? 0 : n.get();
        }
        Set<BlockPos> bucket = POS_BY_TYPE.get(typeId);
        return bucket == null ? 0 : bucket.size();
    }

    private static String groupWarnKey(Block block, int typeId) {
        if (block != null) {
            return "block:" + BuiltInRegistries.BLOCK.getKey(block);
        }
        return "type:" + typeId;
    }

    private static void warnCap(String key, String message, Object... args) {
        if (CAP_WARNED.add(key)) {
            HabiTrainCore.LOGGER.warn(message, args);
        }
    }

    private static Block resolveBlock(Set<Integer> typeIds, Map<Integer, Block> uniqueBlocks) {
        if (typeIds.contains(BlackoutOverlayTypes.STREET_PHONE)) {
            Block phone = BlackoutOverlayTypes.getStreetPhoneBlock();
            if (phone != null && phone != Blocks.AIR) {
                return phone;
            }
        }
        if (typeIds.contains(BlackoutOverlayTypes.ROTARY_PHONE_RED)) {
            Block rotary = BlackoutOverlayTypes.getRotaryPhoneRedBlock();
            if (rotary != null && rotary != Blocks.AIR) {
                return rotary;
            }
        }
        if (typeIds.contains(BlackoutOverlayTypes.HORN)) {
            Block horn = BlackoutOverlayTypes.getHornBlock();
            if (horn != null && horn != Blocks.AIR) {
                return horn;
            }
        }
        Block found = null;
        for (int typeId : typeIds) {
            Block unique = uniqueBlocks.get(typeId);
            if (unique == null) {
                return null;
            }
            if (found == null) {
                found = unique;
            } else if (found != unique) {
                return null;
            }
        }
        return found;
    }

    private static Map<Integer, Block> uniqueBlockByTypeOrEmpty() {
        try {
            return uniqueBlockByType();
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    private static Map<Integer, Block> uniqueBlockByType() {
        Map<Integer, Set<Block>> collected = new HashMap<>();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            int typeId = def.getBlockTypeId();
            if (typeId < BlackoutOverlayTypes.CUSTOM_OVERLAY_MIN_TYPE_ID) {
                continue;
            }
            if (def.getScanBlocks() != null) {
                for (Block block : def.getScanBlocks()) {
                    if (block != null && block != Blocks.AIR) {
                        collected.computeIfAbsent(typeId, t -> new HashSet<>()).add(block);
                    }
                }
            }
            if (def.getScanBlockIds() != null) {
                for (String blockId : def.getScanBlockIds()) {
                    ResourceLocation loc = ResourceLocation.tryParse(blockId);
                    if (loc == null) {
                        continue;
                    }
                    Block resolved = BuiltInRegistries.BLOCK.get(loc);
                    if (resolved != null && resolved != Blocks.AIR) {
                        collected.computeIfAbsent(typeId, t -> new HashSet<>()).add(resolved);
                    }
                }
            }
        }
        addIfPresent(collected, BlackoutOverlayTypes.STREET_PHONE, BlackoutOverlayTypes.getStreetPhoneBlock());
        addIfPresent(collected, BlackoutOverlayTypes.ROTARY_PHONE_RED, BlackoutOverlayTypes.getRotaryPhoneRedBlock());
        addIfPresent(collected, BlackoutOverlayTypes.HORN, BlackoutOverlayTypes.getHornBlock());

        Map<Integer, Block> unique = new HashMap<>();
        for (var entry : collected.entrySet()) {
            if (entry.getValue().size() == 1) {
                unique.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        return unique;
    }

    private static void addIfPresent(Map<Integer, Set<Block>> collected, int typeId, Block block) {
        if (block != null && block != Blocks.AIR) {
            collected.computeIfAbsent(typeId, t -> new HashSet<>()).add(block);
        }
    }
}
