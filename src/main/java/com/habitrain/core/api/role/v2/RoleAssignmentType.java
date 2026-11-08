package com.habitrain.core.api.role.v2;

/** SRE assignment buckets, distinct from the overlapping {@link RoleFaction} predicates. */
public enum RoleAssignmentType {
    CIVILIAN(1), NEUTRAL(2), KILLER_NEUTRAL(3), KILLER(4), VIGILANTE(5);

    private final int id;

    RoleAssignmentType(int id) { this.id = id; }

    public int id() { return id; }

    public static java.util.Optional<RoleAssignmentType> fromId(int id) {
        for (var value : values()) if (value.id == id) return java.util.Optional.of(value);
        return java.util.Optional.empty();
    }
}
