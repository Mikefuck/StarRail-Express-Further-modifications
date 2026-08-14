package com.habitrain.core.api.role.v2.skill;

import com.habitrain.core.api.role.v2.definition.ListOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * A reversible skill-list patch: append, remove matching ids, or replace the
 * whole list. Merge semantics match the legacy {@code ManagedSkillPatch.Mode}.
 */
public record RoleSkillPatch(ListOp op, List<RoleSkillSpec> skills) {

    public RoleSkillPatch {
        Objects.requireNonNull(op, "op");
        skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    }

    public static RoleSkillPatch append(RoleSkillSpec... skills) {
        return new RoleSkillPatch(ListOp.APPEND, List.of(skills));
    }

    public static RoleSkillPatch removeMatchingIds(RoleSkillSpec... skills) {
        return new RoleSkillPatch(ListOp.REMOVE, List.of(skills));
    }

    public static RoleSkillPatch replaceAll(RoleSkillSpec... skills) {
        return new RoleSkillPatch(ListOp.REPLACE_ALL, List.of(skills));
    }

    /**
     * Folds this patch onto {@code current}. {@link ListOp#REMOVE} drops current
     * specs whose ids occur in {@link #skills}; {@link ListOp#APPEND} concatenates
     * and then rejects duplicate ids; {@link ListOp#REPLACE_ALL} discards current.
     */
    public List<RoleSkillSpec> apply(List<RoleSkillSpec> current) {
        List<RoleSkillSpec> baseline = current == null ? List.of() : current;
        return switch (op) {
            case APPEND -> concatUnique(baseline, skills);
            case REMOVE -> {
                var remove = ids(skills);
                yield baseline.stream().filter(s -> !remove.contains(s.id())).toList();
            }
            case REPLACE_ALL -> List.copyOf(skills);
        };
    }

    private static List<RoleSkillSpec> concatUnique(List<RoleSkillSpec> baseline, List<RoleSkillSpec> extra) {
        LinkedHashMap<net.minecraft.resources.ResourceLocation, RoleSkillSpec> out = new LinkedHashMap<>();
        for (RoleSkillSpec spec : baseline) {
            if (out.put(spec.id(), spec) != null) {
                throw new IllegalStateException("Duplicate skill id in baseline: " + spec.id());
            }
        }
        for (RoleSkillSpec spec : extra) {
            if (out.put(spec.id(), spec) != null) {
                throw new IllegalStateException("Duplicate skill id when appending: " + spec.id());
            }
        }
        return List.copyOf(out.values());
    }

    private static java.util.Set<net.minecraft.resources.ResourceLocation> ids(List<RoleSkillSpec> specs) {
        java.util.Set<net.minecraft.resources.ResourceLocation> ids = new java.util.HashSet<>();
        for (RoleSkillSpec spec : specs) {
            ids.add(spec.id());
        }
        return ids;
    }
}
