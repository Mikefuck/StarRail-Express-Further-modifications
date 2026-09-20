package com.habitrain.core.api.match;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only end-of-round snapshot for addons (lottery grants, records).
 * Built by core from SRE round-end CCA; downstream must not re-read those fields.
 */
public final class MatchSettlement {
    private final String modeId;
    private final MatchWinKind winKind;
    private final String matchKey;
    private final Set<UUID> participants;
    private final Set<UUID> winners;
    private final Map<UUID, MatchWinFaction> factions;

    public MatchSettlement(
            String modeId,
            MatchWinKind winKind,
            String matchKey,
            Set<UUID> participants,
            Set<UUID> winners,
            Map<UUID, MatchWinFaction> factions) {
        this.modeId = modeId == null || modeId.isBlank() ? "sre:murder" : modeId;
        this.winKind = winKind == null ? MatchWinKind.NONE : winKind;
        this.matchKey = matchKey == null ? "" : matchKey;
        this.participants = participants == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(participants));
        this.winners = winners == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(winners));
        this.factions = factions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(factions));
    }

    /** Canonical short mode id ({@code habitrain:blackout} / {@code sre:murder} / {@code sre:repair}). */
    public String modeId() {
        return modeId;
    }

    public MatchWinKind winKind() {
        return winKind;
    }

    /** Durable round fingerprint {@code dimension|startWorldTick|startMillis}. */
    public String matchKey() {
        return matchKey;
    }

    public Set<UUID> participants() {
        return participants;
    }

    public Set<UUID> winners() {
        return winners;
    }

    /**
     * 阵营查询：未知玩家 / 非参与者一律返回 {@link MatchWinFaction#PASSENGER}。
     *
     * <p><b>审核 M-15</b>：抽奖类消费方可能把「查不到」当成「乘客阵营」而误发奖。
     * 需要区分二者时请用 {@link #factionOfOrEmpty}。</p>
     */
    public MatchWinFaction factionOf(@Nullable UUID player) {
        if (player == null) {
            return MatchWinFaction.PASSENGER;
        }
        return factions.getOrDefault(player, MatchWinFaction.PASSENGER);
    }

    /**
     * 阵营查询：只有 {@link #factions()} 里真的有该玩家时才返回，否则空。
     * 这是「查不到」与「乘客阵营」可区分的版本（审核 M-15）。
     */
    public java.util.Optional<MatchWinFaction> factionOfOrEmpty(@Nullable UUID player) {
        if (player == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(factions.get(player));
    }

    public Map<UUID, MatchWinFaction> factions() {
        return factions;
    }
}
