package com.habitrain.core.api;

import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of the current mode→map vote orchestrator state for one level.
 *
 * @param phase            IDLE|MODE_VOTING|MAP_VOTING|SWITCHING_MAP|STARTING_MODE
 * @param selectedModeId   winner of mode vote (if already resolved)
 * @param selectedMapId    winner of map vote (if already resolved)
 * @param remainingSeconds approximate remaining option-vote seconds (0 outside voting phases)
 */
public record ModeMapVoteSnapshot(
        String phase,
        @Nullable String selectedModeId,
        @Nullable String selectedMapId,
        int remainingSeconds
) {}
