package com.habitrain.core.api;

import java.util.Objects;

/**
 * 任务分类 — per-GameMode 可自定义。
 * 内置快捷常量 ALL 用于通用任务。
 * SRE 原版任务仍使用 {@link com.habitrain.taskapi.api.HabiTaskCategory} 枚举。
 */
public class TaskCategory {
    private final String id;
    private final String displayName;
    private final String gameModeId;

    public static final TaskCategory ALL = new TaskCategory("core:all", "通用", "core");

    public TaskCategory(String id, String displayName, String gameModeId) {
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
        this.gameModeId = Objects.requireNonNull(gameModeId);
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getGameModeId() { return gameModeId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskCategory that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return "TaskCategory{" + id + "}"; }
}
