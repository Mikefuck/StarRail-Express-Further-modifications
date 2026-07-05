package com.habitrain.core.game.sre;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CustomTaskBlockCache {

    private static final Map<BlockPos, Set<Integer>> BLOCK_TYPE_IDS = new ConcurrentHashMap<>();

    public static void put(BlockPos pos, int typeId) {
        BLOCK_TYPE_IDS.computeIfAbsent(pos.immutable(), k -> new HashSet<>()).add(typeId);
    }

    public static Set<Integer> get(BlockPos pos) {
        return BLOCK_TYPE_IDS.get(pos);
    }

    public static void clear() {
        BLOCK_TYPE_IDS.clear();
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
        for (var entry : data.entrySet()) {
            BLOCK_TYPE_IDS.put(entry.getKey().immutable(), new HashSet<>(entry.getValue()));
        }
    }
}