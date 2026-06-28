package com.habitrain.core.api;

import java.util.Objects;

/**
 * 任务分类 — per-GameMode 可自定义。
 * 标准分类常量 MURDER / REPAIR / ALL / CUSTOM 替代旧的 HabiTaskCategory 枚举。
 */
public class TaskCategory {
    private final String id;
    private final String displayName;
    private final String gameModeId;

    // ========== 标准分类常量（替代旧的 HabiTaskCategory 枚举） ==========
    public static final TaskCategory MURDER = new TaskCategory("sre:murder", "谋杀模式", "sre:base");
    public static final TaskCategory REPAIR = new TaskCategory("sre:repair", "修机模式", "sre:base");
    public static final TaskCategory ALL    = new TaskCategory("sre:all", "通用任务", "sre:base");
    public static final TaskCategory CUSTOM = new TaskCategory("sre:custom", "自定义任务", "sre:base");

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
