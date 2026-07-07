package com.habitrain.core.game.sre;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义任务方块缓存。服务端 MapScannerMixin 扫描时填充，客户端从网络包同步。
 *
 * 性能优化：除 typeId 外，还缓存方块本身的 Block 实例，避免渲染时每位置每帧
 * 调用 level.getBlockState(pos).getBlock()（chunk section 查询）。
 */
public class CustomTaskBlockCache {

    private static final Map<BlockPos, Set<Integer>> BLOCK_TYPE_IDS = new ConcurrentHashMap<>();
    /** 并行缓存：BlockPos → 该位置在扫描时记录的 Block（第一个匹配的 typeId 对应的方块） */
    private static final Map<BlockPos, Block> BLOCK_AT_POS = new ConcurrentHashMap<>();

    public static void put(BlockPos pos, int typeId) {
        BLOCK_TYPE_IDS.computeIfAbsent(pos.immutable(), k -> new HashSet<>()).add(typeId);
    }

    /** 同时记录 typeId 和方块本身（性能优化：渲染时免查 getBlockState） */
    public static void put(BlockPos pos, int typeId, Block block) {
        BLOCK_TYPE_IDS.computeIfAbsent(pos.immutable(), k -> new HashSet<>()).add(typeId);
        if (block != null) BLOCK_AT_POS.putIfAbsent(pos.immutable(), block);
    }

    public static Set<Integer> get(BlockPos pos) {
        return BLOCK_TYPE_IDS.get(pos);
    }

    /** 读取扫描时缓存的 Block（避免 getBlockState）。可能返回 null（未缓存或已失效） */
    public static Block getBlockAt(BlockPos pos) {
        return BLOCK_AT_POS.get(pos);
    }

    public static void clear() {
        BLOCK_TYPE_IDS.clear();
        BLOCK_AT_POS.clear();
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
        BLOCK_AT_POS.clear();
        for (var entry : data.entrySet()) {
            BLOCK_TYPE_IDS.put(entry.getKey().immutable(), new HashSet<>(entry.getValue()));
        }
    }
}