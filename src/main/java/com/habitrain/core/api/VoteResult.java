package com.habitrain.core.api;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;

/**
 * 通用选项投票结算结果。
 *
 * @param voteId     投票实例 id
 * @param winnerId   胜出 option id；无人选项时仍会随机挑一个（见 {@link #randomPick}）
 * @param tallies    各 option id 得票（含 0 票）
 * @param randomPick true 表示全员 0 票后随机，或并列最高票后随机
 */
public record VoteResult(
        String voteId,
        @Nullable String winnerId,
        Map<String, Integer> tallies,
        boolean randomPick
) {
    public VoteResult {
        tallies = tallies == null ? Map.of() : Collections.unmodifiableMap(tallies);
    }
}
