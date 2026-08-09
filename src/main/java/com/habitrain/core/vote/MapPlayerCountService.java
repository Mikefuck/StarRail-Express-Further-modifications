package com.habitrain.core.vote;

import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Player-count based map draw: splits round candidates into maps recommended for the
 * current player count and the rest, then randomly draws up to {@code drawCount} maps —
 * recommended first, filled from non-matching maps (flagged so the vote UI can mark
 * them as "not recommended for this player count"). Replaces the old fixed pool rotation.
 */
public final class MapPlayerCountService {
    /** Suffix appended to drawn-but-not-recommended map display names in the vote UI. */
    public static final String NOT_RECOMMENDED_MARK = " §8[§7玩家人数不建议选择此地图§8]";

    private MapPlayerCountService() {}

    public static boolean shouldApply(ModeMapVoteSettings settings) {
        return settings != null && settings.playerCountOrDefault().enabled;
    }

    /**
     * Draw up to {@code drawCount} maps for {@code playerCount}.
     *
     * @return drawn map ids in display order (recommended first, non-matching appended)
     *         plus the ids that were appended because the recommended pool ran short.
     */
    public static DrawResult draw(
            ModeMapVoteSettings settings,
            List<String> candidates,
            int playerCount,
            Random random
    ) {
        List<String> cand = candidates != null ? new ArrayList<>(candidates) : new ArrayList<>();
        int want = settings.playerCountOrDefault().clampDrawCount(settings.playerCountOrDefault().drawCount);
        if (cand.isEmpty() || want <= 0) {
            return new DrawResult(List.of(), Set.of());
        }

        List<String> recommended = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String id : cand) {
            if (id == null || id.isBlank()) continue;
            MapVoteEntry entry = settings.maps.get(id);
            // missing entry = no player-count restriction = always recommended
            if (entry == null || entry.matchesPlayerCount(playerCount)) {
                recommended.add(id);
            } else {
                other.add(id);
            }
        }

        Random rng = random != null ? random : new Random();
        Collections.shuffle(recommended, rng);
        Collections.shuffle(other, rng);

        want = Math.min(want, cand.size());
        List<String> ids = new ArrayList<>();
        Set<String> notRecommended = new HashSet<>();

        int take = Math.min(want, recommended.size());
        for (int i = 0; i < take; i++) {
            ids.add(recommended.get(i));
        }
        int need = want - take;
        if (need > 0) {
            int t = Math.min(need, other.size());
            for (int i = 0; i < t; i++) {
                String id = other.get(i);
                ids.add(id);
                notRecommended.add(id);
            }
        }
        return new DrawResult(ids, notRecommended);
    }

    /** Result of a player-count draw. */
    public record DrawResult(List<String> ids, Set<String> notRecommended) {
        public DrawResult {
            ids = List.copyOf(ids);
            notRecommended = Set.copyOf(notRecommended);
        }
    }
}
