package com.habitrain.core.api;

import java.util.List;
import java.util.UUID;

/**
 * 游戏胜利结果值对象。
 * 由 GameMode.checkWinCondition() 返回。
 */
public class WinResult {
    private final List<UUID> winners;
    private final String reason;

    public WinResult(List<UUID> winners, String reason) {
        this.winners = winners != null
            ? List.copyOf(winners)
            : List.of();
        this.reason = reason;
    }

    /**
     * 单一胜者。{@code playerId} 不可为 null（{@code List.of} 会 NPE）。
     * 无人获胜时使用 {@link #noWinner(String)}。
     */
    public static WinResult singleWinner(UUID playerId, String reason) {
        return new WinResult(List.of(playerId), reason);
    }

    /** 空胜者列表（超时、团灭、中止等）。 */
    public static WinResult noWinner(String reason) {
        return new WinResult(List.of(), reason);
    }

    public static WinResult forceEnd(String reason) {
        return new WinResult(List.of(), reason);
    }

    public List<UUID> getWinners() { return winners; }
    public String getReason() { return reason; }
    public boolean hasWinner() { return !winners.isEmpty(); }
}
