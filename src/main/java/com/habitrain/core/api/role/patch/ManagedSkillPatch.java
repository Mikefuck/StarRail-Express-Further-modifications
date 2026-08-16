package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;

import java.util.List;
import java.util.Objects;

/**
 * Declarative skill patch whose definitions can be added and removed by core
 * while preserving the target role's pre-existing definitions.
 */
@FunctionalInterface
public interface ManagedSkillPatch {
    List<RoleSkill.Definition> getDefinitions(SRERole original);

    /** How managed definitions are combined with the role's captured baseline. */
    default Mode mode() {
        return Mode.APPEND;
    }

    /**
     * Replace baseline definitions whose ids match a managed definition, while
     * preserving every other upstream skill.
     */
    static ManagedSkillPatch replaceMatchingIds(ManagedSkillPatch delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new ManagedSkillPatch() {
            @Override
            public List<RoleSkill.Definition> getDefinitions(SRERole original) {
                return delegate.getDefinitions(original);
            }

            @Override
            public Mode mode() {
                return Mode.REPLACE_MATCHING_IDS;
            }
        };
    }

    /** Replace the role's complete unified-skill list while this patch is active. */
    static ManagedSkillPatch replaceAll(ManagedSkillPatch delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new ManagedSkillPatch() {
            @Override
            public List<RoleSkill.Definition> getDefinitions(SRERole original) {
                return delegate.getDefinitions(original);
            }

            @Override
            public Mode mode() {
                return Mode.REPLACE_ALL;
            }
        };
    }

    enum Mode {
        APPEND,
        REPLACE_MATCHING_IDS,
        REPLACE_ALL
    }
}
