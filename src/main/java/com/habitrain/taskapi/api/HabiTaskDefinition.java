package com.habitrain.taskapi.api;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import java.awt.Color;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 任务定义 - 用于注册新任务的完整描述
 * DLC模组通过 {@link HabiTaskRegistry#register(HabiTaskDefinition)} 注册任务
 */
public class HabiTaskDefinition {
    private final String modId;
    private final String taskId;
    private final String displayName;
    private final HabiTaskCategory category;
    private final float weight;
    private final int blockTypeId;
    private final Color instinctColor;
    private final boolean canDirectlyWin;
    private final Set<Block> scanBlocks;
    /** 方块ID列表（用于延迟查找，解决跨模组加载顺序问题） */
    private final Set<String> scanBlockIds;

    // 回调函数
    private final BiConsumer<Player, HabiTaskInstance> onAssignHandler;
    private final BiConsumer<Player, HabiTaskInstance> onCompleteHandler;
    private final BiFunction<Player, HabiTaskInstance, Boolean> completionChecker;
    private final BiConsumer<Player, HabiTaskInstance> tickHandler;
    private final BiPredicate<Player, HabiTaskInstance> canAssignPredicate;

    private HabiTaskDefinition(Builder builder) {
        this.modId = builder.modId;
        this.taskId = builder.taskId;
        this.displayName = builder.displayName;
        this.category = builder.category;
        this.weight = builder.weight;
        this.blockTypeId = builder.blockTypeId;
        this.instinctColor = builder.instinctColor;
        this.canDirectlyWin = builder.canDirectlyWin;
        this.scanBlocks = builder.scanBlocks;
        this.scanBlockIds = builder.scanBlockIds;
        this.onAssignHandler = builder.onAssignHandler;
        this.onCompleteHandler = builder.onCompleteHandler;
        this.completionChecker = builder.completionChecker;
        this.tickHandler = builder.tickHandler;
        this.canAssignPredicate = builder.canAssignPredicate;
    }

    /** 获取完整任务ID (格式: modId:taskId) */
    public String getFullId() {
        return modId + ":" + taskId;
    }

    public String getModId() { return modId; }
    public String getTaskId() { return taskId; }
    public String getDisplayName() { return displayName; }
    public HabiTaskCategory getCategory() { return category; }
    public float getWeight() { return weight; }
    public int getBlockTypeId() { return blockTypeId; }
    public Color getInstinctColor() { return instinctColor; }
    public boolean canDirectlyWin() { return canDirectlyWin; }
    /** 获取此任务对应的扫描方块类型（用于MapScanner高亮） */
    public Set<Block> getScanBlocks() { return scanBlocks; }

    /**
     * 获取此任务对应的方块ID列表（字符串形式）
     * 用于延迟查找——在 MapScanner 运行时才解析方块，
     * 解决跨模组加载顺序导致的方块找不到问题
     */
    public Set<String> getScanBlockIds() { return scanBlockIds; }

    /** 任务分配时调用 (可用于给予道具等) */
    public void onAssign(Player player, HabiTaskInstance instance) {
        if (onAssignHandler != null) onAssignHandler.accept(player, instance);
    }

    /** 任务完成时调用 (可用于发放奖励等) */
    public void onComplete(Player player, HabiTaskInstance instance) {
        if (onCompleteHandler != null) onCompleteHandler.accept(player, instance);
    }

    /** 自定义完成检查 (返回true表示任务已完成) */
    public boolean checkCompletion(Player player, HabiTaskInstance instance) {
        if (completionChecker != null) return completionChecker.apply(player, instance);
        return instance.isFulfilled();
    }

    /** 每tick回调 (用于自定义进度逻辑) */
    public void onTick(Player player, HabiTaskInstance instance) {
        if (tickHandler != null) tickHandler.accept(player, instance);
    }

    /** 检查是否可为该玩家分配此任务 */
    public boolean canAssign(Player player, HabiTaskInstance instance) {
        if (canAssignPredicate != null) return canAssignPredicate.test(player, instance);
        return true;
    }

    /**
     * 任务定义构建器
     */
    public static class Builder {
        private final String modId;
        private final String taskId;
        private String displayName;
        private HabiTaskCategory category = HabiTaskCategory.ALL;
        private float weight = 1.0f;
        private int blockTypeId = -1;
        private Color instinctColor = new Color(200, 200, 200, 180);
        private boolean canDirectlyWin = false;
        private Set<Block> scanBlocks = Set.of();
        private Set<String> scanBlockIds = Set.of();

        private BiConsumer<Player, HabiTaskInstance> onAssignHandler;
        private BiConsumer<Player, HabiTaskInstance> onCompleteHandler;
        private BiFunction<Player, HabiTaskInstance, Boolean> completionChecker;
        private BiConsumer<Player, HabiTaskInstance> tickHandler;
        private BiPredicate<Player, HabiTaskInstance> canAssignPredicate;

        /**
         * @param modId  注册此任务的模组ID
         * @param taskId 任务ID (在同一模组内唯一)
         */
        public Builder(String modId, String taskId) {
            this.modId = modId;
            this.taskId = taskId;
            this.displayName = taskId;
        }

        /** 设置任务显示名称 */
        public Builder displayName(String name) {
            this.displayName = name;
            return this;
        }

        /** 设置任务分类 (默认 ALL) */
        public Builder category(HabiTaskCategory category) {
            this.category = category;
            return this;
        }

        /** 设置任务权重 (默认 1.0) */
        public Builder weight(float weight) {
            this.weight = weight;
            return this;
        }

        /** 设置对应扫描方块类型ID (≥12，-1表示无方块) */
        public Builder blockTypeId(int blockTypeId) {
            this.blockTypeId = blockTypeId;
            return this;
        }

        /** 设置透视颜色 */
        public Builder instinctColor(Color color) {
            this.instinctColor = color;
            return this;
        }

        /** 设置完成此任务是否可直接获胜 */
        public Builder canDirectlyWin(boolean canDirectlyWin) {
            this.canDirectlyWin = canDirectlyWin;
            return this;
        }

        /**
         * 设置任务对应的扫描方块列表
         * 设置后这些方块会在 MapScanner 中被标记为此任务的 blockTypeId，
         * 从而在开启任务点透视时显示高亮边框
         */
        public Builder scanBlocks(Block... blocks) {
            this.scanBlocks = Set.of(blocks);
            return this;
        }

        /**
         * 设置任务对应的方块ID列表（字符串形式，推荐方式）
         * <p>
         * 与 {@link #scanBlocks(Block...)} 的区别：
         * scanBlockIds 使用的是方块注册名（如 "minecraft:grass_block"），
         * 在 MapScanner 扫描时才会被解析为真实的 Block 对象。
         * 这解决了某些模组的方块在任务注册时尚未加载的问题。
         * <p>
         * 注意：此方法与 scanBlocks 是"或"的关系——
         * MapScanner 会扫描 scanBlocks 和 scanBlockIds 两者的并集。
         *
         * @param blockIds 方块注册名列表，格式如 "mod_id:block_id"
         */
        public Builder scanBlockIds(String... blockIds) {
            this.scanBlockIds = Set.of(blockIds);
            return this;
        }

        /** 任务分配时回调 (可用于给玩家道具) */
        public Builder onAssign(BiConsumer<Player, HabiTaskInstance> handler) {
            this.onAssignHandler = handler;
            return this;
        }

        /** 任务完成时回调 (可用于自定义奖励) */
        public Builder onComplete(BiConsumer<Player, HabiTaskInstance> handler) {
            this.onCompleteHandler = handler;
            return this;
        }

        /** 自定义完成检测 (返回true标记任务完成) */
        public Builder completionChecker(BiFunction<Player, HabiTaskInstance, Boolean> checker) {
            this.completionChecker = checker;
            return this;
        }

        /** 每tick调用 (用于检查进度) */
        public Builder onTick(BiConsumer<Player, HabiTaskInstance> handler) {
            this.tickHandler = handler;
            return this;
        }

        /** 分配条件检查 (返回false则跳过此任务) */
        public Builder canAssign(BiPredicate<Player, HabiTaskInstance> predicate) {
            this.canAssignPredicate = predicate;
            return this;
        }

        public HabiTaskDefinition build() {
            return new HabiTaskDefinition(this);
        }
    }
}
