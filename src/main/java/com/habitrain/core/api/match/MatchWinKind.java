package com.habitrain.core.api.match;

import java.util.Locale;

/**
 * Coarse win bucket for settlement consumers. Mapped from SRE {@code GameUtils.WinStatus}.
 *
 * <p>约定（审核 B22）：无法识别时返回 {@link #NONE}；能识别为「有胜负但不在本枚举覆盖范围」
 * 时返回 {@link #OTHER}。两者都不为 {@code null}。
 */
public enum MatchWinKind {
    NONE,
    INNOCENT,
    KILLER,
    CUSTOM,
    NO_PLAYER,
    OTHER;

    public static MatchWinKind fromWinStatusName(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        // 审核 B9：Locale.ROOT，避免 tr/az 等默认 Locale 破坏字面量比较。
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "NONE", "NOT_MODIFY" -> NONE;
            case "KILLERS" -> KILLER;
            case "PASSENGERS", "TIME" -> INNOCENT;
            case "CUSTOM", "CUSTOM_COMPONENT", "LOVERS", "GAMBLER", "RECORDER",
                    "LOOSE_END", "NIAN_SHOU" -> CUSTOM;
            case "NO_PLAYER" -> NO_PLAYER;
            default -> OTHER;
        };
    }

    public boolean isKillerWin() {
        return this == KILLER;
    }

    public boolean isInnocentWin() {
        return this == INNOCENT;
    }
}
