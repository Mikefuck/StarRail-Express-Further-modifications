package com.habitrain.core.api.match;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Grant / settlement faction bucket. Distinct from {@code RoleFaction} (which
 * includes vigilante/mafia tags used for queries).
 */
public enum MatchWinFaction {
    PASSENGER,
    KILLER,
    NEUTRAL;

    /**
     * Blackout end-faction name → bucket.
     *
     * <p>审核 B22/M-14：与 {@link MatchSettlement#factionOf}（未知玩家 → {@link #PASSENGER}）
     * 不同，本方法对<b>未知阵营名</b>返回 {@code null}（调用方必须判空），
     * 因为「没有阵营」与「乘客阵营」在发奖语义上完全不同。
     * 需要哨兵值时请用 {@link #fromBlackoutNameOrNeutral}。</p>
     *
     * <p>入参会被 {@code trim} 并按 {@link Locale#ROOT} 转大写，因此
     * {@code " bad "} 与 {@code "BAD"} 等价（审核 M-14）。</p>
     */
    public static @Nullable MatchWinFaction fromBlackoutName(String factionName, boolean killerWon) {
        if (factionName == null || factionName.isBlank()) {
            return null;
        }
        return switch (factionName.trim().toUpperCase(Locale.ROOT)) {
            case "BAD" -> KILLER;
            case "SIN_KILLER_SHARE" -> killerWon ? KILLER : NEUTRAL;
            case "SIN_INDEPENDENT" -> NEUTRAL;
            case "GOOD" -> PASSENGER;
            default -> null;
        };
    }

    /** Same as {@link #fromBlackoutName} but maps unknown names to {@link #NEUTRAL}. */
    public static MatchWinFaction fromBlackoutNameOrNeutral(String factionName, boolean killerWon) {
        MatchWinFaction faction = fromBlackoutName(factionName, killerWon);
        return faction != null ? faction : NEUTRAL;
    }

    public static MatchWinFaction fromRoleFlags(
            boolean neutrals,
            boolean customWinner,
            boolean neutralForInnocent,
            boolean neutralForKiller,
            boolean killer,
            boolean killerTeam,
            boolean canUseKiller,
            boolean mafiaTeam) {
        if (neutrals
                || customWinner
                || (neutralForInnocent && neutralForKiller && !killerTeam && !canUseKiller)) {
            return NEUTRAL;
        }
        if (killer || killerTeam || canUseKiller || mafiaTeam) {
            return KILLER;
        }
        return PASSENGER;
    }
}
