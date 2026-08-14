package com.habitrain.core.api.role.v2.skill;

import io.wifi.starrailexpress.api.RoleSkill;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Declarative handle for one unified skill: a stable id plus the optional
 * upstream {@link RoleSkill.Definition} that will be registered at freeze.
 *
 * <p>The definition may be omitted in unit tests that only exercise list-merge
 * semantics. Runtime ADD/MODIFY paths should always supply a finished
 * {@code RoleSkill.skill(...).build()} definition.
 */
public record RoleSkillSpec(ResourceLocation id, @Nullable RoleSkill.Definition definition) {

    public RoleSkillSpec {
        Objects.requireNonNull(id, "id");
        if (definition != null && definition.id() != null && !id.equals(definition.id())) {
            throw new IllegalArgumentException(
                    "RoleSkillSpec id " + id + " does not match definition id " + definition.id());
        }
    }

    /** Id-only spec for merge tests and remove-matching patches. */
    public static RoleSkillSpec of(ResourceLocation id) {
        return new RoleSkillSpec(id, null);
    }

    /** Wraps a finished unified skill definition, using {@code definition.id()} as the key. */
    public static RoleSkillSpec of(RoleSkill.Definition definition) {
        Objects.requireNonNull(definition, "definition");
        ResourceLocation id = definition.id();
        if (id == null) {
            throw new IllegalArgumentException("RoleSkill.Definition must carry an id");
        }
        return new RoleSkillSpec(id, definition);
    }
}
