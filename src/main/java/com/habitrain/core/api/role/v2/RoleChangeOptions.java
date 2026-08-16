package com.habitrain.core.api.role.v2;

/**
 * Options controlling a role change transaction.
 *
 * <p>{@code recordTimeline} controls whether the "role A -> role B" timeline
 * entry is written; {@code addStats} controls whether the role/faction play-count
 * statistics are incremented. Both default to {@code true} for a normal
 * conversion; callers that want custom timeline text or no stat churn pass
 * {@code false}. {@code reinitialize} re-runs the full change chain even when
 * the player already holds the target role (fix-doc §11.3).
 */
public record RoleChangeOptions(boolean recordTimeline, boolean addStats, boolean reinitialize) {

    /** Source-compatible constructor without the reinitialize flag. */
    public RoleChangeOptions(boolean recordTimeline, boolean addStats) {
        this(recordTimeline, addStats, false);
    }

    /** Default options: record the timeline and update stats. */
    public static RoleChangeOptions defaults() {
        return new RoleChangeOptions(true, true, false);
    }

    /** Options that skip both the timeline and stat updates. */
    public static RoleChangeOptions silent() {
        return new RoleChangeOptions(false, false, false);
    }

    /** Options that force a full re-initialization even on a same-role change. */
    public static RoleChangeOptions forceReinitialize() {
        return new RoleChangeOptions(true, true, true);
    }

    /** Returns a copy of this option set with the reinitialize flag set. */
    public RoleChangeOptions withReinitialize() {
        return new RoleChangeOptions(recordTimeline, addStats, true);
    }
}
