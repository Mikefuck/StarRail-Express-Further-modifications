package com.habitrain.core.game.sre.role.sins.component;

import java.util.*;

/** Integer coin conservation and the inclusive ten-second revival boundary. */
public final class LustPolicy {
    public static final int MIN_PLAYERS = 24;
    public static final int GIFT_COOLDOWN_SECONDS = 90;
    public static final int SELECTION_COOLDOWN_TICKS = 120 * 20;
    public static final int REVIVAL_WINDOW_TICKS = 10 * 20;

    private LustPolicy() {}

    public static Map<UUID, Integer> distribute(int openingPlayers, List<UUID> aliveMarked, UUID selected) {
        List<UUID> recipients = new ArrayList<>(new LinkedHashSet<>(aliveMarked));
        recipients.removeIf(Objects::isNull);
        Map<UUID, Integer> grants = new LinkedHashMap<>();
        if (openingPlayers <= 0 || recipients.isEmpty()) return grants;
        int pool = Math.multiplyExact(openingPlayers, 10);
        if (selected != null && recipients.remove(selected)) {
            grants.put(selected, pool / 2);
            pool -= pool / 2;
        }
        if (!recipients.isEmpty()) {
            int base = pool / recipients.size();
            int extra = pool % recipients.size();
            for (int i = 0; i < recipients.size(); i++) grants.put(recipients.get(i), base + (i < extra ? 1 : 0));
        }
        return grants;
    }

    public static boolean canRevive(long deathTick, long now) {
        return now >= deathTick && now - deathTick <= REVIVAL_WINDOW_TICKS;
    }

    public static boolean canFollow(boolean alive, Set<UUID> marked, Set<UUID> winners) {
        return alive && marked.stream().anyMatch(winners::contains);
    }
}
