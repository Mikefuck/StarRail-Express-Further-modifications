package com.habitrain.core.api;

import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of the current mode→map vote orchestrator state for one level.
 *
 * @param phase            orchestrator phase（审核 B7：由裸 String 改为公开枚举）
 * @param selectedModeId   winner of mode vote (if already resolved)
 * @param selectedMapId    winner of map vote (if already resolved)
 * @param remainingSeconds approximate remaining option-vote seconds (0 outside voting phases)
 */
public record ModeMapVoteSnapshot(
        ModeMapVotePhase phase,
        @Nullable String selectedModeId,
        @Nullable String selectedMapId,
        int remainingSeconds
) {
    public ModeMapVoteSnapshot {
        phase = phase != null ? phase : ModeMapVotePhase.IDLE;
    }
}
