package com.habitrain.core.game.sre.modifier;

import org.agmas.harpymodloader.modifiers.SREModifier;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Seven virtues group membership and exclusivity helpers.
 * Membership = six habitrain virtues + optional upstream generous.
 */
public final class VirtueGroup {
    private VirtueGroup() {}

    /** All registered virtue modifiers (skips nulls; includes GENEROUS when linked). */
    public static Set<SREModifier> all() {
        Set<SREModifier> s = new LinkedHashSet<>();
        addIfPresent(s, HabiModifiers.HUMILITY);
        addIfPresent(s, HabiModifiers.MERCY);
        addIfPresent(s, HabiModifiers.PATIENCE);
        addIfPresent(s, HabiModifiers.DILIGENCE);
        addIfPresent(s, HabiModifiers.TEMPERANCE);
        addIfPresent(s, HabiModifiers.CHASTITY);
        addIfPresent(s, HabiModifiers.GENEROUS);
        return Collections.unmodifiableSet(s);
    }

    public static boolean isVirtue(SREModifier m) {
        return m != null && all().contains(m);
    }

    /** Alias for {@link #isVirtue(SREModifier)}. */
    public static boolean contains(SREModifier m) {
        return isVirtue(m);
    }

    /** Hard exclusive pair: patience ↔ diligence. */
    public static boolean isHardExclusivePair(SREModifier a, SREModifier b) {
        if (a == null || b == null) {
            return false;
        }
        return (a == HabiModifiers.PATIENCE && b == HabiModifiers.DILIGENCE)
                || (a == HabiModifiers.DILIGENCE && b == HabiModifiers.PATIENCE);
    }

    /** Alias for {@link #isHardExclusivePair(SREModifier, SREModifier)}. */
    public static boolean areExclusive(SREModifier a, SREModifier b) {
        return isHardExclusivePair(a, b);
    }

    private static void addIfPresent(Set<SREModifier> s, SREModifier m) {
        if (m != null) {
            s.add(m);
        }
    }
}
