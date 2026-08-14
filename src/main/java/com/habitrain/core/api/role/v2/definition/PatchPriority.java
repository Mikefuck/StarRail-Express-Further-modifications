package com.habitrain.core.api.role.v2.definition;

/**
 * Stable ordering tier for a v2 {@code MODIFY} patch (or a replacement) when
 * several providers touch the same role.
 *
 * <p>Patches are applied in ascending {@link #value()} order, then by provider
 * mod id, then by stable {@code entryKey}. Only a small set of named tiers is
 * exposed so ordering stays predictable; {@link #value()} is the numeric weight
 * used for the stable sort.
 */
public enum PatchPriority {

    /** Applied before {@link #NORMAL} patches. */
    EARLY(-100),
    /** The default tier. */
    NORMAL(0),
    /** Applied after {@link #NORMAL} patches. */
    LATE(100);

    private final int value;

    PatchPriority(int value) {
        this.value = value;
    }

    /** The numeric weight used for the stable sort (ascending). */
    public int value() {
        return value;
    }
}
