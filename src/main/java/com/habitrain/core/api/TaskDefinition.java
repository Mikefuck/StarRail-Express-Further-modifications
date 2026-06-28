package com.habitrain.core.api;

import com.habitrain.taskapi.api.HabiTaskCategory;
import java.awt.Color;
import java.util.Set;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 临时占位 — 将在 Task 3 中替换为完整实现。
 */
public class TaskDefinition {
    private final String modId;
    private final String taskId;
    private final String fullId;
    private final String displayName;
    private final HabiTaskCategory originalCategory;
    private final String gameModeId;
    private final float weight;
    private final int blockTypeId;
    private final Color instinctColor;
    private final boolean canDirectlyWin;
    private final int timeLimit;
    private final boolean canRepeat;
    private final List<String> tags;

    private final BiConsumer<Player, TaskInstance> onAssignHandler;
    private final BiConsumer<Player, TaskInstance> onCompleteHandler;
    private final BiFunction<Player, TaskInstance, Boolean> completionChecker;
    private final BiConsumer<Player, TaskInstance> tickHandler;
    private final BiPredicate<Player, TaskInstance> canAssignPredicate;

    // 内部 Player 引用 — 使用 net.minecraft.world.entity.player.Player
    private static class Player {}

    private TaskDefinition(Builder builder) {
        this.modId = builder.modId;
        this.taskId = builder.taskId;
        this.fullId = builder.modId + ":" + builder.taskId;
        this.displayName = builder.displayName;
        this.originalCategory = builder.originalCategory;
        this.gameModeId = builder.gameModeId;
        this.weight = builder.weight;
        this.blockTypeId = builder.blockTypeId;
        this.instinctColor = builder.instinctColor;
        this.canDirectlyWin = builder.canDirectlyWin;
        this.timeLimit = builder.timeLimit;
        this.canRepeat = builder.canRepeat;
        this.tags = builder.tags;
        this.onAssignHandler = builder.onAssignHandler;
        this.onCompleteHandler = builder.onCompleteHandler;
        this.completionChecker = builder.completionChecker;
        this.tickHandler = builder.tickHandler;
        this.canAssignPredicate = builder.canAssignPredicate;
    }

    public String getFullId() { return fullId; }
    public String getModId() { return modId; }
    public String getTaskId() { return taskId; }
    public String getDisplayName() { return displayName; }
    public HabiTaskCategory getOriginalCategory() { return originalCategory; }
    public String getGameModeId() { return gameModeId; }
    public float getWeight() { return weight; }
    public int getBlockTypeId() { return blockTypeId; }
    public Color getInstinctColor() { return instinctColor; }
    public boolean canDirectlyWin() { return canDirectlyWin; }
    public int getTimeLimit() { return timeLimit; }
    public boolean canRepeat() { return canRepeat; }
    public List<String> getTags() { return tags; }

    public static class Builder {
        private final String modId;
        private final String taskId;
        private String displayName;
        private HabiTaskCategory originalCategory = HabiTaskCategory.ALL;
        private String gameModeId = "sre:base";
        private float weight = 1.0f;
        private int blockTypeId = -1;
        private Color instinctColor = new Color(200, 200, 200, 180);
        private boolean canDirectlyWin = false;
        private int timeLimit = 0;
        private boolean canRepeat = false;
        private List<String> tags = List.of();

        private BiConsumer<Player, TaskInstance> onAssignHandler;
        private BiConsumer<Player, TaskInstance> onCompleteHandler;
        private BiFunction<Player, TaskInstance, Boolean> completionChecker;
        private BiConsumer<Player, TaskInstance> tickHandler;
        private BiPredicate<Player, TaskInstance> canAssignPredicate;

        public Builder(String modId, String taskId) {
            this.modId = modId;
            this.taskId = taskId;
            this.displayName = taskId;
        }

        public Builder displayName(String name) { this.displayName = name; return this; }
        public Builder originalCategory(HabiTaskCategory cat) { this.originalCategory = cat; return this; }
        public Builder gameMode(String gameModeId) { this.gameModeId = gameModeId; return this; }
        public Builder weight(float w) { this.weight = w; return this; }
        public Builder blockTypeId(int id) { this.blockTypeId = id; return this; }
        public Builder instinctColor(Color c) { this.instinctColor = c; return this; }
        public Builder canDirectlyWin(boolean v) { this.canDirectlyWin = v; return this; }
        public Builder timeLimit(int seconds) { this.timeLimit = seconds; return this; }
        public Builder canRepeat(boolean v) { this.canRepeat = v; return this; }
        public Builder tags(String... t) { this.tags = List.of(t); return this; }
        public Builder onAssign(BiConsumer<Player, TaskInstance> h) { this.onAssignHandler = h; return this; }
        public Builder onComplete(BiConsumer<Player, TaskInstance> h) { this.onCompleteHandler = h; return this; }
        public Builder completionChecker(BiFunction<Player, TaskInstance, Boolean> h) { this.completionChecker = h; return this; }
        public Builder onTick(BiConsumer<Player, TaskInstance> h) { this.tickHandler = h; return this; }
        public Builder canAssign(BiPredicate<Player, TaskInstance> h) { this.canAssignPredicate = h; return this; }

        public TaskDefinition build() { return new TaskDefinition(this); }
    }
}
