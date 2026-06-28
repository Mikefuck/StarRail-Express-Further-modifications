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
        if (target >= 0.85f) return 10f;
        float boost = (target / (1f - target)) * ((float) origCount / (float) dlcCount);
        return Math.max(0.0f, Math.min(10.0f, boost));
    }

    public static float calcDlcPercent(float boost, long dlcCount, long origCount) {
        float dlcTotal = boost * dlcCount;
        float grand = dlcTotal + origCount;
        return grand > 0 ? dlcTotal / grand : 0;
    }
}
