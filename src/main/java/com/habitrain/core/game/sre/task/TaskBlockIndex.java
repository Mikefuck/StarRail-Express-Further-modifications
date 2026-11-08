package com.habitrain.core.game.sre.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
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
 * 一个任务方块索引实例。客户端与服务端各自持有实例。
 *
 * 性能优化：除 typeId 外，还缓存方块本身的 Block 实例，避免渲染时每位置每帧
 * 调用 level.getBlockState(pos).getBlock()（chunk section 查询）。
 */
public final class TaskBlockIndex {

    public static final int MAX_ENTRIES = CustomTaskBlockIndexLimits.GLOBAL_MAX_ENTRIES;

    private final Object LOCK = new Object();
    private final Map<BlockPos, Set<Integer>> BLOCK_TYPE_IDS = new ConcurrentHashMap<>();
    /** 并行缓存：BlockPos → 该位置在扫描时记录的 Block（第一个匹配的 typeId 对应的方块） */
    private final Map<BlockPos, Block> BLOCK_AT_POS = new ConcurrentHashMap<>();
    private final Map<Integer, Set<BlockPos>> POS_BY_TYPE = new ConcurrentHashMap<>();
    private final Map<Block, AtomicInteger> COUNT_BY_BLOCK = new ConcurrentHashMap<>();
    private final Set<String> CAP_WARNED = ConcurrentHashMap.newKeySet();

    public boolean put(BlockPos pos, int typeId) {
        return put(pos, typeId, null);
    }

    /** 同时记录 typeId 和方块本身（性能优化：渲染时免查 getBlockState） */
    public boolean put(BlockPos pos, int typeId, Block block) {
        if (pos == null) {
            return false;
        }
        synchronized (LOCK) {
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
    }

    public Set<Integer> get(BlockPos pos) {
        Set<Integer> ids = BLOCK_TYPE_IDS.get(pos);
        return ids == null ? null : Set.copyOf(ids);
    }

    /** 读取扫描时缓存的 Block（避免 getBlockState）。可能返回 null（未缓存或已失效） */
    public Block getBlockAt(BlockPos pos) {
        return BLOCK_AT_POS.get(pos);
    }

    public Set<BlockPos> positionsForType(int typeId) {
        Set<BlockPos> set = POS_BY_TYPE.get(typeId);
        return set == null || set.isEmpty() ? Set.of() : Set.copyOf(set);
    }

    public Set<BlockPos> positionsForTypes(int... typeIds) {
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

    public void clear() {
        synchronized (LOCK) {
            BLOCK_TYPE_IDS.clear();
            BLOCK_AT_POS.clear();
            POS_BY_TYPE.clear();
            COUNT_BY_BLOCK.clear();
            CAP_WARNED.clear();
        }
    }

    public boolean isEmpty() {
        return BLOCK_TYPE_IDS.isEmpty();
    }

    public int size() {
        return BLOCK_TYPE_IDS.size();
    }

    public Set<BlockPos> keySet() {
        return Set.copyOf(BLOCK_TYPE_IDS.keySet());
    }

    public Map<BlockPos, Set<Integer>> snapshot() {
        synchronized (LOCK) {
            Map<BlockPos, Set<Integer>> copy = new HashMap<>();
            for (var entry : BLOCK_TYPE_IDS.entrySet()) {
                copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            return copy;
        }
    }

    public void loadFromSnapshot(Map<BlockPos, Set<Integer>> data) {
        synchronized (LOCK) {
            Map<BlockPos, Block> previousBlocks = new HashMap<>(BLOCK_AT_POS);
            Map<BlockPos, Set<Integer>> previousTypes = snapshot();
            clear();
            if (data == null || data.isEmpty()) {
                return;
            }
            Map<Integer, Block> uniqueBlocks = uniqueBlockByTypeOrEmpty();
            for (var entry : data.entrySet()) {
                BlockPos pos = entry.getKey();
                Set<Integer> types = entry.getValue();
                if (pos == null || types == null || types.isEmpty()) {
                    continue;
                }
                Block restored = resolveBlock(types, uniqueBlocks);
                // 增量刷新类型不变时保留本实例之前解析出的精确 Block。
                if (restored == null) {
                    Set<Integer> prevTypeSet = previousTypes.get(pos);
                    if (prevTypeSet != null && prevTypeSet.equals(types)) {
                        restored = previousBlocks.get(pos);
                    }
                }
                for (int typeId : types) {
                    put(pos, typeId, restored);
                }
            }
        }
    }

    private int groupCount(Block block, int typeId) {
        if (block != null) {
            AtomicInteger n = COUNT_BY_BLOCK.get(block);
            return n == null ? 0 : n.get();
        }
        Set<BlockPos> bucket = POS_BY_TYPE.get(typeId);
        return bucket == null ? 0 : bucket.size();
    }

    private String groupWarnKey(Block block, int typeId) {
        if (block != null) {
            return "block:" + BuiltInRegistries.BLOCK.getKey(block);
        }
        return "type:" + typeId;
    }

    private void warnCap(String key, String message, Object... args) {
        if (CAP_WARNED.add(key)) {
            HabiTrainCore.LOGGER.warn(message, args);
        }
    }

    private Block resolveBlock(Set<Integer> typeIds, Map<Integer, Block> uniqueBlocks) {

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

    private Map<Integer, Block> uniqueBlockByTypeOrEmpty() {
        try {
            return uniqueBlockByType();
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    private Map<Integer, Block> uniqueBlockByType() {
        Map<Integer, Set<Block>> collected = new HashMap<>();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            int typeId = def.getBlockTypeId();
            if (typeId < com.habitrain.core.game.sre.task.CustomTaskBlockIndexLimits.CUSTOM_OVERLAY_MIN_TYPE_ID) {
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

        Map<Integer, Block> unique = new HashMap<>();
        for (var entry : collected.entrySet()) {
            if (entry.getValue().size() == 1) {
                unique.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        return unique;
    }
}
