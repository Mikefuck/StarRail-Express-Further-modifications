package com.habitrain.core.api;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 游戏胜利结果值对象。
 * 由 GameMode.checkWinCondition() 返回。
 */
public class WinResult {
    private final List<UUID> winners;
    private final String reason;

    public WinResult(List<UUID> winners, @Nullable String reason) {
        this.winners = winners != null
            ? List.copyOf(winners)
            : List.of();
        // 审核 A3：reason 允许为 null（WinFoldResult 会直接透传可空 reason），
        // 而 GameModeRegistry.stop 会把它打进日志行并抛 NPE。
        // 与 MatchSettlement 保持一致：null 归一为空串，getReason() 永不为 null。
        this.reason = reason == null ? "" : reason;
    }

    /**
     * 单一胜者。{@code playerId} 不可为 null（{@code List.of} 会 NPE）。
     * 无人获胜时使用 {@link #noWinner(String)}。
     */
    public static WinResult singleWinner(UUID playerId, String reason) {
        return new WinResult(List.of(playerId), reason);
    }

    /**
     * 空胜者列表（超时、团灭、中止等）。
     *
     * <p>审核 M-23：本方法与 {@link #forceEnd(String)} 的实现完全相同，区别只在<b>语义</b>——
     * {@code noWinner} 表示「规则判定无人获胜」，{@code forceEnd} 表示「管理员/系统强制终止」。
     * 消费方不应对两者做行为区分。</p>
     */
    public static WinResult noWinner(@Nullable String reason) {
        return new WinResult(List.of(), reason);
    }

    /** 管理员/系统强制终止；行为等同 {@link #noWinner(String)}（见其 javadoc）。 */
    public static WinResult forceEnd(@Nullable String reason) {
        return new WinResult(List.of(), reason);
    }

    public List<UUID> getWinners() { return winners; }

    /** 结算原因；永不为 {@code null}（传入 null 时归一为空串，审核 A3）。 */
    public String getReason() { return reason; }

    public boolean hasWinner() { return !winners.isEmpty(); }
}
