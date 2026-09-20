package com.habitrain.core.api;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 通用选项投票结算结果。
 *
 * @param voteId     投票实例 id
 * @param winnerId   胜出 option id；无人选项时仍会随机挑一个（见 {@link #randomPick}）；
 *                   投票被取消时为 {@code null}（见 {@link #cancelled}）
 * @param tallies    各 option id 得票（含 0 票）；取消时为空表
 * @param randomPick true 表示全员 0 票后随机，或并列最高票后随机
 * @param cancelled  审核 B16：{@code true} 表示本次结果来自「取消 / 重置」而不是正常结算。
 *                   旧实现里取消路径直接丢弃 {@code onResolved}，消费方的状态机会悬挂，
 *                   且 {@code isActive()==false} 无法区分「已结算」与「已取消」。
 */
public record VoteResult(
        String voteId,
        @Nullable String winnerId,
        Map<String, Integer> tallies,
        boolean randomPick,
        boolean cancelled
) {
    public VoteResult {
        tallies = tallies == null ? Map.of() : Map.copyOf(tallies);
    }

    /**
     * 兼容重载（保持 2.0.10 的四参构造可用），{@code cancelled = false}。
     */
    public VoteResult(String voteId, @Nullable String winnerId,
                      Map<String, Integer> tallies, boolean randomPick) {
        this(voteId, winnerId, tallies, randomPick, false);
    }

    /** 取消 / 重置产生的结算结果（{@code winnerId == null}，票数为空）。 */
    public static VoteResult cancelled(String voteId) {
        return new VoteResult(voteId, null, Map.of(), false, true);
    }
}
