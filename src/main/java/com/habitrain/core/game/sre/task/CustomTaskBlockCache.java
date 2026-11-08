package com.habitrain.core.game.sre.task;
import com.habitrain.core.game.sre.task.TaskBlockIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import java.util.Map;
import java.util.Set;
/** Index owned by the server session. */
public class CustomTaskBlockCache {
    public static final int MAX_ENTRIES = TaskBlockIndex.MAX_ENTRIES;
    private static final TaskBlockIndex INDEX = new TaskBlockIndex();
    public CustomTaskBlockCache() {}

    public static boolean put(BlockPos pos, int typeId) { return INDEX.put(pos, typeId); }
    public static boolean put(BlockPos pos, int typeId, Block block) { return INDEX.put(pos, typeId, block); }
    public static Set<Integer> get(BlockPos pos) { return INDEX.get(pos); }
    public static Block getBlockAt(BlockPos pos) { return INDEX.getBlockAt(pos); }
    public static Set<BlockPos> positionsForType(int typeId) { return INDEX.positionsForType(typeId); }
    public static Set<BlockPos> positionsForTypes(int... ids) { return INDEX.positionsForTypes(ids); }
    public static void clear() { INDEX.clear(); }
    public static boolean isEmpty() { return INDEX.isEmpty(); }
    public static int size() { return INDEX.size(); }
    public static Set<BlockPos> keySet() { return INDEX.keySet(); }
    public static Map<BlockPos, Set<Integer>> snapshot() { return INDEX.snapshot(); }
    public static void loadFromSnapshot(Map<BlockPos, Set<Integer>> data) { INDEX.loadFromSnapshot(data); }
}
