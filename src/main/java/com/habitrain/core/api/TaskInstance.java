package com.habitrain.core.api;

/**
 * 临时占位 — 将在 Task 3 中替换为完整实现。
 */
public class TaskInstance {
    private final TaskDefinition definition;
    private boolean fulfilled = false;
    private int progress = 0;

    public TaskInstance(TaskDefinition definition) {
        this.definition = definition;
    }

    public TaskDefinition getDefinition() { return definition; }
    public String getFullId() { return definition.getFullId(); }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public boolean isFulfilled() { return fulfilled; }
    public void setFulfilled(boolean fulfilled) { this.fulfilled = fulfilled; }
}
