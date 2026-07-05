package com.habitrain.core.task;

/**
 * 自动平衡计算器 — 从原 HabiConfigManager 中提取的纯逻辑。
 *
 * target × originalCount
 * boost = ─────────────────────
 * (1-target) × dlcCount
 */
public class TaskBalancer {

    private TaskBalancer() {}

    public static float calcBoost(float target, long dlcCount, long origCount) {
        if (dlcCount <= 0 || origCount <= 0) return 1.0f;
        if (target <= 0f) return 0f;
        // ConfigManager 将 target 钳制在 [0.1, 0.8]，无需处理 target >= 0.85 的特殊分支。
        // 保留 Math.min(10.0f, ...) 作为上限保护。
        float boost = (target / (1f - target)) * ((float) origCount / (float) dlcCount);
        return Math.max(0.0f, Math.min(10.0f, boost));
    }

}
