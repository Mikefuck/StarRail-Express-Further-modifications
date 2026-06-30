package com.habitrain.core.api;

import java.util.Collections;
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
            ? Collections.unmodifiableList(winners)
            : List.of();
        this.reason = reason;
    }

    public static WinResult singleWinner(UUID playerId, String reason) {
        return new WinResult(List.of(playerId), reason);
    }

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
