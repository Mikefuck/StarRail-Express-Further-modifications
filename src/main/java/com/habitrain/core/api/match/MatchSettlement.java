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

    public MatchWinFaction factionOf(@Nullable UUID player) {
        if (player == null) {
            return MatchWinFaction.PASSENGER;
        }
        return factions.getOrDefault(player, MatchWinFaction.PASSENGER);
    }

    public Map<UUID, MatchWinFaction> factions() {
        return factions;
    }
}
