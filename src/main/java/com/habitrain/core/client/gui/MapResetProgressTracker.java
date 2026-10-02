package com.habitrain.core.client.gui;

import net.minecraft.util.Mth;

/**
 * 从上游 actionbar「重置地图中 xx%」推导出的整备进度。
 *
 * <p>SRE 重置地图分两段：整列车复制（黄色文字）→ 复位门窗等机关方块（金色文字），两段各自 0→100，
 * 每 10 tick 发一次 actionbar。客户端屏蔽这条 actionbar 后用本类把两段拼成一条单调进度，
 * 供 {@link MapResetProgressHud} 在没有投票会话时（指令开局、切换地图、中途加入）显示；
 * 有投票会话时只借用这里的阶段判断，进度以服务端 {@code MapVoteProgressPayload} 为准。</p>
 */
public final class MapResetProgressTracker {
    /** 两次 actionbar 相隔超过此值视为新的一轮重置，进度从零开始。 */
    static final long NEW_RUN_GAP_MILLIS = 10_000L;
    /**
     * 最后一次 actionbar 后仍显示进度牌的时长。整车复制结束后上游会先扫描任务点再开始复位方块，
     * 中间可能空档数秒，留足余量免得进度牌收起又落下。
     */
    static final long VISIBLE_AFTER_LAST_MILLIS = 6_000L;
    /** 收到「游戏开始！」后让 100% 停留片刻再收起。 */
    static final long FINISHED_HOLD_MILLIS = 800L;
    /** 无投票会话时，整车复制占进度条的前 80%。 */
    private static final int CARRIAGES_END = 80;

    public enum Stage {
        /** 整列车复制。 */
        CARRIAGES,
        /** 复位门窗、按钮、床等机关方块。 */
        FIXTURES,
        /** 重置完毕，等待发车。 */
        DEPARTING
    }

    private boolean observed;
    private long lastObservedAt;
    private long finishedAt;
    private boolean carriagesSeen;
    private int progress;
    private Stage stage = Stage.CARRIAGES;

    /**
     * 记录一条上游重置进度。
     *
     * @param percent  该阶段的百分比（0..100）
     * @param fixtures true=复位机关阶段，false=整车复制阶段
     */
    public void observe(int percent, boolean fixtures, long now) {
        if (!observed || now - lastObservedAt > NEW_RUN_GAP_MILLIS || finishedAt > 0L) {
            reset();
        }
        observed = true;
        lastObservedAt = now;
        int clamped = Mth.clamp(percent, 0, 100);
        int mapped;
        if (fixtures) {
            stage = Stage.FIXTURES;
            int start = carriagesSeen ? CARRIAGES_END : 0;
            mapped = start + Math.round(clamped * (100 - start) / 100.0f);
        } else {
            carriagesSeen = true;
            if (stage != Stage.FIXTURES) {
                stage = Stage.CARRIAGES;
            }
            mapped = Math.round(clamped * CARRIAGES_END / 100.0f);
        }
        progress = Math.max(progress, mapped);
    }

    /** 上游提示「游戏开始！」：本轮重置结束。 */
    public void finish(long now) {
        if (!observed || finishedAt > 0L) {
            return;
        }
        finishedAt = now;
        progress = 100;
        stage = Stage.DEPARTING;
    }

    public boolean isVisible(long now) {
        if (!observed) {
            return false;
        }
        if (finishedAt > 0L) {
            return now - finishedAt < FINISHED_HOLD_MILLIS;
        }
        return now - lastObservedAt < VISIBLE_AFTER_LAST_MILLIS;
    }

    /** 最近是否收到过上游进度（用于借用阶段判断）。 */
    public boolean isRecent(long now) {
        return observed && now - lastObservedAt < VISIBLE_AFTER_LAST_MILLIS;
    }

    public int progress() {
        return progress;
    }

    public Stage stage() {
        return stage;
    }

    public void reset() {
        observed = false;
        lastObservedAt = 0L;
        finishedAt = 0L;
        carriagesSeen = false;
        progress = 0;
        stage = Stage.CARRIAGES;
    }
}
