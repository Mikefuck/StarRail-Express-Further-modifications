package com.habitrain.core.game.sre.role.sins.component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-owned death cycles for one round. Entity replacement and rest visits do not reset them. */
final class LustRestrictionState {
    private record Death(long generation, boolean exempt) {}
    private final Map<UUID, Death> deaths = new HashMap<>();
    private long generation;

    void died(UUID player) {
        deaths.computeIfAbsent(player, ignored -> new Death(++generation, false));
    }

    boolean isDead(UUID player) { return deaths.containsKey(player); }

    boolean exempt(UUID player) {
        Death death = deaths.get(player);
        if (death == null) return false;
        deaths.put(player, new Death(death.generation(), true));
        return true;
    }

    boolean isExempt(UUID player) {
        Death death = deaths.get(player);
        return death != null && death.exempt();
    }

    boolean hidden(UUID player, boolean effectiveLustExists) {
        return effectiveLustExists && isDead(player) && !isExempt(player);
    }

    void revived(UUID player) { deaths.remove(player); }
}
