package com.habitrain.core.api;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

import java.awt.Color;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 任务定义 — 取代 HabiTaskDefinition。
 * 新增: timeLimit、canRepeat、tags 等扩展字段。
 */
public class TaskDefinition {

    private final String modId;
    private final String taskId;
    private final String fullId;
    private final String displayName;

    // 分类：标准 TaskCategory 或自定义 customCategory
    private final TaskCategory category;
    private final String gameModeId;
    private final TaskCategory customCategory;

    private final float weight;
    private final int blockTypeId;
    private final Color instinctColor;
    private final boolean canDirectlyWin;
    private final Set<Block> scanBlocks;
    private final Set<String> scanBlockIds;

    // 新增字段
    private final int timeLimit;           // 0 = 不限时
    private final boolean canRepeat;
    private final boolean shareProgress;
    private final List<String> tags;

    // 回调函数
    private final BiConsumer<Player, TaskInstance> onAssignHandler;
    private final BiConsumer<Player, TaskInstance> onCompleteHandler;
    private final BiConsumer<Player, TaskInstance> onRemoveHandler;
    private final BiConsumer<Player, TaskInstance> onFailHandler;
    private final BiFunction<Player, TaskInstance, Boolean> completionChecker;
    private final BiConsumer<Player, TaskInstance> tickHandler;
    private final BiPredicate<Player, TaskInstance> canAssignPredicate;
    private final ProgressUpdateHandler onProgressUpdateHandler;

    @FunctionalInterface
    public interface ProgressUpdateHandler {
        void onProgressUpdate(Player player, TaskInstance task, int oldProgress);
    }

    private TaskDefinition(Builder builder) {
        this.modId = builder.modId;
        this.taskId = builder.taskId;
        this.fullId = builder.modId + ":" + builder.taskId;
        this.displayName = builder.displayName;
        this.category = builder.category;
        this.gameModeId = builder.gameModeId;
        this.customCategory = builder.customCategory;
        this.weight = builder.weight;
        this.blockTypeId = builder.blockTypeId;
        this.instinctColor = builder.instinctColor;
        this.canDirectlyWin = builder.canDirectlyWin;
        this.scanBlocks = builder.scanBlocks;
        this.scanBlockIds = builder.scanBlockIds;
        this.timeLimit = builder.timeLimit;
        this.canRepeat = builder.canRepeat;
        this.shareProgress = builder.shareProgress;
        this.tags = builder.tags;
        this.onAssignHandler = builder.onAssignHandler;
        this.onCompleteHandler = builder.onCompleteHandler;
        this.onRemoveHandler = builder.onRemoveHandler;
        this.onFailHandler = builder.onFailHandler;
        this.completionChecker = builder.completionChecker;
        this.tickHandler = builder.tickHandler;
        this.canAssignPredicate = builder.canAssignPredicate;
        this.onProgressUpdateHandler = builder.onProgressUpdateHandler;
    }

    // --- Getters ---
    public String getFullId() { return fullId; }
    public String getModId() { return modId; }
    public String getTaskId() { return taskId; }
    public String getDisplayName() { return displayName; }
    public TaskCategory getCategory() { return category; }
    public String getGameModeId() { return gameModeId; }
    public TaskCategory getCustomCategory() { return customCategory; }
    public float getWeight() { return weight; }
    public int getBlockTypeId() { return blockTypeId; }
    public Color getInstinctColor() { return instinctColor; }
    public boolean canDirectlyWin() { return canDirectlyWin; }
    public Set<Block> getScanBlocks() { return scanBlocks; }
    public Set<String> getScanBlockIds() { return scanBlockIds; }
    public int getTimeLimit() { return timeLimit; }
    public boolean canRepeat() { return canRepeat; }
    public boolean isShareProgress() { return shareProgress; }
    public List<String> getTags() { return tags; }

    // --- Callback dispatch ---
    public void onAssign(Player player, TaskInstance instance) { if (onAssignHandler != null) onAssignHandler.accept(player, instance); }
    public void onComplete(Player player, TaskInstance instance) { if (onCompleteHandler != null) onCompleteHandler.accept(player, instance); }
    public void onRemove(Player player, TaskInstance instance) { if (onRemoveHandler != null) onRemoveHandler.accept(player, instance); }
    public void onFail(Player player, TaskInstance instance) { if (onFailHandler != null) onFailHandler.accept(player, instance); }
    public boolean checkCompletion(Player player, TaskInstance instance) { if (completionChecker != null) return completionChecker.apply(player, instance); return instance.isFulfilled(); }
    public void onTick(Player player, TaskInstance instance) { if (tickHandler != null) tickHandler.accept(player, instance); }
    public boolean canAssign(Player player, TaskInstance instance) { if (canAssignPredicate != null) return canAssignPredicate.test(player, instance); return true; }
    public void onProgressUpdate(Player player, TaskInstance instance, int oldProgress) { if (onProgressUpdateHandler != null) onProgressUpdateHandler.onProgressUpdate(player, instance, oldProgress); }

    // --- Builder ---
    public static class Builder {
        private final String modId;
        private final String taskId;
        private String displayName;
        private TaskCategory category = TaskCategory.ALL;
        private String gameModeId = "sre:base";
        private TaskCategory customCategory;
        private float weight = 1.0f;
        private int blockTypeId = -1;
        private Color instinctColor = new Color(200, 200, 200, 180);
        private boolean canDirectlyWin = false;
        private Set<Block> scanBlocks = Set.of();
        private Set<String> scanBlockIds = Set.of();
        private int timeLimit = 0;
        private boolean canRepeat = false;
        private boolean shareProgress = false;
        private List<String> tags = List.of();

        private BiConsumer<Player, TaskInstance> onAssignHandler;
        private BiConsumer<Player, TaskInstance> onCompleteHandler;
        private BiConsumer<Player, TaskInstance> onRemoveHandler;
        private BiConsumer<Player, TaskInstance> onFailHandler;
        private BiFunction<Player, TaskInstance, Boolean> completionChecker;
        private BiConsumer<Player, TaskInstance> tickHandler;
        private BiPredicate<Player, TaskInstance> canAssignPredicate;
        private ProgressUpdateHandler onProgressUpdateHandler;

        public Builder(String modId, String taskId) {
            this.modId = modId;
            this.taskId = taskId;
            this.displayName = taskId;
        }

        public Builder displayName(String name) { this.displayName = name; return this; }
        public Builder category(TaskCategory cat) { this.category = cat; return this; }
        public Builder customCategory(TaskCategory cat) { this.customCategory = cat; return this; }
        public Builder gameMode(String gameModeId) { this.gameModeId = gameModeId; return this; }
        public Builder weight(float w) { this.weight = w; return this; }
        public Builder blockTypeId(int id) { this.blockTypeId = id; return this; }
        public Builder instinctColor(Color c) { this.instinctColor = c; return this; }
        public Builder canDirectlyWin(boolean v) { this.canDirectlyWin = v; return this; }
        public Builder scanBlocks(Block... blocks) { this.scanBlocks = Set.of(blocks); return this; }
        public Builder scanBlockIds(String... ids) { this.scanBlockIds = Set.of(ids); return this; }
        public Builder timeLimit(int seconds) { this.timeLimit = seconds; return this; }
        public Builder canRepeat(boolean v) { this.canRepeat = v; return this; }
        public Builder shareProgress(boolean v) { this.shareProgress = v; return this; }
        public Builder tags(String... t) { this.tags = List.of(t); return this; }

        public Builder onAssign(BiConsumer<Player, TaskInstance> h) { this.onAssignHandler = h; return this; }
        public Builder onComplete(BiConsumer<Player, TaskInstance> h) { this.onCompleteHandler = h; return this; }
        public Builder onRemove(BiConsumer<Player, TaskInstance> h) { this.onRemoveHandler = h; return this; }
        public Builder onFail(BiConsumer<Player, TaskInstance> h) { this.onFailHandler = h; return this; }
        public Builder completionChecker(BiFunction<Player, TaskInstance, Boolean> h) { this.completionChecker = h; return this; }
        public Builder onTick(BiConsumer<Player, TaskInstance> h) { this.tickHandler = h; return this; }
        public Builder canAssign(BiPredicate<Player, TaskInstance> h) { this.canAssignPredicate = h; return this; }
        public Builder onProgressUpdate(ProgressUpdateHandler h) { this.onProgressUpdateHandler = h; return this; }

        public TaskDefinition build() { return new TaskDefinition(this); }
    }
}
